package dev.acme.adbtoolbox.application.shell

/** One-shot events [ShellViewModel] emits alongside state — never folded into [ShellViewState]. */
sealed interface ShellEffect {
    data class ShowMessage(val text: String) : ShellEffect
}
