package io.whozoss.factory.forge.bmad

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

class ForgeGitPacketSnapshotTest {
    @TempDir lateinit var repo: Path
    private val mapper = ObjectMapper()
    private val verifier = ForgeGitPacketSnapshot()

    @Test
    fun `approved packet reads pinned commit even after branch and worktree move`() {
        initRepo()
        val documents = documents()
        writeDocuments(documents)
        git("add", ".")
        git("commit", "-qm", "design packet")
        val pinned = git("rev-parse", "HEAD").trim()
        val manifest = manifest(pinned, documents)

        val first = verifier.verify(manifest, "whoz", repo) as ForgeGitPacketSnapshot.Result.Valid
        Files.writeString(repo.resolve("specs/SPEC.md"), "# Changed after approval")
        git("add", ".")
        git("commit", "-qm", "later design")
        val later = verifier.verify(manifest, "whoz", repo)

        assertThat(first.commit).isEqualTo(pinned)
        assertThat(first.sha256).isEqualTo(sha(first.canonicalManifest))
        assertThat(later).isInstanceOf(ForgeGitPacketSnapshot.Result.Valid::class.java)
        later as ForgeGitPacketSnapshot.Result.Valid
        assertThat(later.repository).isEqualTo(first.repository)
        assertThat(later.commit).isEqualTo(first.commit)
        assertThat(later.sha256).isEqualTo(first.sha256)
        assertThat(later.canonicalManifest).isEqualTo(first.canonicalManifest)
    }

    @Test
    fun `missing committed document and wrong repository fail closed`() {
        initRepo()
        val documents = documents()
        writeDocuments(documents)
        git("add", ".")
        git("commit", "-qm", "design packet")
        val pinned = git("rev-parse", "HEAD").trim()
        val manifest = manifest(pinned, documents)

        assertThat(verifier.verify(manifest, "another-repository", repo))
            .isEqualTo(ForgeGitPacketSnapshot.Result.Invalid("INVALID_PACKET_SOURCE"))
        assertThat(verifier.verify(manifest(pinned, documents, missingPath = "plans/missing.md"), "whoz", repo))
            .isEqualTo(ForgeGitPacketSnapshot.Result.Invalid("GIT_DOCUMENT_UNAVAILABLE"))
        assertThat(verifier.verify(manifest("f".repeat(40), documents), "whoz", repo))
            .isEqualTo(ForgeGitPacketSnapshot.Result.Invalid("GIT_COMMIT_UNAVAILABLE"))
    }

    @Test
    fun `committed bytes must match the declared packet digest`() {
        initRepo()
        val documents = documents()
        writeDocuments(documents)
        git("add", ".")
        git("commit", "-qm", "design packet")
        val pinned = git("rev-parse", "HEAD").trim()
        val falseDocuments = documents + ("specs/SPEC.md" to "# Uncommitted assertion".toByteArray())

        assertThat(verifier.verify(manifest(pinned, falseDocuments), "whoz", repo))
            .isEqualTo(ForgeGitPacketSnapshot.Result.Invalid("CONTENT_MISMATCH"))
    }

    @Test
    fun `unavailable source checkout cannot be replaced by manifest claims`() {
        initRepo()
        val documents = documents()
        writeDocuments(documents)
        git("add", ".")
        git("commit", "-qm", "design packet")
        val pinned = git("rev-parse", "HEAD").trim()

        assertThat(verifier.verify(manifest(pinned, documents), "whoz", repo.resolve("unavailable")))
            .isEqualTo(ForgeGitPacketSnapshot.Result.Invalid("GIT_COMMIT_UNAVAILABLE"))
    }

    private fun initRepo() {
        git("init", "-q")
        git("config", "user.name", "Forge Test")
        git("config", "user.email", "forge-test@example.invalid")
    }

    private fun documents() = linkedMapOf(
        "specs/SPEC.md" to "# Approved source".toByteArray(),
        "plans/implementation.md" to "1. Implement".toByteArray(),
        "plans/verification.md" to "1. Verify".toByteArray(),
    )

    private fun writeDocuments(documents: Map<String, ByteArray>) {
        for ((path, bytes) in documents) {
            val file = repo.resolve(path)
            Files.createDirectories(file.parent)
            Files.write(file, bytes)
        }
    }

    private fun manifest(commit: String, documents: Map<String, ByteArray>, missingPath: String? = null): ByteArray {
        val paths = documents.keys.toList()
        val kinds = listOf("source_spec", "implementation_plan", "verification_plan")
        val entries = paths.mapIndexed { index, path ->
            val actualPath = if (index == 1) missingPath ?: path else path
            mapOf("kind" to kinds[index], "path" to actualPath, "locator" to "git:whoz:$commit:$actualPath",
                "revision" to commit, "sha256" to sha(documents.getValue(path)), "sizeBytes" to documents.getValue(path).size)
        }
        return mapper.writeValueAsBytes(mapOf(
            "schemaVersion" to "packet-manifest.v1",
            "source" to mapOf("repository" to "whoz", "commit" to commit),
            "applicability" to mapOf(
                "source_spec" to decision(true), "detailed_spec" to decision(false),
                "technical_spec" to decision(false), "ux_spec" to decision(false),
                "qa_spec" to decision(false), "implementation_plan" to decision(true),
                "verification_plan" to decision(true),
            ),
            "entries" to entries,
        ))
    }

    private fun decision(applicable: Boolean) = mapOf("applicable" to applicable, "reason" to "Test decision")

    private fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private fun git(vararg args: String): String {
        val process = ProcessBuilder(listOf("git", "-C", repo.toString()) + args).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "Git fixture failed: $output" }
        return output
    }
}
