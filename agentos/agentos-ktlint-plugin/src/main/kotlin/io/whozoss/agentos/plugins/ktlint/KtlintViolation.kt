package io.whozoss.agentos.plugins.ktlint

/**
 * A ktlint finding, detached from the ktlint API so the rest of the module (and the agent-facing report)
 * does not depend on engine types.
 *
 * @property file path of the file, relative to the project root.
 * @property line 1-based line of the violation.
 * @property column 1-based column of the violation.
 * @property ruleId id of the violated rule, e.g. `standard:no-wildcard-imports`.
 * @property detail human-readable message of the rule.
 * @property autoCorrectable true when `ktlint_format` can fix it.
 */
data class KtlintViolation(
    val file: String,
    val line: Int,
    val column: Int,
    val ruleId: String,
    val detail: String,
    val autoCorrectable: Boolean,
) {
    /** One report line: `file:line:column message (rule-id)`, flagged `[auto-correctable]` when it can be fixed. */
    fun describe(): String = "$file:$line:$column $detail ($ruleId)" + if (autoCorrectable) " [auto-correctable]" else ""
}
