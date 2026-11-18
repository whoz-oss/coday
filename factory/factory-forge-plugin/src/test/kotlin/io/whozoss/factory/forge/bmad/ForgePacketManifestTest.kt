package io.whozoss.factory.forge.bmad

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.security.MessageDigest

class ForgePacketManifestTest {
    private val mapper = ObjectMapper()
    private val commit = "a".repeat(40)
    private val bytes = linkedMapOf(
        "specs/SPEC.md" to "# Source specification".toByteArray(),
        "plans/implementation.md" to "1. Implement the selected scope".toByteArray(),
        "plans/verification.md" to "1. Run the repository tests".toByteArray(),
    )

    private fun digest(data: ByteArray) = MessageDigest.getInstance("SHA-256")
        .digest(data).joinToString("") { "%02x".format(it) }

    private fun entry(kind: String, path: String, data: ByteArray) = linkedMapOf<String, Any>(
        "kind" to kind,
        "path" to path,
        "locator" to "git:whoz:$commit:$path",
        "revision" to commit,
        "sha256" to digest(data),
        "sizeBytes" to data.size,
    )

    private fun manifest() = linkedMapOf<String, Any>(
        "schemaVersion" to "packet-manifest.v1",
        "source" to linkedMapOf("repository" to "whoz", "commit" to commit),
        "applicability" to linkedMapOf(
            "source_spec" to mapOf("applicable" to true, "reason" to "Ticket source"),
            "detailed_spec" to mapOf("applicable" to false, "reason" to "Covered by source"),
            "technical_spec" to mapOf("applicable" to false, "reason" to "No extra technical spec"),
            "ux_spec" to mapOf("applicable" to false, "reason" to "No UX change"),
            "qa_spec" to mapOf("applicable" to false, "reason" to "Covered by verification plan"),
            "implementation_plan" to mapOf("applicable" to true, "reason" to "Always required"),
            "verification_plan" to mapOf("applicable" to true, "reason" to "Always required"),
        ),
        "entries" to mutableListOf(
            entry("source_spec", "specs/SPEC.md", bytes.getValue("specs/SPEC.md")),
            entry("implementation_plan", "plans/implementation.md", bytes.getValue("plans/implementation.md")),
            entry("verification_plan", "plans/verification.md", bytes.getValue("plans/verification.md")),
        ),
    )

    private fun validate(manifest: Map<String, Any>, content: Map<String, ByteArray> = bytes) =
        ForgePacketManifest.validate(mapper.writeValueAsBytes(manifest), content)

    @Test
    fun `valid packet returns canonical manifest bytes and their digest`() {
        val first = validate(manifest()) as ForgePacketManifest.Result.Valid
        val reordered = manifest().toSortedMap(reverseOrder())
        val second = validate(reordered) as ForgePacketManifest.Result.Valid
        assertThat(first.canonicalBytes).isEqualTo(second.canonicalBytes)
        assertThat(first.sha256).isEqualTo(digest(first.canonicalBytes))
        assertThat(second.sha256).isEqualTo(first.sha256)
    }

    @Test
    fun `missing plan and contradictory applicability fail closed`() {
        val missing = manifest()
        @Suppress("UNCHECKED_CAST")
        (missing["entries"] as MutableList<Map<String, Any>>).removeAt(2)
        assertThat(validate(missing, bytes - "plans/verification.md"))
            .isEqualTo(ForgePacketManifest.Result.Invalid("APPLICABILITY_MISMATCH"))

        val contradictory = manifest()
        @Suppress("UNCHECKED_CAST")
        (contradictory["applicability"] as MutableMap<String, Any>)["ux_spec"] =
            mapOf("applicable" to true, "reason" to "UX required")
        assertThat(validate(contradictory))
            .isEqualTo(ForgePacketManifest.Result.Invalid("APPLICABILITY_MISMATCH"))
    }

    @Test
    fun `duplicate normalized paths and unsafe paths fail closed`() {
        val duplicate = manifest()
        @Suppress("UNCHECKED_CAST")
        (duplicate["entries"] as MutableList<Map<String, Any>>).add(
            entry("other_plan", "specs/spec.md", "another".toByteArray()),
        )
        assertThat(validate(duplicate, bytes + ("specs/spec.md" to "another".toByteArray())))
            .isEqualTo(ForgePacketManifest.Result.Invalid("DUPLICATE_OR_UNSAFE_PATH"))

        val unsafe = manifest()
        @Suppress("UNCHECKED_CAST")
        (unsafe["entries"] as MutableList<MutableMap<String, Any>>)[0]["path"] = "specs/../SPEC.md"
        assertThat(validate(unsafe))
            .isEqualTo(ForgePacketManifest.Result.Invalid("DUPLICATE_OR_UNSAFE_PATH"))
    }

    @Test
    fun `revision content hash and empty plan mismatches fail closed`() {
        val revision = manifest()
        @Suppress("UNCHECKED_CAST")
        (revision["entries"] as MutableList<MutableMap<String, Any>>)[0]["revision"] = "b".repeat(40)
        assertThat(validate(revision)).isEqualTo(ForgePacketManifest.Result.Invalid("REVISION_MISMATCH"))

        val tampered = bytes + ("specs/SPEC.md" to "changed".toByteArray())
        assertThat(validate(manifest(), tampered)).isEqualTo(ForgePacketManifest.Result.Invalid("CONTENT_MISMATCH"))

        val blank = "   ".toByteArray()
        val emptyPlan = manifest()
        @Suppress("UNCHECKED_CAST")
        (emptyPlan["entries"] as MutableList<MutableMap<String, Any>>)[1] =
            entry("implementation_plan", "plans/implementation.md", blank)
        assertThat(validate(emptyPlan, bytes + ("plans/implementation.md" to blank)))
            .isEqualTo(ForgePacketManifest.Result.Invalid("EMPTY_PLAN"))
    }
}
