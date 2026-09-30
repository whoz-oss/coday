package io.whozoss.agentos.sdk.util

/**
 * Shared security utility for detecting sensitive file names (secrets, credentials, private keys).
 *
 * Used across plugins, file tools, and skill resource decoders.
 */
object SensitiveFileDetector {

    val DEFAULT_SENSITIVE_PATTERNS: List<String> = listOf(
        ".env",
        ".env.*",
        "credentials.json",
        "*.key",
        "*.pem",
        "token.json",
        "auth-profiles.json",
        "*.p12",
        "*.pfx",
        "id_rsa",
        "id_dsa",
        "id_ecdsa",
        "id_ed25519",
    )

    fun isSensitive(
        fileName: String,
        patterns: List<String> = DEFAULT_SENSITIVE_PATTERNS,
    ): Boolean = patterns.any { pattern -> matchesGlob(fileName, pattern) }

    fun matchesGlob(
        name: String,
        pattern: String,
    ): Boolean {
        val regex = Regex(
            pattern.split("*").joinToString(".*") { Regex.escape(it) },
            RegexOption.IGNORE_CASE,
        )
        return regex.matches(name)
    }
}
