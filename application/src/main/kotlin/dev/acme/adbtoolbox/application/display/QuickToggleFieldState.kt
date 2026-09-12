package dev.acme.adbtoolbox.application.display

/**
 * One quick-toggle field's presentation state (task 028: "expose Loading/Applying/Error states").
 * [Idle] is always derived from a device readback, never assumed from the write that triggered it
 * — see [dev.acme.adbtoolbox.application.display.QuickTogglesViewModel]'s write-then-readback
 * flow. [Applying]/[Error] both keep the last value the caller actually knows ([Applying.optimistic]
 * is the value being written, shown while waiting for readback truth; [Error.lastKnown] is the last
 * confirmed readback, if any) so the UI never has to guess a value out of thin air.
 */
sealed interface QuickToggleFieldState<out T> {
    data object Loading : QuickToggleFieldState<Nothing>

    data class Idle<out T>(val value: T) : QuickToggleFieldState<T>

    data class Applying<out T>(val optimistic: T) : QuickToggleFieldState<T>

    data class Error<out T>(val message: String, val lastKnown: T?) : QuickToggleFieldState<T>
}

/** The best value currently known for this field, or `null` when nothing has ever been confirmed. */
fun <T> QuickToggleFieldState<T>.valueOrNull(): T? = when (this) {
    is QuickToggleFieldState.Idle -> value
    is QuickToggleFieldState.Applying -> optimistic
    is QuickToggleFieldState.Error -> lastKnown
    QuickToggleFieldState.Loading -> null
}
