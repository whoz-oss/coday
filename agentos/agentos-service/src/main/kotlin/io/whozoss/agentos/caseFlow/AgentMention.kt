package io.whozoss.agentos.caseFlow

/**
 * Parsing of the leading `@agentName` mention that routes a user message to an agent.
 *
 * [CaseServiceImpl] is the only production caller: it uses [extractName] to select the agent
 * ([CaseServiceImpl.selectAgent]) and to decide whether a prompt linked to an agent still needs
 * its `@agent` prefix, so both decisions agree on what a mention is. The message text, mention
 * included, reaches the agent unchanged. [strip] is not used by the service today; it is kept
 * next to [extractName] because the two share one grammar and must stay in agreement.
 *
 * ## Accepted forms (leading only, after `trim()`)
 *
 * - **Plain**: `@AgentName rest of message`
 * - **Chip** (markdown link inserted by the composer autocomplete):
 *   `[@Agent Name](#agent=<uuid>) rest of message`
 *   The link target `(...)` is optional and its content is **not parsed**: routing goes through
 *   the name only, resolved by [io.whozoss.agentos.agent.AgentService.resolveAgentName].
 *
 * ## Agent-name character class
 *
 * Agent names are not constrained (they may hold spaces, dots or non-ASCII letters), so each
 * form delimits the name its own way:
 *
 * - **Plain** uses `[\w-]+`. `\S+` was deliberately rejected: a message like
 *   `@inspector\u00A0https://...`, where the separator is a non-breaking space (U+00A0) or any
 *   other non-ASCII whitespace, would make `\S+` capture `inspector\u00A0https://...` as the
 *   name, because `\S` in Java/Kotlin regex only excludes ASCII whitespace. `[\w-]+` stops at
 *   the first space-like or special character.
 * - **Chip** takes everything up to the closing `]`. That bracket is an explicit delimiter, so
 *   the non-breaking-space problem cannot arise, and a name the composer emits as the chip label
 *   is never cut short. The name must start with a non-whitespace character and stays on one line.
 */
object AgentMention {
    /**
     * Matches a leading mention in either the plain or chip form.
     *
     * - group 1: the name of the chip form `[@Name](...)` or `[@Name]`
     * - group 2: the name of the plain form `@Name`
     *
     * The regex is anchored at the start of the trimmed string (`^`) and spans the whole
     * construct so that [strip] leaves no `](#agent=...)` debris behind.
     *
     * The link-target part `(?:\([^)]*\))?` is optional so `[@Name]` (no target) is also accepted.
     * It ends at the first `)`: a target holding one (`[@Name](#a)b) rest`) leaves `b) rest`
     * behind.
     *
     * The trailing `(?!\()` guard rejects a chip whose link target is unclosed or duplicated
     * (`[@Name](#x`, `[@Name](#a)(#b)`): without it the optional group would simply not
     * participate, the match would succeed on `[@Name]` alone, and [strip] would leave the
     * dangling `(#x` in the message body. Rejecting keeps [extractName] and [strip] in agreement:
     * whatever one recognises, the other removes entirely.
     */
    private val LEADING_MENTION_REGEX =
        """^(?:\[@([^\]\s][^\]\r\n]*)\](?:\([^)]*\))?(?!\()|@([\w-]+))""".toRegex()

    /**
     * Returns the agent name mentioned at the start of [text], or `null` when there is none.
     *
     * Accepts both the plain form (`@Name`) and the chip form (`[@Name](#agent=...)`).
     * Leading whitespace is ignored (the text is trimmed before matching).
     */
    fun extractName(text: String): String? {
        val match = LEADING_MENTION_REGEX.find(text.trim()) ?: return null
        // Group 1 is set by the chip branch `[@Name](...)`, group 2 by the plain branch `@Name`.
        return (match.groups[1] ?: match.groups[2])?.value?.trim()
    }

    /**
     * Returns [text] without its leading mention, trimmed.
     * Returns the trimmed text unchanged when there is no leading mention.
     *
     * Works for both the plain form (`@Name rest`) and the chip form
     * (`[@Name](#agent=...) rest`). The whole chip construct, including `](#agent=...)`,
     * is stripped, leaving no debris.
     */
    fun strip(text: String): String = text.trim().replaceFirst(LEADING_MENTION_REGEX, "").trim()
}
