package dev.acme.adbtoolbox.adapters.adb.ddmlib

import com.android.ddmlib.AdbCommandRejectedException
import com.android.ddmlib.IDevice
import com.android.ddmlib.IShellOutputReceiver
import com.android.ddmlib.ShellCommandUnresponsiveException
import com.android.ddmlib.TimeoutException
import dev.acme.adbtoolbox.domain.adb.AdbDeviceRequest
import dev.acme.adbtoolbox.domain.adb.AdbOperation
import dev.acme.adbtoolbox.domain.adb.AdbOutcome
import dev.acme.adbtoolbox.domain.adb.AdbRequest
import dev.acme.adbtoolbox.domain.adb.AdbStreamEvent
import dev.acme.adbtoolbox.domain.adb.AdbTextResult
import dev.acme.adbtoolbox.domain.adb.AdbTransport
import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import dev.acme.adbtoolbox.domain.process.ByteSink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration

// ddmlib's `executeShellCommand` requires a `long` timeout; 0 is its documented "no timeout" value,
// so this adapter's coroutine timeout/cancellation is the only thing bounding the call (ADR 0005:
// timeout is a typed AdbOutcome, cancellation tears the call down cooperatively via
// IShellOutputReceiver.isCancelled — never left hardcoded false). Never use a huge value instead:
// Android Studio's adblib-backed IDevice converts it to nanoseconds, overflows, and its
// idle-monitoring heartbeat then busy-spins while the command never completes.
private const val NO_DDMLIB_TIMEOUT_MILLIS = 0L

/**
 * The `:adapters-adb` [AdbTransport] backed by ddmlib (ADR 0005): device discovery and shell
 * execution only, matching the scope of this task. Never selects/falls back to the binary
 * transport itself — that choice is made once, at the `:intellij` composition root (task 007).
 *
 * Every device-scoped call resolves [DeviceSerial] against [deviceSource] explicitly — never "the
 * first device" — and rejects a device that is not [IDevice.DeviceState.ONLINE] with a typed
 * [AdbOutcome.TransportFailure] rather than attempting the call anyway.
 *
 * ddmlib's shell v1 protocol (`IDevice.executeShellCommand`) merges stdout/stderr into one stream
 * and reports no OS exit code, so every completed call here reports [AdbOutcome.Completed] with a
 * `null` exit code (never fabricated as `0`) and an empty `stderr` — an honest transport
 * limitation, not a parsing gap.
 */
