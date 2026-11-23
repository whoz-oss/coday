package io.whozoss.factory.forge.bmad

import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.factory.sdk.spi.FactoryStepEvidenceObserver
import io.whozoss.factory.sdk.spi.StepEvidenceObservation
import io.whozoss.factory.sdk.spi.StepEvidenceObservationContext
import org.pf4j.Extension
import java.nio.file.Path

/** Inspects the committed Forge packet in the Run-bound Git worktree, outside a DB transaction. */
@Extension
class ForgePacketEvidenceObserver : FactoryStepEvidenceObserver {
    private val git = ProcessGitObjects()
    private val mapper = ObjectMapper()

    override fun supports(flowType: String, stepId: String, agentName: String): Boolean =
        flowType == "forge.delivery" && stepId !in setOf("packet-approval", "implementation-build")

    override fun requiresBeforeSideEffect(flowType: String, stepId: String): Boolean =
        flowType == "forge.delivery" && stepId in setOf(
            "product-design", "ux-design", "technical-design", "implementation-planning", "qa-design", "packet-assembly",
        )

    private fun observeCaseBinding(context: StepEvidenceObservationContext): StepEvidenceObservation {
        val suppliedRoot = runCatching { Path.of(context.worktreePath).toRealPath() }.getOrNull()
            ?: return StepEvidenceObservation.Unavailable("WORKTREE_UNAVAILABLE", "The Run-bound Case path cannot be resolved.")
        val gitRoot = git.checkoutRoot(suppliedRoot)
            ?: return StepEvidenceObservation.Unavailable("GIT_SOURCE_UNAVAILABLE", "The Run-bound Case Git root cannot be observed.")
        if (gitRoot != suppliedRoot) return StepEvidenceObservation.Invalid(
            "CASE_WORKTREE_ROOT_MISMATCH", "The Run-bound worktree path is not the selected Case Git root.",
        )
        val repository = (context.gitObjects?.sourceRepository() ?: git.sourceRepository(gitRoot))
            ?.uppercase()
            ?.takeIf { it in setOf("WHOZ", "CODAY") }
            ?: return StepEvidenceObservation.Unavailable("GIT_SOURCE_UNAVAILABLE", "The Case repository identity cannot be observed.")
        val revision = git.headCommit(gitRoot)
            ?: return StepEvidenceObservation.Unavailable("GIT_SOURCE_UNAVAILABLE", "The Case HEAD revision cannot be observed.")
        return StepEvidenceObservation.Observed(mapOf("case_write" to mapOf(
            "repository" to repository,
            "location" to "CASE_EXCHANGE",
            "working_path" to gitRoot.toString(),
            "relative_path" to "repo",
            "revision" to revision,
        )))
    }

    override fun observe(context: StepEvidenceObservationContext): StepEvidenceObservation {
        val hasPersistedRouting = context.run.domainState["repositoryRouting"] is Map<*, *>
        val caseBinding = if (context.stepId == "delivery-preflight" || hasPersistedRouting) {
            observeCaseBinding(context)
        } else null
        if (caseBinding != null && caseBinding !is StepEvidenceObservation.Observed) return caseBinding
        if (context.stepId != "packet-assembly" || context.report.isEmpty()) {
            return caseBinding ?: StepEvidenceObservation.Observed(emptyMap())
        }
        val artifacts = context.report["artifacts"] as? List<*>
            ?: return StepEvidenceObservation.Invalid("PACKET_MANIFEST_MISSING", "The packet manifest artifact is missing.")
        val matching = artifacts.mapNotNull { raw ->
            runCatching { mapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(raw) }.getOrNull()
                ?.takeIf { it.path("kind").asText() == "packet-manifest.v1" }
        }
        if (matching.size != 1 || matching.singleOrNull()?.path("encoding")?.asText() != "markdown") {
            return StepEvidenceObservation.Invalid("PACKET_MANIFEST_INVALID", "Exactly one Markdown packet-manifest.v1 artifact is required.")
        }
        val content = matching.single().path("content").takeIf { it.isTextual }?.asText()
            ?: return StepEvidenceObservation.Invalid("PACKET_MANIFEST_INVALID", "The packet manifest must contain JSON text.")
        val bytes = content.toByteArray(Charsets.UTF_8)
        if (bytes.size > 256 * 1024) {
            return StepEvidenceObservation.Invalid("PACKET_MANIFEST_INVALID", "The packet manifest exceeds 256 KiB.")
        }
        val worktree = Path.of(context.worktreePath)
        val remote = context.gitObjects
        val repository = remote?.sourceRepository() ?: git.sourceRepository(worktree)
            ?: return StepEvidenceObservation.Unavailable("GIT_SOURCE_UNAVAILABLE", "The Run's Git origin cannot be observed.")
        val objects = if (remote == null) git else object : ForgeGitPacketSnapshot.GitObjects {
            override fun commitExists(worktree: Path, commit: String): Boolean = remote.commitExists(commit)
            override fun readBlob(worktree: Path, commit: String, path: String, maxBytes: Int): ByteArray? =
                remote.readBlob(commit, path, maxBytes)
        }
        val snapshot = ForgeGitPacketSnapshot(objects)
        return when (val result = snapshot.verify(bytes, repository, worktree)) {
            is ForgeGitPacketSnapshot.Result.Valid -> StepEvidenceObservation.Observed(mapOf(
                "repository" to result.repository,
                "commit" to result.commit,
                "manifestSha256" to result.sha256,
                "canonicalManifest" to result.canonicalManifest.toString(Charsets.UTF_8),
                "packetGeneration" to context.attemptId,
            ) + (caseBinding as? StepEvidenceObservation.Observed)?.facts.orEmpty())
            is ForgeGitPacketSnapshot.Result.Invalid -> when (result.code) {
                "GIT_COMMIT_UNAVAILABLE", "GIT_DOCUMENT_UNAVAILABLE", "GIT_SNAPSHOT_UNAVAILABLE" ->
                    StepEvidenceObservation.Unavailable(result.code, "The pinned Git packet could not be read.")
                else -> StepEvidenceObservation.Invalid(result.code, "The packet manifest does not match the committed documents.")
            }
        }
    }
}
