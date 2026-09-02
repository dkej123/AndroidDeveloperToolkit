package dev.acme.adbtoolbox.domain.discovery

/** A tool's self-reported version string, exactly as parsed from its `version`/`--version` output —
 * never reformatted or coerced into a stricter semver shape than the tool itself guarantees. */
@JvmInline
value class ToolVersion private constructor(val raw: String) {

    override fun toString(): String = raw

    companion object {
        fun of(raw: String): ToolVersion {
            require(raw.isNotBlank()) { "Tool version must not be blank" }
            return ToolVersion(raw)
        }
    }
}
