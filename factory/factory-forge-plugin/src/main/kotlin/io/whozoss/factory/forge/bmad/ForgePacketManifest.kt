package io.whozoss.factory.forge.bmad

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.security.MessageDigest
import java.util.Locale

/** Pure CE05 packet check; publication, retention and human authority remain Factory service work. */
internal object ForgePacketManifest {
    private val mapper = ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
    private val sha256 = Regex("^[0-9a-f]{64}$")
    private val commitSha = Regex("^[0-9a-f]{40}$")
    private val required = setOf("source_spec", "implementation_plan", "verification_plan")
    private val optional = setOf("detailed_spec", "technical_spec", "ux_spec", "qa_spec")
    private val decidedKinds = required + optional
    private val allowedKinds = decidedKinds + "other_plan"

    sealed interface Result {
        data class Valid(val canonicalBytes: ByteArray, val sha256: String) : Result
        data class Invalid(val code: String) : Result
    }

    /** Return bytes that must be stored unchanged; every digest refers to these exact bytes. */
    fun validate(rawBytes: ByteArray, documentBytes: Map<String, ByteArray>): Result {
        val root = try { mapper.readTree(rawBytes) } catch (_: Exception) { return Result.Invalid("INVALID_JSON") }
        if (!root.isObject || !hasFields(root, setOf("schemaVersion", "source", "applicability", "entries")) ||
            root.path("schemaVersion").asText() != "packet-manifest.v1"
        ) return Result.Invalid("INVALID_MANIFEST_SHAPE")

        val source = root.path("source")
        if (!source.isObject || !hasFields(source, setOf("repository", "commit")) ||
            source.path("repository").asText().isBlank() || !commitSha.matches(source.path("commit").asText())
        ) return Result.Invalid("INVALID_SOURCE_REVISION")
        val commit = source.path("commit").asText()

        val applicability = root.path("applicability")
        if (!applicability.isObject || !hasFields(applicability, decidedKinds)) return Result.Invalid("INVALID_APPLICABILITY")
        for (kind in decidedKinds) {
            val decision = applicability.path(kind)
            if (!decision.isObject || !hasFields(decision, setOf("applicable", "reason")) ||
                !decision.path("applicable").isBoolean || decision.path("reason").asText().isBlank() ||
                (kind in required && !decision.path("applicable").asBoolean())
            ) return Result.Invalid("INVALID_APPLICABILITY")
        }

        val entries = root.path("entries")
        if (!entries.isArray || entries.size() > 100) return Result.Invalid("INVALID_ENTRIES")
        val seenPaths = mutableSetOf<String>()
        val counts = mutableMapOf<String, Int>()
        val usedBytes = mutableSetOf<String>()
        for (entry in entries) {
            if (!entry.isObject || !hasFields(entry, setOf("kind", "path", "locator", "revision", "sha256", "sizeBytes")))
                return Result.Invalid("INVALID_ENTRY")
            val kind = entry.path("kind").asText()
            val path = entry.path("path").asText()
            if (kind !in allowedKinds || !safePath(path) || !seenPaths.add(path.lowercase(Locale.ROOT)))
                return Result.Invalid("DUPLICATE_OR_UNSAFE_PATH")
            if (entry.path("locator").asText().isBlank() || entry.path("revision").asText() != commit)
                return Result.Invalid("REVISION_MISMATCH")
            val content = documentBytes[path] ?: return Result.Invalid("MISSING_DOCUMENT_BYTES")
            usedBytes += path
            val size = entry.path("sizeBytes")
            if (!size.isIntegralNumber || size.asLong() <= 0 || size.asLong() != content.size.toLong() ||
                !sha256.matches(entry.path("sha256").asText()) || entry.path("sha256").asText() != digest(content)
            ) return Result.Invalid("CONTENT_MISMATCH")
            if (kind.endsWith("_plan") && content.toString(Charsets.UTF_8).isBlank())
                return Result.Invalid("EMPTY_PLAN")
            counts[kind] = (counts[kind] ?: 0) + 1
        }
        if (documentBytes.keys != usedBytes) return Result.Invalid("UNLISTED_DOCUMENT_BYTES")
        for (kind in decidedKinds) {
            val expected = if (applicability.path(kind).path("applicable").asBoolean()) 1 else 0
            if (counts.getOrDefault(kind, 0) != expected) return Result.Invalid("APPLICABILITY_MISMATCH")
        }
        val canonicalBytes = mapper.writeValueAsBytes(sorted(root))
        return Result.Valid(canonicalBytes, digest(canonicalBytes))
    }

    private fun hasFields(node: JsonNode, names: Set<String>): Boolean =
        node.fieldNames().asSequence().toSet() == names

    private fun safePath(path: String): Boolean = path.isNotBlank() && !path.startsWith('/') && !path.contains('\\') &&
        !path.contains('\u0000') && path.split('/').all { it.isNotEmpty() && it != "." && it != ".." }

    private fun sorted(node: JsonNode): JsonNode = when {
        node.isObject -> mapper.createObjectNode().apply {
            node.fieldNames().asSequence().sorted().forEach { set<JsonNode>(it, sorted(node.get(it))) }
        }
        node.isArray -> mapper.createArrayNode().apply { node.forEach { add(sorted(it)) } }
        else -> node.deepCopy<JsonNode>()
    }

    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}
