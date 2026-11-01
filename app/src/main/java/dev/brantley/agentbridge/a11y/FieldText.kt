package dev.brantley.agentbridge.a11y

/**
 * Text already in a field before a type action.
 * A hint or placeholder (for example "RCS message") is not content. Typing without
 * `clear` must not append to it. Password refusal happens before this runs.
 */
internal fun existingFieldText(
    clear: Boolean,
    shown: String?,
    hint: String?,
    showingHint: Boolean,
): String {
    if (clear || showingHint) return ""
    val current = shown.orEmpty()
    if (!hint.isNullOrEmpty() && current == hint) return ""
    return current
}
