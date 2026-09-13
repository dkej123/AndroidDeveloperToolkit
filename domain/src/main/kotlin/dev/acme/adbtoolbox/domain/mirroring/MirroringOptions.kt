package dev.acme.adbtoolbox.domain.mirroring

import dev.acme.adbtoolbox.domain.adb.DeviceSerial

/**
 * The minimal, functional mirroring option set needed to launch a scrcpy session — the semantics
 * design/IMPLEMENTATION.md §4 lists (`--stay-awake --show-touches --max-size ... --video-bit-rate
 * ...`). This is deliberately not the full options model: task 040 owns validation UX, persistence,
 * and any additional option surface: `MirroringOptions` here only needs to be enough to build a
 * correct `scrcpy` argument vector for this task's session lifecycle.
 */
data class MirroringOptions(
    val stayAwake: Boolean = false,
    val showTouches: Boolean = false,
    val maxSize: Int? = null,
    val videoBitRateMbps: Int? = null,
) {
    init {
        require(maxSize == null || maxSize > 0) { "maxSize must be positive, was $maxSize" }
        require(videoBitRateMbps == null || videoBitRateMbps > 0) {
            "videoBitRateMbps must be positive, was $videoBitRateMbps"
        }
    }

    companion object {
        /** Task 040's sane UI/persistence bounds: scrcpy itself only rejects non-positive values
         * (enforced above), but a much larger typo'd value is still worth catching before it ever
         * reaches a real `scrcpy` invocation or a persisted file. */
        const val MIN_MAX_SIZE_PX: Int = 1
        const val MAX_MAX_SIZE_PX: Int = 7680

        const val MIN_VIDEO_BIT_RATE_MBPS: Int = 1
        const val MAX_VIDEO_BIT_RATE_MBPS: Int = 999

        val DEFAULT: MirroringOptions = MirroringOptions()
    }
}

/**
 * Builds the structured `scrcpy` argument vector for [serial]/[options] — never a shell string.
 * Matches design/IMPLEMENTATION.md §4's `scrcpy -s $S [--stay-awake --show-touches --max-size 1920
 * --video-bit-rate 8M]` exactly, including flag order.
 */
fun buildScrcpyArguments(serial: DeviceSerial, options: MirroringOptions): List<String> = buildList {
    add("-s")
    add(serial.value)
    if (options.stayAwake) add("--stay-awake")
    if (options.showTouches) add("--show-touches")
    options.maxSize?.let {
        add("--max-size")
        add(it.toString())
    }
    options.videoBitRateMbps?.let {
        add("--video-bit-rate")
        add("${it}M")
    }
}
