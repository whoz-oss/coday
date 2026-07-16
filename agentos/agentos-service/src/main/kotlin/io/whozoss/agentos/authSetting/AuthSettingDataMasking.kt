package io.whozoss.agentos.authSetting

private const val MASK = "****"

/**
 * Mask a single sensitive value for safe inclusion in API responses.
 *
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
 * Selectively mask values in [data] for safe inclusion in API responses.
 *
 * Only keys listed in [sensitiveKeys] are masked. All other keys are returned
 * in plain text, allowing non-sensitive properties (e.g. `clientId`, `discoveryUrl`,
 * `username`) to be visible to the caller.
 *
 * Returns `null` when [data] is empty (nothing to surface).
 */
fun maskDataMapSelective(
    data: Map<String, String>,
    sensitiveKeys: Set<String>,
): Map<String, String>? {
    if (data.isEmpty()) return null
    return data.mapValues { (key, value) ->
        if (key in sensitiveKeys) maskSensitiveValue(value) else value
    }
}

/**
 * Return `true` when [value] is a masked placeholder (contains the sentinel `"****"`).
 *
 * Used by [AuthSettingController.update] to detect that the client sent back a masked
 * value unchanged and that the original persisted value should be preserved.
 */
fun isDataValueMasked(value: String?): Boolean = value != null && MASK in value
