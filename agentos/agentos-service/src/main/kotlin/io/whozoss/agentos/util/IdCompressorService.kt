package io.whozoss.agentos.util

import org.springframework.stereotype.Service

/**
 * Service that compresses and decompresses UUID and MongoDB ObjectId tokens in LLM message
 * histories, reducing token usage and preventing IDs from fragmenting across subword tokens.
 *
 * ## Why this exists
 *
 * UUIDs (`550e8400-e29b-41d4-a716-446655440000`) and ObjectIds (`507f1f77bcf86cd799439011`)
 * are long, opaque strings that inflate the token count of every message sent to an LLM.
 * More importantly, their hyphen-separated structure causes most tokenizers to split them into
 * many small subword tokens, making it harder for the model to treat them as atomic references.
 *
 * ## Compression strategy
 *
 * Each unique ID encountered in a text is replaced by a short alias:
 * - UUID  (36 chars, e.g. `550e8400-…`) → `UI<base36-offset>`, e.g. `UI0`
 * - ObjectId (24 hex chars)             → `OI<base36-offset>`, e.g. `OI1k`
 *
 * The `<base36-offset>` is the character position of the ID's first occurrence across all
 * `compress` calls on the same service instance. This guarantees that aliases are unique
 * across an entire conversation history even when the same text is compressed in chunks.
 *
 * The bidirectional maps are accumulated across multiple `compress` calls, so a single
 * service instance can be used to compress a whole conversation (message by message) and
 * then decompress any LLM response that echoes those aliases.
 *
 * ## Typical usage
 *
 * ```kotlin
 * val service = idCompressorService   // injected Spring bean
 * val buffer  = service.newBuffer()   // per-call state
 *
 * // 1. Compress all input messages (populates the alias map).
 * val compressedMessages = messages.map { service.compress(it, buffer) }
 *
 * // 2. Stream the LLM response.
 * llmStream.collect { chunk ->
 *     val decompressed = service.feed(chunk, buffer)
 *     emit(decompressed)
 * }
 * val tail = service.flush(buffer)
 * if (tail.isNotEmpty()) emit(tail)
 * ```
 *
 * ## Thread safety
 *
 * A [MessageCompressorBuffer] is **not** thread-safe and must not be shared across
 * concurrent coroutines. Create one buffer per LLM call.
 */
@Service
class IdCompressorService {

