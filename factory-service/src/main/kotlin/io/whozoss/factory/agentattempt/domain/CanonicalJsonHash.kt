package io.whozoss.factory.agentattempt.domain

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.security.MessageDigest

/**
 * Canonical JSON hashing and constant-time comparison for the AGENT-STEP
 * aggregate.
 *
 * Faithful port of the `sha256` / `canonicalizeAgentStepResult` /
 * `canonicalAgentStepResultJson` / `hashAgentStepResult` / `safeEqual` /
 * `isSafeAgentStepResultId` helpers in
 * `factory/src/domain/agent-attempt/agent-step-result.ts`:
 *
 *   * object keys are recursively sorted with the natural (lexicographic)
 *     `String` ordering — the Node `Object.keys(record).sort()` default;
 *   * arrays keep their position;
 *   * `null` values are **preserved** so the digest is identical to the Node
 *     `JSON.stringify(canonicalize(value))` output;
 *   * the raw digest is a lowercase SHA-256 hex string; the prefixed form is
 *     `sha256:<hex>`, exactly like `createHash('sha256')`.
 *
 * `safeEqual` uses [MessageDigest.isEqual] so capability token digests are
 * compared in constant time.
 */
object CanonicalJsonHash {

    private val mapper = ObjectMapper()

    private val SAFE_ID = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")
    private val BRIEF_HASH = Regex("^sha256:[0-9a-f]{64}$")

    /** Recursively canonicalized node: sorted object keys, ordered arrays, preserved nulls. */
    fun canonicalize(node: JsonNode): JsonNode = when {
        node.isObject -> {
            val sorted = mapper.createObjectNode()
            node.fieldNames().asSequence().sorted().forEach { key ->
                sorted.set<JsonNode>(key, canonicalize(node.get(key)))
            }
            sorted
        }

        node.isArray -> {
            val array = mapper.createArrayNode()
            node.forEach { entry -> array.add(canonicalize(entry)) }
            array
        }

        else -> node
    }

    /** Compact canonical JSON of the recursively key-sorted node. */
    fun canonicalJson(node: JsonNode): String = mapper.writeValueAsString(canonicalize(node))

    /** Lowercase SHA-256 hex digest of the UTF-8 encoded [input]. */
    fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** `sha256:<hex>` digest of the UTF-8 encoded [input], matching the Node `sha256`. */
    fun sha256(input: String): String = "sha256:${sha256Hex(input)}"

    /** `sha256:<hex>` digest of the canonical JSON form of [node]. */
    fun hash(node: JsonNode): String = sha256(canonicalJson(node))

    /** Constant-time comparison of two strings, false on any mismatch. */
    fun safeEqual(left: String, right: String): Boolean =
        MessageDigest.isEqual(left.toByteArray(Charsets.UTF_8), right.toByteArray(Charsets.UTF_8))

    /** True when [value] matches the factory safe-identifier grammar. */
    fun isSafeId(value: String?): Boolean = value != null && SAFE_ID.matches(value)

    /** True when [value] is a `sha256:<64 hex>` brief hash. */
    fun isBriefHash(value: String?): Boolean = value != null && BRIEF_HASH.matches(value)
}
