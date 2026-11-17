package io.whozoss.factory.forge.bmad

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.ObjectMapper

/**
 * Forge-owned machine-readable values carried by Factory's existing artifact field.
 * JSON is plain Markdown content, so the Factory 249 worker schema and persistence
 * can transport it without a service-level result extension.
 */
internal object ForgeBusinessOutputArtifact {
    const val KIND = "forge.business-outputs.v1"
    private const val MAX_BYTES = 65_536
    private val keyPattern = Regex("^[A-Za-z][A-Za-z0-9_.-]{0,127}$")
    private val reservedKeys = setOf("status", "summary", "claims", "artifacts", "findings", "selectedRoutes", "outputs")
    private val mapper = ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)

    sealed interface ReadResult {
        data object Missing : ReadResult
        data class Invalid(val reason: String) : ReadResult
        data class Valid(val values: Map<String, Any?>) : ReadResult
    }

    fun read(report: Map<String, Any?>): ReadResult {
        val artifacts = report["artifacts"] as? List<*> ?: return ReadResult.Missing
        val matching = artifacts.mapNotNull { artifact ->
            val node = runCatching { mapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(artifact) }.getOrNull()
            node?.takeIf { it.path("kind").asText() == KIND }
        }
        if (matching.isEmpty()) return ReadResult.Missing
        if (matching.size != 1) return ReadResult.Invalid("exactly one $KIND artifact is allowed")
        val artifact = matching.single()
        if (artifact.path("encoding").asText() != "markdown") {
            return ReadResult.Invalid("$KIND must use markdown encoding")
        }
        val content = artifact.path("content").takeIf { it.isTextual }?.asText()
            ?: return ReadResult.Invalid("$KIND must contain JSON text")
        if (content.toByteArray(Charsets.UTF_8).size > MAX_BYTES) {
            return ReadResult.Invalid("$KIND exceeds $MAX_BYTES bytes")
        }
        val parsed = runCatching { mapper.readTree(content) }.getOrNull()
            ?: return ReadResult.Invalid("$KIND contains invalid JSON")
        if (!parsed.isObject || parsed.size() > 40 ||
            parsed.fieldNames().asSequence().any { !keyPattern.matches(it) || it in reservedKeys }
        ) return ReadResult.Invalid("$KIND must contain an object with at most 40 safe keys")
        @Suppress("UNCHECKED_CAST")
        val values = mapper.convertValue(parsed, Map::class.java) as Map<String, Any?>
        return ReadResult.Valid(values)
    }

    fun values(report: Map<String, Any?>): Map<String, Any?> =
        (read(report) as? ReadResult.Valid)?.values.orEmpty()
}
