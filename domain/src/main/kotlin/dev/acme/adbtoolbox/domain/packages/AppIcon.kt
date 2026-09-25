package dev.acme.adbtoolbox.domain.packages

/**
 * An app's launcher icon as PNG bytes, rendered on the device by the app-info helper (ADR 0007).
 * Compares by content so an unchanged icon never makes an otherwise equal [PackageEntry] look new.
 */
class AppIcon(val png: ByteArray) {
    override fun equals(other: Any?): Boolean = other is AppIcon && png.contentEquals(other.png)

    override fun hashCode(): Int = png.contentHashCode()

    override fun toString(): String = "AppIcon(${png.size} bytes)"
}
