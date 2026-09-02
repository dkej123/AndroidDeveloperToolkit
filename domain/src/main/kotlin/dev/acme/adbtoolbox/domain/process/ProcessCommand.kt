package dev.acme.adbtoolbox.domain.process

/**
 * A structured executable invocation: one executable path plus its arguments, never a shell
 * string. Adapters must invoke [executable] directly with [arguments] as a literal argument
 * vector — never concatenate these into a shell command line.
 */
data class ProcessCommand(
    val executable: String,
    val arguments: List<String> = emptyList(),
    val workingDirectory: String? = null,
    val environment: Map<String, String> = emptyMap(),
)
