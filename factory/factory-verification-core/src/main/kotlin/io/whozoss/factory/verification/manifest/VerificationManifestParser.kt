package io.whozoss.factory.verification.manifest

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * Parser of the target-repository verification manifest
 * (`<repo_root>/factory/verification.json`).
 *
 * Pure and deterministic: reads a single file under the destination repository
 * root, validates the whitelist shape, and returns a [VerificationManifest].
 * Anything that is not an explicit `name -> { command, timeoutMs? }` declaration
 * is rejected with a [VerificationManifestException] — never silently ignored,
 * because the manifest is a trust boundary.
 */
object VerificationManifestParser {

    private val MAPPER = ObjectMapper()

    private const val ROOT_FIELDS = "schemaVersion, verifications"

    /** The absolute path of the manifest inside [repoRoot]. */
    fun pathFor(repoRoot: Path): Path = repoRoot.resolve(VERIFICATION_MANIFEST_RELATIVE_PATH)

    /**
     * Loads the manifest of [repoRoot].
     *
     * @throws VerificationManifestException [VERIFICATION_MANIFEST_MISSING] when
     *   the file is absent, [VERIFICATION_MANIFEST_INVALID] when it is not a valid
     *   whitelist.
     */
    fun load(repoRoot: Path): VerificationManifest {
        val path = pathFor(repoRoot)
        if (!Files.isRegularFile(path)) {
            throw VerificationManifestException(
                VERIFICATION_MANIFEST_MISSING,
                "No verification manifest at $path (expected a whitelist at $VERIFICATION_MANIFEST_RELATIVE_PATH).",
            )
        }
        val json = try {
            Files.readString(path, StandardCharsets.UTF_8)
        } catch (e: IOException) {
            throw VerificationManifestException(
                VERIFICATION_MANIFEST_INVALID,
                "Unreadable verification manifest at $path: ${e.message}",
            )
        }
        return parse(json)
    }

    /** Parses a manifest from raw JSON text. */
    fun parse(json: String): VerificationManifest {
        val root = try {
            MAPPER.readTree(json)
        } catch (e: Exception) {
            throw VerificationManifestException(VERIFICATION_MANIFEST_INVALID, "Invalid verification manifest JSON: ${e.message}")
        }
        if (root == null || !root.isObject) {
            throw invalid("The manifest root must be a JSON object with fields: $ROOT_FIELDS.")
        }

        val schemaVersionNode = root.get("schemaVersion")
        if (schemaVersionNode == null || !schemaVersionNode.isTextual) {
            throw invalid("'schemaVersion' is required and must be the string \"$VERIFICATION_MANIFEST_SCHEMA_VERSION\".")
        }
        val schemaVersion = schemaVersionNode.asText()
        if (schemaVersion != VERIFICATION_MANIFEST_SCHEMA_VERSION) {
            throw invalid("Unsupported 'schemaVersion' \"$schemaVersion\"; expected \"$VERIFICATION_MANIFEST_SCHEMA_VERSION\".")
        }

        val verificationsNode = root.get("verifications")
        if (verificationsNode == null || !verificationsNode.isObject) {
            throw invalid("'verifications' is required and must be an object of name -> { command, timeoutMs? }.")
        }

        val entries = LinkedHashMap<String, VerificationEntry>()
        val names = verificationsNode.fieldNames()
        while (names.hasNext()) {
            val name = names.next()
            if (name.isBlank()) {
                throw invalid("A verification name must be a non-blank string.")
            }
            entries[name] = parseEntry(name, verificationsNode.get(name))
        }

        return VerificationManifest(schemaVersion = schemaVersion, verifications = entries)
    }

    private fun parseEntry(name: String, node: JsonNode?): VerificationEntry {
        if (node == null || !node.isObject) {
            throw invalid("Verification '$name' must be an object with a 'command' and an optional 'timeoutMs'.")
        }
        val fields = node.fieldNames().asSequence().toList()
        val unknown = fields.filter { it != "command" && it != "timeoutMs" }
        if (unknown.isNotEmpty()) {
            throw invalid("Verification '$name' has unknown field(s) ${unknown.joinToString(", ")}; only 'command' and 'timeoutMs' are allowed.")
        }

        val commandNode = node.get("command")
        if (commandNode == null || !commandNode.isTextual || commandNode.asText().isBlank()) {
            throw invalid("Verification '$name' requires a non-blank 'command'.")
        }

        val timeoutNode = node.get("timeoutMs")
        val timeoutMs = when {
            timeoutNode == null || timeoutNode.isNull -> null
            timeoutNode.isIntegralNumber && timeoutNode.asLong() > 0 -> timeoutNode.asLong()
            else -> throw invalid("Verification '$name' has an invalid 'timeoutMs'; expected a positive integer.")
        }

        return VerificationEntry(command = commandNode.asText(), timeoutMs = timeoutMs)
    }

    private fun invalid(message: String) = VerificationManifestException(VERIFICATION_MANIFEST_INVALID, message)
}
