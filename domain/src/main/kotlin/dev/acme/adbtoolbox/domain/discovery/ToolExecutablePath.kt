package dev.acme.adbtoolbox.domain.discovery

/** A resolved, validated executable path for a [ToolId] — validation (existence, executable bit)
 * happened in an adapter before this value is constructed; `:domain` only carries the result. */
@JvmInline
value class ToolExecutablePath private constructor(val value: String) {

    override fun toString(): String = value

    companion object {
        fun of(value: String): ToolExecutablePath {
            require(value.isNotBlank()) { "Tool executable path must not be blank" }
            return ToolExecutablePath(value)
        }
    }
}
