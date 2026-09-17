package dev.acme.adbtoolbox.domain.devicecontext

/**
 * One feature's remembered, device-truth override value for a serial that has since gone
 * offline/disconnected (task 041's "remember applied overrides per serial and offer to re-apply
 * when that serial returns" — `design/README.md` Interactions & behaviour). [featureId] matches
 * [OverrideResetUseCase.featureId]; [value] is the same opaque token
 * [OverrideResetUseCase.currentValue] produced when this was captured — never a raw command, and
 * never applied to a device without going back through that feature's own [OverrideResetUseCase].
 */
data class PendingReapplyOverride(val featureId: String, val value: String)
