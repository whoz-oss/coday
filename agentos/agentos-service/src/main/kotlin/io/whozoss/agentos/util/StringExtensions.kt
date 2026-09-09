package io.whozoss.agentos.util

import java.text.Normalizer

/**
 * Converts a string to a URL-friendly slug:
 * - Normalizes diacritics (é → e, ñ → n, etc.)
 * - Lowercases
 * - Replaces any sequence of non-alphanumeric characters with a single hyphen
 * - Trims leading/trailing hyphens
 */
fun String.toSlug(): String =
    Normalizer.normalize(this, Normalizer.Form.NFD)
        .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
        .lowercase()
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')
