package dev.acme.adbtoolbox.domain.packages

/**
 * Which packages a discovery request should target (`design/README.md`'s Apps view "Show system
 * packages" toggle). [User] maps to `pm list packages -3` (third-party/user-installed packages
 * only). [All] maps to plain `pm list packages` (no filtering flag), which already returns every
 * known package — user and system — so toggling system packages on requires no second call or
 * client-side union.
 */
enum class PackageListScope {
    User,
    All,
}
