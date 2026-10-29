package io.whozoss.agentos.caseFlow

/**
 * Parsing of the leading `@agentName` mention that routes a user message to an agent.
 *
 * This object is the **single source of truth** for mention grammar. No caller may
 * re-implement a `startsWith("@")` check or its own `^@` regex — all mention
 * detection must go through [extractName] or [strip].
 *
 * Shared by agent selection ([CaseServiceImpl]) and by agents that parse the raw message
 * text themselves (e.g. [io.whozoss.agentos.agent.AgentLoop]), so both sides always agree
 * on what a mention is.
 *
 * ## Accepted forms (leading only, after `trim()`)
 *
 * - **Plain**: `@AgentName rest of message`
 * - **Chip** (markdown link inserted by the composer autocomplete):
 *   `[@AgentName](#agent=<uuid>) rest of message`
 *   The link target `(...)` is **optional** and its content is **not parsed**.
 *
 * ## Agent-name character class
 *
 * Both forms use `[\w-]+` (word characters and hyphens). `\S+` was deliberately
 * rejected: a message like `@inspector\u00A0https://...` — where the separator is a
 * non-breaking space (U+00A0) or any other non-ASCII whitespace — would cause `\S+`
 * to capture `inspector\u00A0https://...` as the agent name, because `\S` in
 * Java/Kotlin regex only excludes ASCII whitespace. The tighter class `[\w-]+` stops
 * at the first space-like or special character, ensuring only the name token is captured.
 */
object AgentMention {
    /**
     * Matches a leading mention in either the plain or chip form.
     *
     * Plain:  `@Name`                         → group 1 = `Name`
     * Chip:   `[@Name](…)` or `[@Name]`       → group 1 = `Name`
     *
     * The regex is anchored at the start of the trimmed string (`^`) and spans the
     * whole construct so that [strip] leaves no `](#agent=…)` debris behind.
     *
     * Group layout:
     *   group 1 — agent name (shared by both branches via `(?:…|…)` alternation)
     *
     * The link-target part `(?:\([^)]*\))?` is optional so `[@Name]` (no target) is
     * also accepted.
     *
     * The trailing `(?!\()` guard rejects a chip whose link target is unclosed or
     * duplicated (`[@Name](#x`, `[@Name](#a)(#b)`): without it the optional group would
     * simply not participate, the match would succeed on `[@Name]` alone, and [strip]
     * would leave the dangling `(#x` in the message body. Rejecting keeps [extractName]
     * and [strip] in agreement: whatever one recognises, the other removes entirely.
     */
    private val LEADING_MENTION_REGEX =
        """^(?:\[@([\w-]+)\](?:\([^)]*\))?(?!\()|@([\w-]+))""".toRegex()

    /**
     * Returns the agent name mentioned at the start of [text], or `null` when there is none.
     *
     * Accepts both the plain form (`@Name`) and the chip form (`[@Name](#agent=…)`).
     * Leading whitespace is ignored (the text is trimmed before matching).
     */
    fun extractName(text: String): String? {
        val match = LEADING_MENTION_REGEX.find(text.trim()) ?: return null
        // Group 1 is set by the chip branch `[@Name](…)`, group 2 by the plain branch `@Name`.
        return match.groupValues[1].ifEmpty { null } ?: match.groupValues[2].ifEmpty { null }
    }

    /**
     * Returns [text] without its leading mention, trimmed.
     * Returns the trimmed text unchanged when there is no leading mention.
     *
     * Works for both the plain form (`@Name rest`) and the chip form
     * (`[@Name](#agent=…) rest`). The whole chip construct — including `](#agent=…)` —
     * is stripped, leaving no debris.
     */
    fun strip(text: String): String = text.trim().replaceFirst(LEADING_MENTION_REGEX, "").trim()
}