    companion object {
        /** Prefix used for compressed UUID aliases (e.g. `UI0`, `UI1k`). */
        const val UUID_COMPRESSED_VALUE_PREFIX = "UI"

        /** Prefix used for compressed MongoDB ObjectId aliases (e.g. `OI0`, `OI1k`). */
        const val OBJECTID_COMPRESSED_VALUE_PREFIX = "OI"

        private val UUID_REGEX = Regex("(?i)\\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\b")
        private val OBJECTID_REGEX = Regex("(?i)\\b[0-9a-f]{24}\\b")
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /** Creates a fresh [MessageCompressorBuffer] for one LLM call. */
    fun newBuffer(): MessageCompressorBuffer = MessageCompressorBuffer()

    /**
     * Scans [text] for UUIDs and ObjectIds, assigns short aliases for any new IDs,
     * and returns the text with all known IDs replaced by their aliases.
     *
     * The [buffer]'s alias maps are updated in place so that subsequent calls on
     * the same buffer build on the same mapping.
     */
    fun compress(text: String, buffer: MessageCompressorBuffer): String {
        if (text.isBlank()) return text
        var compressedText = text
        val matches = (UUID_REGEX.findAll(text) + OBJECTID_REGEX.findAll(text)).sortedBy { it.range.first }

        for (match in matches) {
            val original = match.value
            if (!buffer.originalToCompressed.containsKey(original)) {
                val prefix = if (original.length == 36) UUID_COMPRESSED_VALUE_PREFIX else OBJECTID_COMPRESSED_VALUE_PREFIX
                val absoluteOffset = buffer.globalOffset + match.range.first
                val compressed = "$prefix${absoluteOffset.toString(36)}"
                buffer.originalToCompressed[original] = compressed
                buffer.compressedToOriginal[compressed] = original
            }
        }

        buffer.originalToCompressed.forEach { (original, compressed) ->
            compressedText = compressedText.replace(original, compressed)
        }

        buffer.globalOffset += text.length
        return compressedText
    }

    /**
     * Replaces all compressed aliases in [text] with their original IDs.
     * Aliases that are not present in [buffer] are left unchanged.
     */
    fun uncompress(text: String, buffer: MessageCompressorBuffer): String {
        var result = text
        buffer.compressedToOriginal.forEach { (compressed, original) ->
            result = result.replace(compressed, original)
        }
        return result
    }

    /**
     * Appends [chunk] to the streaming carry buffer, emits the safe prefix (fully
     * decompressed), and holds back any suffix that could still grow into a known alias.
     *
     * Call this for every chunk received from the LLM stream.
     * Call [flush] once after the stream ends.
     *
     * @see MessageCompressorBuffer for the algorithm description.
     */
    fun feed(chunk: String, buffer: MessageCompressorBuffer): String {
        buffer.streamCarry.append(chunk)
        val safeEnd = findSafeEnd(buffer.streamCarry.toString(), buffer.compressedToOriginal.keys)
        if (safeEnd == 0) return ""
        val toEmit = buffer.streamCarry.substring(0, safeEnd)
        buffer.streamCarry.delete(0, safeEnd)
        return uncompress(toEmit, buffer)
    }

    /**
     * Decompresses and returns whatever remains in the streaming carry buffer.
     * Must be called exactly once after the stream ends.
     */
    fun flush(buffer: MessageCompressorBuffer): String {
        val remaining = buffer.streamCarry.toString()
        buffer.streamCarry.clear()
        return uncompress(remaining, buffer)
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Returns the index up to which [text] is safe to emit — i.e. the position where
     * the longest suffix that could still grow into a known compressed alias starts.
     *
     * A suffix is held back only when it is a **strict prefix** of a known alias
     * (i.e. the alias starts with the suffix AND is longer than it). An exact
     * complete-alias match is NOT held back — [uncompress] will handle it.
     *
     * Scans suffixes from longest (position 0) to shortest (last char). The first
     * suffix that is a strict prefix of any known alias determines the hold-back
     * point. If no suffix qualifies, the entire string is safe.
     */
    private fun findSafeEnd(text: String, tokens: Set<String>): Int {
        if (tokens.isEmpty()) return text.length
        for (suffixStart in 0 until text.length) {
            val suffix = text.substring(suffixStart)
            if (tokens.any { it.startsWith(suffix) && it.length > suffix.length }) {
                return suffixStart
            }
        }
        return text.length
    }
}

/**
 * Mutable state for a single LLM call's compression/decompression lifecycle.
 *
 * Holds the bidirectional alias maps accumulated during `compress` calls and the
 * carry buffer used by the streaming decompressor.
 *
 * This class is pure state — all logic lives in [IdCompressorService].
 *
 * **Not thread-safe.** Create one instance per LLM call and do not share it
 * across concurrent coroutines.
 *
 * ### Streaming algorithm
 *
 * Compressed tokens always have the form `UI<base36>` or `OI<base36>`. Rather than
 * holding back a fixed window, [IdCompressorService.feed] scans the tail of the
 * carry for the longest suffix that is a **strict prefix of any known alias**.
 * Only that suffix is held back; everything before it is safe to emit.
 *
 * This correctly handles tokens that arrive one character at a time:
 * - carry = `"UI"` → suffix `"UI"` is a prefix of e.g. `"UI2fj"` → hold all of it
 * - carry = `"UI2"` → suffix `"UI2"` is a prefix → hold all of it
 * - carry = `"UI2fj"` → exact match → will be decompressed in the next feed or flush
 * - carry = `"hello UI2"` → safe prefix `"hello "` emitted, `"UI2"` held
 */
class MessageCompressorBuffer {
    internal val originalToCompressed: MutableMap<String, String> = mutableMapOf()
    internal val compressedToOriginal: MutableMap<String, String> = mutableMapOf()
    internal var globalOffset: Int = 0
    internal val streamCarry: StringBuilder = StringBuilder()
}
