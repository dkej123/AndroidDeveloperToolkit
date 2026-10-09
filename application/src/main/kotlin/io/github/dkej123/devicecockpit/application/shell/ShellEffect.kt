package io.github.dkej123.devicecockpit.application.shell

/** One-shot events [ShellViewModel] emits alongside state — never folded into [ShellViewState]. */
sealed interface ShellEffect {
    data class ShowMessage(val text: String) : ShellEffect
}
