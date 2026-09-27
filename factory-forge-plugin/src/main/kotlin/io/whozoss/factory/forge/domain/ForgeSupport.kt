package io.whozoss.factory.forge.domain

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import java.security.MessageDigest

/**
 * A Forge domain failure carrying a stable machine-readable code.
 *
 * Mirrors the `CodedError` thrown by the pure TypeScript Forge modules
 * (`forge-spec.ts`, `forge-story-spec.ts`, `forge-story-analysis.ts`,
 * `forge-story-edit.ts`, `forge-story-oracles.ts`,
 * `forge-front-oracle-resolution.ts`).
 */
class ForgeCodedException(
    val code: String,
    message: String = code,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/** One append-only Forge ledger event, as parsed from a JSONL line. */
typealias ForgeLedgerEvent = Map<String, Any?>

/** A governed work item (Epic or Story) referenced by a Forge run. */
data class ForgeWorkItem(
    val id: String,
    val kind: String,
)

/**
 * JSON/hash helpers shared by the pure Forge domain modules.
 *
 * The canonicalization and SHA-256 helpers reproduce the Node
 * `createHash('sha256')` / `JSON.stringify(canonical)` outputs byte-for-byte so
 * the evidence-set and spec hashes match the TypeScript runtime.
 */
internal object ForgeJson {

    val mapper = ObjectMapper()

    fun parseObject(raw: String): Map<String, Any?> =
        mapper.readValue(raw, object : TypeReference<LinkedHashMap<String, Any?>>() {})

    fun stringify(value: Any?): String = mapper.writeValueAsString(value)

    fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun sha256(input: String): String = "sha256:${sha256Hex(input)}"

    /** Recursively canonicalized value: sorted object keys, ordered arrays, preserved nulls. */
    fun canonical(value: Any?): Any? = when (value) {
        null -> null
        is Map<*, *> -> {
            val sorted = LinkedHashMap<String, Any?>()
            value.keys.map { it.toString() }.sorted().forEach { key ->
                val raw = value.entries.firstOrNull { it.key.toString() == key }?.value
                sorted[key] = canonical(raw)
            }
            sorted
        }

        is List<*> -> value.map { canonical(it) }
        is Array<*> -> value.map { canonical(it) }
        else -> value
    }
}

/** Numeric coercion that tolerates Jackson's Int/Long/Double widening. */
internal fun asInt(value: Any?): Int = when (value) {
    is Number -> value.toInt()
    is String -> value.toIntOrNull() ?: 0
    else -> 0
}

/** String coercion for optional ledger fields. */
internal fun asString(value: Any?): String? = value as? String

/** Map coercion that keeps the original insertion order. */
@Suppress("UNCHECKED_CAST")
internal fun asMap(value: Any?): Map<String, Any?>? = value as? Map<String, Any?>
