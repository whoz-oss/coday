package io.whozoss.agentos.util

class IdCompressor {
    private val originalToCompressed = mutableMapOf<String, String>()
    private val compressedToOriginal = mutableMapOf<String, String>()
    private var globalOffset = 0

    fun compress(text: String): String {
        if (text.isBlank()) return text
        var compressedText = text
        val matches = (UUID_REGEX.findAll(text) + OBJECTID_REGEX.findAll(text)).sortedBy { it.range.first }
        
        for (match in matches) {
            val original = match.value
            if (!originalToCompressed.containsKey(original)) {
                val prefix = if (original.length == 36) "UI" else "OI"
                val absoluteOffset = globalOffset + match.range.first
                val compressed = "$prefix${absoluteOffset.toString(36)}"
                originalToCompressed[original] = compressed
                compressedToOriginal[compressed] = original
            }
        }

        originalToCompressed.forEach { (original, compressed) ->
            compressedText = compressedText.replace(original, compressed)
        }

        globalOffset += text.length
        return compressedText
    }

    fun uncompress(text: String): String {
        var result = text
        compressedToOriginal.forEach { (compressed, original) ->
            result = result.replace(compressed, original)
        }
        return result
    }

    /**
     * Stateful helper for decompressing a stream of text chunks where compressed
     * tokens may be split across chunk boundaries.
     *
     * The key insight: compressed tokens always have the form `UI<base36>` or
     * `OI<base36>`. Rather than holding back a fixed window, we scan the tail of
     * the carry for the longest suffix that is a **prefix of any known compressed
     * token**. Only that suffix is held back; everything before it is safe to emit.
     *
     * This correctly handles tokens that arrive one character at a time:
     * - carry = "UI" → suffix "UI" is a prefix of e.g. "UI2fj" → hold all of it
     * - carry = "UI2" → suffix "UI2" is a prefix → hold all of it
     * - carry = "UI2fj" → exact match → will be decompressed in the next feed or flush
     * - carry = "hello UI2" → safe prefix "hello " emitted, "UI2" held
     *
     * Usage:
     * 1. Call [IdCompressor.compress] on all input messages to populate the map.
     * 2. Instantiate [StreamingDecompressor] (snapshot of tokens taken at this point).
     * 3. For each incoming chunk call [feed] and emit the returned string.
     * 4. After the stream ends call [flush] and emit the returned string.
     */
    inner class StreamingDecompressor {
        // Snapshot of all known compressed tokens, taken after compress() populated the map.
        private val tokens: Set<String> = compressedToOriginal.keys.toSet()
        private val carry = StringBuilder()

        /**
         * Appends [chunk] to the carry buffer, then finds the longest suffix of the
         * carry that is a prefix of any known token. Everything before that suffix is
         * safe to emit (decompressed). The suffix stays in the carry.
         *
         * If no suffix matches any token prefix, the entire carry is safe to emit.
         */
        fun feed(chunk: String): String {
            carry.append(chunk)
            val safeEnd = findSafeEnd(carry.toString())
            if (safeEnd == 0) return ""
            val toEmit = carry.substring(0, safeEnd)
            carry.delete(0, safeEnd)
            return uncompress(toEmit)
        }

        /**
         * Decompresses and returns whatever remains in the carry buffer.
         * Must be called exactly once after the stream ends.
         */
        fun flush(): String {
            val remaining = carry.toString()
            carry.clear()
            return uncompress(remaining)
        }

        /**
         * Returns the index up to which the [text] is safe to emit — i.e. the
         * position where the longest suffix that could still grow into a known
         * compressed token starts.
         *
         * A suffix must be held back only when it is a **strict prefix** of a known
         * token (i.e. the token starts with the suffix but the suffix is shorter
         * than the token). An exact complete-token match is NOT held back — it will
         * be decompressed by [uncompress] in the emitted portion.
         *
         * Scans suffixes from longest (position 0) to shortest (last char). The
         * first suffix that is a strict prefix of any known token determines the
         * hold-back point. If no suffix qualifies, the entire string is safe.
         */
        private fun findSafeEnd(text: String): Int {
            if (tokens.isEmpty()) return text.length
            for (suffixStart in 0 until text.length) {
                val suffix = text.substring(suffixStart)
                // Hold back only if this suffix is a STRICT prefix of a known token
                // (i.e. the token starts with the suffix AND is longer than it).
                // Exact matches are safe — uncompress() will handle them.
                if (tokens.any { it.startsWith(suffix) && it.length > suffix.length }) {
                    return suffixStart
                }
            }
            return text.length
        }
    }

    companion object {
        private val UUID_REGEX = Regex("(?i)\\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\b")
        private val OBJECTID_REGEX = Regex("(?i)\\b[0-9a-f]{24}\\b")
    }
}
