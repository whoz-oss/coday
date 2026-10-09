package io.whozoss.factory.delivery.domain

import com.fasterxml.jackson.databind.ObjectMapper
import java.security.MessageDigest

/**
 * Canonical JSON hashing shared by every delivery domain rule.
 *
 * Port of the `canonical()` / `hash()` helpers duplicated across the Node
 * `delivery-policy.ts`, `delivery-operation-definition.ts` and `delivery-store.ts`
 * modules: object keys are sorted recursively, `null`/absent values are dropped
 * and arrays keep their order, so two values that differ only by key order hash
 * identically. The digest is a lowercase SHA-256 hex string.
 */
object CanonicalHash {

    private val mapper = ObjectMapper()

    /** Canonical JSON shape: sorted object keys (dropping nulls), ordered arrays. */
    fun canonical(value: Any?): Any? = when (value) {
        null -> null
        is Map<*, *> -> value.entries
            .filter { it.value != null }
            .map { it.key.toString() to canonical(it.value) }
            .sortedBy { it.first }
            .toMap(LinkedHashMap())
        is List<*> -> value.map { canonical(it) }
        else -> value
    }

    /** Stable lowercase SHA-256 hex digest of the canonical JSON form. */
    fun sha256(value: Any?): String {
        val json = mapper.writeValueAsString(canonical(value))
        val digest = MessageDigest.getInstance("SHA-256").digest(json.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** Stable `sha256:`-prefixed digest of the canonical JSON form. */
    fun canonicalDeliveryHash(value: Any?): String = "sha256:${sha256(value)}"
}
