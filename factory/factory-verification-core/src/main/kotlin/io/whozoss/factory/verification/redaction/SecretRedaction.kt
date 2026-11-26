package io.whozoss.factory.verification.redaction

/**
 * The single secret-redaction rule set of Factory diagnostics (code-verification output, session failures,
 * CI logs). Pure and deterministic: no framework, no I/O.
 *
 * Two families are applied, in this order:
 *  - multi-line private key blocks and bare, self-describing tokens (GitHub, JWT, AWS, Slack), replaced by
 *    `<redacted>` wherever they appear;
 *  - credential assignments (`NAME_TOKEN=value`, `Authorization: ...`, `Bearer ...`), whose name is kept:
 *    `NAME_TOKEN=<redacted>`.
 */
object SecretRedaction {

    private val BARE_TOKENS = listOf(
        Regex("-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?-----END [A-Z ]*PRIVATE KEY-----"),
        Regex("gh[pousr]_[A-Za-z0-9]{36,}"),
        Regex("github_pat_[A-Za-z0-9_]{20,}"),
        Regex("eyJ[\\w-]+\\.eyJ[\\w-]+\\.[\\w-]+"),
        Regex("AKIA[0-9A-Z]{16}"),
        Regex("xox[baprs]-[A-Za-z0-9-]{10,}"),
    )

    private val ASSIGNMENTS = listOf(
        Regex("(?i)\\b([A-Z0-9_]*(?:TOKEN|SECRET|PASSWORD|API_KEY|PRIVATE_KEY))\\s*=\\s*[^\\s]+"),
        Regex("(?i)\\b(authorization)\\s*:\\s*[^\\r\\n]+"),
        Regex("(?i)\\b(bearer)\\s+[^\\s]+"),
    )

    const val PLACEHOLDER = "<redacted>"

    /** [value] with every known secret shape replaced. Never throws. */
    fun redact(value: String): String {
        var result = value
        BARE_TOKENS.forEach { pattern -> result = pattern.replace(result, PLACEHOLDER) }
        ASSIGNMENTS.forEach { pattern -> result = pattern.replace(result, "$1=$PLACEHOLDER") }
        return result
    }
}
