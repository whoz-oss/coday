package io.whozoss.factory.forge.bmad

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import io.whozoss.factory.sdk.spi.RunView
import io.whozoss.factory.sdk.spi.StepEvidenceObservation
import io.whozoss.factory.sdk.spi.StepEvidenceObservationContext
import io.whozoss.factory.sdk.spi.TrustedGitObjectReader

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
    fun `local Git replace refs cannot substitute another commit for the pinned packet`() {
        initRepo()
        val original = documents()
        writeDocuments(original)
        git("add", ".")
        git("commit", "-qm", "original packet")
        val pinned = git("rev-parse", "HEAD").trim()
        val replacement = original + ("specs/SPEC.md" to "# Different source".toByteArray())
        writeDocuments(replacement)
        git("add", ".")
        git("commit", "-qm", "replacement packet")
        val replacementCommit = git("rev-parse", "HEAD").trim()
        git("replace", pinned, replacementCommit)

        assertThat(verifier.verify(manifest(pinned, replacement), "whoz", repo))
            .isEqualTo(ForgeGitPacketSnapshot.Result.Invalid("CONTENT_MISMATCH"))
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

    @Test
    fun `observer reads a committed packet through the configured Git source`() {
        initRepo()
        git("remote", "add", "origin", "https://github.com/biznet-io/whoz.git")
        val documents = documents()
        writeDocuments(documents)
        git("add", ".")
        git("commit", "-qm", "design packet")
        val pinned = git("rev-parse", "HEAD").trim()
        val rawManifest = manifest(pinned, documents)
        val context = observationContext(rawManifest)

        val result = ForgePacketEvidenceObserver().observe(context)

        assertThat(result).isInstanceOf(StepEvidenceObservation.Observed::class.java)
        val facts = (result as StepEvidenceObservation.Observed).facts
        assertThat(facts["repository"]).isEqualTo("whoz")
        assertThat(facts["commit"]).isEqualTo(pinned)
        assertThat(facts["manifestSha256"]).isEqualTo(sha((facts["canonicalManifest"] as String).toByteArray()))
        assertThat(ForgePacketEvidenceObserver().observe(observationContext(manifest(pinned, documents +
            ("specs/SPEC.md" to "# Wrong bytes".toByteArray())))))
            .isInstanceOf(StepEvidenceObservation.Invalid::class.java)
    }

    @Test
    fun `observer pauses when trusted Git origin cannot be read`() {
        initRepo()
        val documents = documents()
        writeDocuments(documents)
        git("add", ".")
        git("commit", "-qm", "design packet")
        val result = ForgePacketEvidenceObserver().observe(observationContext(manifest(git("rev-parse", "HEAD").trim(), documents)))
        assertThat(result).isEqualTo(StepEvidenceObservation.Unavailable("GIT_SOURCE_UNAVAILABLE", "The Run's Git origin cannot be observed."))
    }

    @Test
    fun `observer verifies a remote Case Git packet without mounting its worktree`() {
        val documents = documents()
        val pinned = "a".repeat(40)
        val remote = object : TrustedGitObjectReader {
            override fun sourceRepository(): String = "whoz"
            override fun commitExists(commit: String): Boolean = commit == pinned
            override fun readBlob(commit: String, path: String, maxBytes: Int): ByteArray? =
                documents[path]?.takeIf { commit == pinned && it.size <= maxBytes }
        }
        val context = observationContext(manifest(pinned, documents)).copy(
            worktreePath = repo.resolve("not-mounted-in-factory").toString(), gitObjects = remote,
        )

        assertThat(ForgePacketEvidenceObserver().observe(context))
            .isInstanceOf(StepEvidenceObservation.Observed::class.java)
        val altered = documents + ("specs/SPEC.md" to "# Uncommitted assertion".toByteArray())
        assertThat(ForgePacketEvidenceObserver().observe(context.copy(
            report = observationContext(manifest(pinned, altered)).report,
        ))).isInstanceOf(StepEvidenceObservation.Invalid::class.java)
    }

    private fun observationContext(rawManifest: ByteArray) = StepEvidenceObservationContext(
        run = RunView("run-1", "forge.delivery"), flowType = "forge.delivery", stepId = "packet-assembly",
        agentName = "ProductEngineer", attemptId = "attempt-1", worktreePath = repo.toString(),
        report = mapOf("artifacts" to listOf(mapOf("kind" to "packet-manifest.v1", "encoding" to "markdown",
            "content" to rawManifest.toString(Charsets.UTF_8)))),
    )

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
