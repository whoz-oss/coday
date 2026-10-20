package io.whozoss.agentos.exchange

/**
 * The prose that tells an agent which of its two file surfaces to write in.
 *
 * An agent granted both exchange scopes receives two sets of file tools whose names differ only by
 * their prefix ([ExchangeIntegrationTypes.CASE_CONFIG_NAME] /
 * [ExchangeIntegrationTypes.NAMESPACE_CONFIG_NAME]) and whose descriptions, produced by the same
 * file-plugin tool classes, are identical. Nothing else in the prompt says what a case or a
 * namespace is, so the only thing arbitrating between the two is the model's bias towards the first
 * matching tool of its list — which is how files shared by a whole namespace get rewritten by a run
 * that only meant to produce its own.
 *
 * Two carriers, on purpose:
 * - [buildBlock] is the one that does the work. It goes into the agent's instructions, which the
 *   advanced intention prompt explicitly orders the model to reason from (`<instructions>`), and
 *   being prose rather than a tool field it can afford to say what a case and a namespace are.
 * - [CASE_TOOL_GUIDANCE] / [NAMESPACE_TOOL_GUIDANCE] restate the decision on the write tools
 *   themselves, where the choice is actually made. They are deliberately short: providers cap a
 *   tool description (OpenAI rejects one past 1024 characters) and the plugin's `readSpreadsheet`,
 *   `readDocument` and `readAsImage` descriptions are already close enough to that ceiling that
 *   only the write tools carry them. [ExchangeToolGrantService] hands them to the plugin, which
 *   appends them to `editFiles`, `moveFile` and `remove` only.
 *
 * Every string here is a constant. Tool definitions sit in the provider's cached prompt prefix (see
 * `agentos.anthropic.prompt-caching-enabled`), so a text that varied per run — a case id, a
 * resolved root path — would miss that cache on every turn of every run.
 */
object ExchangeScopeGuidance {
    /**
     * What the invoking user may do in a scope that was granted. A scope nobody was granted has no
     * stance at all, which is what the null arguments of [buildBlock] mean.
     *
     * Both halves have to hold for a scope to be named as writable: the invoking user's rights,
     * through the per-run `readOnly` decision, and the agent's own per-tool allowlist, through
     * [ExchangeToolGrantService.grantsMutatingTool]. A scope the agent restricted to read tools is
     * therefore described as read-only, because naming it as the write target would send the model
     * to a tool it was not granted, and from there to the other scope's `editFiles`.
     */
    enum class Access {
        READ_ONLY,
        READ_WRITE,
        ;

        companion object {
            /** The stance of a granted scope whose per-run `readOnly` decision came out as [canWrite]. */
            fun forWrite(canWrite: Boolean): Access =
                when (canWrite) {
                    true -> READ_WRITE
                    false -> READ_ONLY
                }
        }
    }

    /** Appended to the case-scope write tools. See the class note on length. */
    const val CASE_TOOL_GUIDANCE: String =
        "Scope: the files of the case being run, your own working surface."

    /** Appended to the namespace-scope write tools. See the class note on length. */
    const val NAMESPACE_TOOL_GUIDANCE: String =
        "Scope: the files shared by every case of the namespace. Write here only for a change the " +
            "user asked to make to those shared files; otherwise work in the case scope."

    /**
     * The `## File scopes` block for a run that was granted [case] and [namespace], or null when
     * there is nothing to arbitrate.
     *
     * Returns null as soon as one scope is missing: an agent with a single file surface cannot
     * choose the wrong one, so it pays neither the tokens nor the risk of being told about a scope
     * it does not have. That is what keeps this change inert for every single-scope agent, the
     * namespace scope being off by default
     * ([ExchangeToolsConfigProperties.namespaceEnabledByDefault]) and additionally gated on the
     * invoking user's Namespace READ.
     */
    fun buildBlock(
        case: Access?,
        namespace: Access?,
    ): String? =
        when {
            case == null || namespace == null -> null
            else ->
                buildString {
                    appendLine()
                    appendLine("## File scopes")
                    appendLine()
                    appendLine(
                        "Your file tools reach two different places, and the prefix of a tool name says which one.",
                    )
                    appendLine()
                    appendLine(
                        "- `${ExchangeIntegrationTypes.CASE_CONFIG_NAME}__` — the files of the case you are " +
                            "working on. This is your working surface: what you produce for the task at hand " +
                            "belongs here.",
                    )
                    appendLine(
                        "- `${ExchangeIntegrationTypes.NAMESPACE_CONFIG_NAME}__` — the files shared by every " +
                            "case of the namespace: common procedures, templates, reference documents. They are " +
                            "visible to every user and every agent of the namespace, from every other case.",
                    )
                    appendLine()
                    append(caseStance(case))
                    append(" ")
                    append(namespaceStance(namespace))
                    append(" Follow your own instructions where they say otherwise.")
                }.trimEnd()
        }

    /**
     * Written as the default target rather than as a prohibition on the namespace scope: a tool
     * description that forbids its own use makes a model decline a request it should serve, or
     * quietly do the work in the other scope and report success — the same bug the other way round,
     * and harder to notice.
     */
    private fun caseStance(case: Access): String =
        when (case) {
            Access.READ_WRITE ->
                "Work in the case scope by default, for reading as much as for writing: when the same file exists " +
                    "in both, the case copy is the one your task is about."
            Access.READ_ONLY ->
                "The case scope is read-only for you on this run: report what you cannot write there rather " +
                    "than falling back to the namespace scope."
        }

    private fun namespaceStance(namespace: Access): String =
        when (namespace) {
            Access.READ_WRITE ->
                "Reach for the namespace scope only when the user asks you to change those shared files, or to " +
                    "record something that has to outlive this case; reading it for shared reference is fine."
            Access.READ_ONLY ->
                "The namespace scope is read-only for you: read it for shared reference, nothing more."
        }
}
