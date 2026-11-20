package io.whozoss.factory.forge.bmad

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Reads the document bytes from a pinned Git commit, never from the moving worktree. */
internal class ForgeGitPacketSnapshot(
    private val git: GitObjects = ProcessGitObjects(),
) {
    interface GitObjects {
        fun commitExists(worktree: Path, commit: String): Boolean
        fun readBlob(worktree: Path, commit: String, path: String, maxBytes: Int): ByteArray?
    }

    sealed interface Result {
        data class Valid(val repository: String, val commit: String, val canonicalManifest: ByteArray, val sha256: String) : Result
        data class Invalid(val code: String) : Result
    }

    private val mapper = ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)

    /** [expectedRepository] and [worktree] must come from trusted Run/workstream state. */
    fun verify(
        rawManifest: ByteArray, expectedRepository: String, worktree: Path,
        acceptedDesign: Map<String, Any?>? = null,
    ): Result {
        if (rawManifest.size > MAX_MANIFEST_BYTES || expectedRepository.isBlank()) return Result.Invalid("INVALID_PACKET_SOURCE")
        val root = try { mapper.readTree(rawManifest) } catch (_: Exception) { return Result.Invalid("INVALID_MANIFEST_JSON") }
        val source = root?.path("source") ?: return Result.Invalid("INVALID_PACKET_SOURCE")
        val repository = source.path("repository").asText()
        val commit = source.path("commit").asText()
        if (repository != expectedRepository || !COMMIT_SHA.matches(commit)) return Result.Invalid("INVALID_PACKET_SOURCE")

        val entries = root.path("entries")
        if (!entries.isArray || entries.size() > MAX_ENTRIES) return Result.Invalid("INVALID_PACKET_ENTRIES")
        val paths = linkedMapOf<String, Int>()
        var total = 0L
        for (entry in entries) {
            val path = entry.path("path").asText()
            val size = entry.path("sizeBytes")
            if (!safePath(path) || !size.isIntegralNumber || size.asLong() !in 1L..MAX_DOCUMENT_BYTES.toLong() ||
                paths.putIfAbsent(path, size.asInt()) != null
            ) return Result.Invalid("INVALID_PACKET_ENTRIES")
            total += size.asLong()
            if (total > MAX_PACKET_BYTES) return Result.Invalid("PACKET_TOO_LARGE")
        }

        return try {
            if (!git.commitExists(worktree, commit)) return Result.Invalid("GIT_COMMIT_UNAVAILABLE")
            val bytes = linkedMapOf<String, ByteArray>()
            var actualTotal = 0L
            for ((path, _) in paths) {
                val remaining = (MAX_PACKET_BYTES - actualTotal).toInt()
                if (remaining <= 0) return Result.Invalid("PACKET_TOO_LARGE")
                val content = git.readBlob(worktree, commit, path, minOf(MAX_DOCUMENT_BYTES, remaining))
                    ?: return Result.Invalid("GIT_DOCUMENT_UNAVAILABLE")
                actualTotal += content.size
                if (actualTotal > MAX_PACKET_BYTES) return Result.Invalid("PACKET_TOO_LARGE")
                bytes[path] = content
            }
            when (val checked = ForgePacketManifest.validate(rawManifest, bytes)) {
                is ForgePacketManifest.Result.Invalid -> Result.Invalid(checked.code)
                is ForgePacketManifest.Result.Valid -> {
                    val mismatch = acceptedDesign?.let { checkDesign(root, bytes, it) }
                    if (mismatch == null) Result.Valid(repository, commit, checked.canonicalBytes, checked.sha256)
                    else Result.Invalid(mismatch)
                }
            }
        } catch (_: Exception) {
            Result.Invalid("GIT_SNAPSHOT_UNAVAILABLE")
        }
    }

    private fun checkDesign(
        manifest: JsonNode, documents: Map<String, ByteArray>, state: Map<String, Any?>,
    ): String? {
        val applicability = state["applicability"] as? Map<*, *>
        if (applicability?.keys != ForgePacketManifest.optionalKinds || ForgePacketManifest.optionalKinds.any { kind ->
                manifest.path("applicability").path(kind) != mapper.valueToTree<JsonNode>(applicability?.get(kind))
            }
        ) return "PACKET_APPLICABILITY_MISMATCH"
        fun document(kind: String): ByteArray = documents.getValue(manifest.path("entries").single {
            it.path("kind").asText() == kind
        }.path("path").asText())
        val acceptedPlan = mapper.valueToTree<JsonNode>(state["delivery_plan"])
        val committedPlan = runCatching { mapper.readTree(document("implementation_plan")) }.getOrNull()
        if (!acceptedPlan.path("steps").isArray || acceptedPlan.path("steps").isEmpty || committedPlan != acceptedPlan) {
            return "PACKET_IMPLEMENTATION_PLAN_MISMATCH"
        }
        val verification = state["verification_plan"] as? String
        return if (verification.isNullOrBlank() || !document("verification_plan").contentEquals(verification.toByteArray(Charsets.UTF_8)))
            "PACKET_VERIFICATION_PLAN_MISMATCH" else null
    }

    private fun safePath(path: String): Boolean = path.isNotBlank() && !path.startsWith('/') &&
        !path.contains('\\') && !path.contains('\u0000') &&
        path.split('/').all { it.isNotEmpty() && it != "." && it != ".." }

    private companion object {
        val COMMIT_SHA = Regex("^[0-9a-f]{40}$")
        const val MAX_ENTRIES = 100
        // Factory worker inline artifacts currently cap one artifact at 256 KiB.
        const val MAX_MANIFEST_BYTES = 256 * 1024
        const val MAX_DOCUMENT_BYTES = 16 * 1024 * 1024
        const val MAX_PACKET_BYTES = 64L * 1024 * 1024
    }
}

/** Git is invoked without a shell; failures become a technical pause at the caller boundary. */
internal class ProcessGitObjects : ForgeGitPacketSnapshot.GitObjects {
    fun sourceRepository(worktree: Path): String? =
        runGit(worktree, listOf("remote", "get-url", "origin"), 1024)
            ?.toString(Charsets.UTF_8)?.trim()
            ?.trimEnd('/')?.removeSuffix(".git")?.substringAfterLast('/')?.substringAfterLast(':')
            ?.takeIf { Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$").matches(it) }

    override fun commitExists(worktree: Path, commit: String): Boolean =
        runGit(worktree, listOf("cat-file", "-e", "$commit^{commit}"), 1024) != null

    override fun readBlob(worktree: Path, commit: String, path: String, maxBytes: Int): ByteArray? =
        runGit(worktree, listOf("cat-file", "blob", "$commit:$path"), maxBytes)

    private fun runGit(worktree: Path, args: List<String>, maxBytes: Int): ByteArray? {
        val process = ProcessBuilder(listOf("git", "-C", worktree.toString()) + args)
            .redirectErrorStream(true)
            .apply { environment()["GIT_NO_REPLACE_OBJECTS"] = "1" }
            .start()
        val reader = Executors.newSingleThreadExecutor { task -> Thread(task, "forge-git-snapshot").apply { isDaemon = true } }
        return try {
            val output = reader.submit<ByteArray> { process.inputStream.use { it.readNBytes(maxBytes + 1) } }
                .get(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (output.size > maxBytes || !process.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS) || process.exitValue() != 0) null
            else output
        } finally {
            process.destroyForcibly()
            reader.shutdownNow()
        }
    }

    private companion object {
        const val GIT_TIMEOUT_SECONDS = 10L
    }
}