class DdmlibAdbTransport(
    private val deviceSource: DdmlibDeviceSource,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AdbTransport {

    override suspend fun executeText(request: AdbRequest): AdbTextResult {
        if (request !is AdbDeviceRequest) {
            return unsupportedText("ddmlib text execution requires a device-scoped request")
        }
        val operation = request.operation
        if (operation !is AdbOperation.Shell) {
            return unsupportedText("ddmlib text execution only supports shell operations")
        }
        val device = when (val lookup = locateOnlineDevice(request.serial)) {
            is DeviceLookup.Failure -> return AdbTextResult(AdbOutcome.TransportFailure(lookup.reason), "", "")
            is DeviceLookup.Unavailable -> return unsupportedText(lookup.reason)
            is DeviceLookup.Found -> lookup.device
        }

        val stdout = StringBuilder()
        val decoder = Utf8ChunkDecoder()
        val outcome = runDdmlibShell(device, operation.command.render(), request.timeout) { data, offset, length ->
            stdout.append(decoder.decode(data, offset, length))
        }
        stdout.append(decoder.finish())
        return AdbTextResult(outcome, stdout.toString(), stderr = "")
    }

    override fun executeStream(request: AdbRequest): Flow<AdbStreamEvent> = callbackFlow {
        val operation = (request as? AdbDeviceRequest)?.operation
        if (request !is AdbDeviceRequest || operation !is AdbOperation.Shell) {
            send(AdbStreamEvent.Completed(AdbOutcome.Unsupported("ddmlib streaming only supports device-scoped shell operations")))
            close()
            return@callbackFlow
        }
        val device = when (val lookup = locateOnlineDevice(request.serial)) {
            is DeviceLookup.Failure -> {
                send(AdbStreamEvent.Completed(AdbOutcome.TransportFailure(lookup.reason)))
                close()
                return@callbackFlow
            }
            is DeviceLookup.Unavailable -> {
                send(AdbStreamEvent.Completed(AdbOutcome.Unsupported(lookup.reason)))
                close()
                return@callbackFlow
            }
            is DeviceLookup.Found -> lookup.device
        }
        val command = operation.command.render()

        val job = launch(ioDispatcher) {
            val decoder = Utf8LineDecoder()
            val outcome = runDdmlibShell(device, command, request.timeout) { data, offset, length ->
                decoder.decode(data, offset, length).forEach { line -> trySendBlocking(AdbStreamEvent.Line(line)) }
            }
            decoder.finish().forEach { line -> trySendBlocking(AdbStreamEvent.Line(line)) }
            send(AdbStreamEvent.Completed(outcome))
            close()
        }

        // Tears the underlying ddmlib call down and detaches the receiver on collector
        // cancellation/disposal — it never just stops reading and leaves it running (ADR 0005).
        awaitClose { job.cancel() }
    }

    override suspend fun executeBinary(request: AdbRequest, sink: ByteSink): AdbOutcome {
        if (request !is AdbDeviceRequest) {
            return AdbOutcome.Unsupported("ddmlib binary execution requires a device-scoped request")
        }
        val operation = request.operation
        if (operation !is AdbOperation.Exec) {
            return AdbOutcome.Unsupported("ddmlib binary execution only supports exec operations")
        }
        val device = when (val lookup = locateOnlineDevice(request.serial)) {
            is DeviceLookup.Failure -> return AdbOutcome.TransportFailure(lookup.reason)
            is DeviceLookup.Unavailable -> return AdbOutcome.Unsupported(lookup.reason)
            is DeviceLookup.Found -> lookup.device
        }

        // ddmlib's public IDevice API exposes no dedicated "exec:" passthrough distinct from the
        // shell service; the literal argv is run as a shell command line and its raw bytes (never
        // UTF-8 decoded) are streamed straight to the sink, preserving binary-safety.
        val command = operation.arguments.joinToString(" ")
        return runDdmlibShell(device, command, request.timeout) { data, offset, length ->
            sink.write(data.copyOfRange(offset, offset + length))
        }
    }

    private fun locateOnlineDevice(serial: DeviceSerial): DeviceLookup {
        // A serial the bridge does not know is a capability gap detected before any device call
        // (bridge not initialized yet, or attached to a different adb server): report it as
        // Unsupported so ADR 0005's fallback can safely retry on the binary transport.
        val device = deviceSource.devices().find { it.serialNumber == serial.toString() }
            ?: return DeviceLookup.Unavailable("ddmlib bridge does not know device '$serial'")
        return when (device.state) {
            IDevice.DeviceState.ONLINE -> DeviceLookup.Found(device)
            IDevice.DeviceState.OFFLINE -> DeviceLookup.Failure("Device '$serial' is offline")
            IDevice.DeviceState.UNAUTHORIZED -> DeviceLookup.Failure("Device '$serial' is unauthorized")
            IDevice.DeviceState.AUTHORIZING -> DeviceLookup.Failure("Device '$serial' is still authorizing")
            IDevice.DeviceState.DISCONNECTED -> DeviceLookup.Failure("Device '$serial' is disconnected")
            else -> DeviceLookup.Failure("Device '$serial' is not in a shell-capable state (${device.state})")
        }
    }

    /**
     * Runs [command] on [device], delivering raw output chunks to [onOutput] as ddmlib produces
     * them. Cancelling the calling coroutine (directly, or via [timeout] elapsing) flips the
     * receiver's `isCancelled()` to `true` and interrupts the blocking ddmlib call — real
     * cooperative cancellation, never the hardcoded-`false` receiver this adapter must not repeat.
     */
    private suspend fun runDdmlibShell(
        device: IDevice,
        command: String,
        timeout: Duration?,
        onOutput: (ByteArray, Int, Int) -> Unit,
    ): AdbOutcome {
        suspend fun execute() {
            val callJob = coroutineContext[Job]
            val receiver = object : IShellOutputReceiver {
                override fun addOutput(data: ByteArray, offset: Int, length: Int) = onOutput(data, offset, length)
                override fun flush() = Unit
                override fun isCancelled(): Boolean = callJob?.let { !it.isActive } ?: false
            }
            runInterruptible(ioDispatcher) {
                try {
                    device.executeShellCommand(command, receiver, NO_DDMLIB_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS, null)
                } catch (failure: Exception) {
                    // Interrupting the blocked call (our timeout or the caller's cancellation) makes
                    // ddmlib/adblib fail with an I/O error such as ClosedByInterruptException. That
                    // is the cancellation itself, not a transport failure: surface it as one so a
                    // timeout becomes TimedOut and a superseded call is simply cancelled.
                    if (callJob?.isActive == false) throw CancellationException("ddmlib shell call interrupted").apply { initCause(failure) }
                    throw failure
                }
            }
        }

        return try {
            if (timeout != null) {
                withTimeoutOrNull(timeout) { execute() } ?: return AdbOutcome.TimedOut
            } else {
                execute()
            }
            AdbOutcome.Completed(exitCode = null)
        } catch (e: TimeoutException) {
            AdbOutcome.TransportFailure(e.message ?: "ddmlib shell call timed out")
        } catch (e: AdbCommandRejectedException) {
            AdbOutcome.TransportFailure(e.message ?: "adb command rejected")
        } catch (e: ShellCommandUnresponsiveException) {
            AdbOutcome.TransportFailure("shell command unresponsive")
        } catch (e: IOException) {
            AdbOutcome.TransportFailure(e.message ?: "I/O error executing shell command")
        }
    }

    private fun unsupportedText(reason: String) = AdbTextResult(AdbOutcome.Unsupported(reason), "", "")

    private sealed interface DeviceLookup {
        data class Found(val device: IDevice) : DeviceLookup
        data class Failure(val reason: String) : DeviceLookup
        data class Unavailable(val reason: String) : DeviceLookup
    }
}
