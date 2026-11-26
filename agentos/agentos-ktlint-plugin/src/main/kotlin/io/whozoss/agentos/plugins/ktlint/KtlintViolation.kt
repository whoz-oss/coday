package io.whozoss.agentos.plugins.ktlint

/**
 * Most violations a report lists. A generated or badly formatted file can have thousands of them, which would flood
 * the context of the agent: beyond this, the report says how many were left out.
 */
internal const val MAX_REPORTED_VIOLATIONS = 200

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

/**
 * One line per violation, the first [MAX_REPORTED_VIOLATIONS] only, then a line telling how many were not shown and
 * how many there are in all. Empty when there is no violation.
 */
internal fun List<KtlintViolation>.describeCapped(): String {
    val shown = take(n = MAX_REPORTED_VIOLATIONS).map { it.describe() }
    val hiddenCount = size - shown.size
    return when {
        hiddenCount > 0 -> shown + "... $hiddenCount more violation(s) not shown ($size total)"
        else -> shown
    }.joinToString(separator = "\n")
}
