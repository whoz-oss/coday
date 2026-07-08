package io.whozoss.agentos.authSetting

private const val MASK = "****"

/**
 * Mask a single sensitive value for safe inclusion in API responses.
 *
 * Mirrors the behaviour of [io.whozoss.agentos.aiProvider.maskApiKey], applied to each
 * value in an [AuthSetting.data] map:
 * - null / blank  → "****"  (always returns a non-null string; the map entry is kept)
 * - length ≤ 8    → "****"
 * - length 9–11   → first 2 + "****" + last 2
 * - length ≥ 12   → first 4 + "****" + last 4
 *
 * Unlike [maskApiKey], null/blank returns `"****"` rather than `null`, because map
 * values must remain present in the response so the client knows the key exists.
 */
fun maskSensitiveValue(value: String?): String {
    if (value.isNullOrBlank()) return MASK
    return when {
        value.length <= 8 -> MASK
        value.length < 12 -> "${value.take(2)}$MASK${value.takeLast(2)}"
        else -> "${value.take(4)}$MASK${value.takeLast(4)}"
    }
}

/**
 * Mask every value in [data] for safe inclusion in API responses.
 *
 * Returns `null` when [data] is null or empty (no keys to surface).
 */
fun maskDataMap(data: Map<String, String>?): Map<String, String>? {
    if (data.isNullOrEmpty()) return null
    return data.mapValues { (_, v) -> maskSensitiveValue(v) }
}

/**
 * Return `true` when [value] is a masked placeholder (contains the sentinel `"****"`).
 *
 * Used by [AuthSettingController.update] to detect that the client sent back a masked
 * value unchanged and that the original persisted value should be preserved.
 */
fun isDataValueMasked(value: String?): Boolean = value != null && MASK in value
