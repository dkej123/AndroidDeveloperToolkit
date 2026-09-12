package dev.acme.adbtoolbox.adapters.jvm.network

/** Seam over `java.net.NetworkInterface` enumeration so [JvmHostNetworkInfo] is testable with
 * synthetic interfaces instead of depending on the test-runner machine's real, nondeterministic
 * network state. */
fun interface NetworkInterfaceLister {
    /** @throws Exception if the JVM cannot enumerate interfaces (e.g. `SocketException`). */
    fun list(): List<NetworkInterfaceSnapshot>
}
