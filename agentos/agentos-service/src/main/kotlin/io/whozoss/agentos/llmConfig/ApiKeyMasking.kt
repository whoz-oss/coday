package io.whozoss.agentos.llmConfig

private const val MASK = "****"

/**
 * Mask an API key for safe inclusion in API responses.
 *
 * Mirrors the behaviour of the frontend `ConfigMaskingService.maskValue()`:
 * - null / blank  → null
 * - length ≤ 8    → "****"
 * - length 9–11   → first 2 + "****" + last 2
 * - length ≥ 12   → first 4 + "****" + last 4
 */
fun maskApiKey(apiKey: String?): String? {
    if (apiKey.isNullOrBlank()) return null
    return when {
        apiKey.length <= 8 -> MASK
        apiKey.length < 12 -> "${apiKey.take(2)}$MASK${apiKey.takeLast(2)}"
        else -> "${apiKey.take(4)}$MASK${apiKey.takeLast(4)}"
    }
}

/**
 * Return true when [value] is a masked placeholder (contains the sentinel "****").
 *
 * Used by [LlmConfigController.update] to detect that the client sent back a masked
 * value unchanged and that the original persisted key should be preserved.
 */
fun isMasked(value: String?): Boolean = value != null && MASK in value
