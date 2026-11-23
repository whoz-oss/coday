package io.whozoss.factory.forge.bmad

import io.whozoss.factory.sdk.spi.FactoryStepEvidenceObserver
import io.whozoss.factory.sdk.spi.StepEvidenceObservation
import io.whozoss.factory.sdk.spi.StepEvidenceObservationContext
import org.pf4j.Extension
import java.nio.file.Path

/** Re-reads the approved packet's committed Git objects before Build can pass. */
@Extension
class ForgeBuildPacketEvidenceObserver : FactoryStepEvidenceObserver {
    override fun supports(flowType: String, stepId: String, agentName: String): Boolean =
        flowType == "forge.delivery" && stepId == "implementation-build" && agentName == "ForgeDeveloper"

    override fun requiresBeforeSideEffect(flowType: String, stepId: String): Boolean =
        flowType == "forge.delivery" && stepId in setOf("packet-approval", "implementation-build")

    override fun observeBeforeSideEffect(context: StepEvidenceObservationContext): StepEvidenceObservation {
        if (context.gitObjects == null) return StepEvidenceObservation.Unavailable(
            "GIT_SOURCE_UNAVAILABLE", "Trusted Case Git is unavailable.",
        )
        val verified = verify(context = context, requireAcceptedDesign = true)
        if (context.stepId != "implementation-build" || verified !is StepEvidenceObservation.Observed) return verified
        val approval = context.run.humanDecisions.lastOrNull {
            it["stepId"] == "packet-approval" && (it["facts"] as? Map<*, *>)?.get("gate") == "packet-approval"
        }
        val source = approval?.get("source") as? Map<*, *>
        val facts = approval?.get("facts") as? Map<*, *>
        return if (approval?.get("outcome") == "pass" && source?.get("kind") == "factory-human" &&
            !(source["actorId"] as? String).isNullOrBlank() && !(facts?.get("decidedAt") as? String).isNullOrBlank() &&
            facts?.get("trustedCheckpointEvidence") == verified.facts && verified.facts.all { (key, value) -> facts?.get(key) == value }
        ) verified else StepEvidenceObservation.Invalid("PACKET_APPROVAL_MISSING_OR_STALE", "Build requires a human approval of the reverified packet.")
    }

    override fun observe(context: StepEvidenceObservationContext): StepEvidenceObservation {
        val packet = context.run.domainState["approvedPacketCandidate"] as? Map<*, *>
        if (packet?.get("packetGeneration") == null) return verify(context = context, requireAcceptedDesign = false)
        return when (val checked = observeBeforeSideEffect(context)) {
            is StepEvidenceObservation.Invalid -> StepEvidenceObservation.Unavailable(checked.code, checked.message)
            else -> checked
        }
    }

    private fun verify(context: StepEvidenceObservationContext, requireAcceptedDesign: Boolean): StepEvidenceObservation {
        val route = context.run.domainState["repositoryRouting"] as? Map<*, *>
        if (route != null) {
            val caseWrite = route["case_write"] as? Map<*, *>
                ?: return StepEvidenceObservation.Invalid("CASE_BINDING_INVALID", "The Case write binding is invalid.")
            val suppliedRoot = runCatching { Path.of(context.worktreePath).toRealPath() }.getOrNull()
                ?: return StepEvidenceObservation.Unavailable("WORKTREE_UNAVAILABLE", "The Run-bound Case path cannot be resolved.")
            val git = ProcessGitObjects()
            val gitRoot = git.checkoutRoot(suppliedRoot)
                ?: return StepEvidenceObservation.Unavailable("GIT_SOURCE_UNAVAILABLE", "The Run-bound Case Git root cannot be observed.")
            if (gitRoot != suppliedRoot || caseWrite["working_path"] != gitRoot.toString() ||
                caseWrite["location"] != "CASE_EXCHANGE" || caseWrite["relative_path"] != "repo"
            ) return StepEvidenceObservation.Invalid("CASE_BINDING_MISMATCH", "Build is not running in the preflight-bound Case repo/ root.")
            val observedRepository = (context.gitObjects?.sourceRepository() ?: git.sourceRepository(gitRoot))?.uppercase()
            if (observedRepository != caseWrite["repository"]) {
                return StepEvidenceObservation.Invalid("CASE_BINDING_MISMATCH", "The Case repository changed since preflight.")
            }
            if (context.report.isEmpty() && git.headCommit(gitRoot) != caseWrite["revision"]) {
                return StepEvidenceObservation.Invalid("CASE_REVISION_DRIFT", "The Case revision changed before packet approval or Build dispatch.")
            }
        }
        val packet = context.run.domainState["approvedPacketCandidate"] as? Map<*, *>
            ?: return StepEvidenceObservation.Invalid("PACKET_IDENTITY_MISSING", "The approved packet is unavailable.")
        val repository = packet["repository"] as? String
        val commit = packet["commit"] as? String
        val digest = packet["manifestSha256"] as? String
        val canonical = packet["canonicalManifest"] as? String
        val generation = packet["packetGeneration"] as? String
        if (requireAcceptedDesign && generation.isNullOrBlank()) {
            return StepEvidenceObservation.Invalid("PACKET_GENERATION_MISSING", "Reassemble the packet to establish its generation.")
        }
        if (repository.isNullOrBlank() || commit.isNullOrBlank() || digest.isNullOrBlank() || canonical.isNullOrBlank()) {
            return StepEvidenceObservation.Invalid("PACKET_IDENTITY_MISSING", "The approved packet identity is incomplete.")
        }
        val reader = context.gitObjects
        val worktree = Path.of(context.worktreePath)
        val local = ProcessGitObjects()
        val source = (if (reader != null) reader.sourceRepository() else local.sourceRepository(worktree))
            ?: return StepEvidenceObservation.Unavailable("GIT_SOURCE_UNAVAILABLE", "The Run's Git source cannot be read.")
        if (source != repository) return StepEvidenceObservation.Invalid("PACKET_REPOSITORY_MISMATCH", "The source repository changed.")
        val objects = if (reader == null) local else object : ForgeGitPacketSnapshot.GitObjects {
            override fun commitExists(worktree: Path, commit: String) = reader.commitExists(commit)
            override fun readBlob(worktree: Path, commit: String, path: String, maxBytes: Int) =
                reader.readBlob(commit, path, maxBytes)
        }
        return when (val verified = ForgeGitPacketSnapshot(objects).verify(
            rawManifest = canonical.toByteArray(Charsets.UTF_8), expectedRepository = repository, worktree = worktree,
            acceptedDesign = context.run.domainState.takeIf { requireAcceptedDesign },
        )) {
            is ForgeGitPacketSnapshot.Result.Valid ->
                if (verified.commit == commit && verified.sha256 == digest)
                    StepEvidenceObservation.Observed(mapOf("repository" to repository, "commit" to commit,
                        "manifestSha256" to digest) + generation?.let { mapOf("packetGeneration" to it) }.orEmpty())
                else StepEvidenceObservation.Invalid("PACKET_CHANGED", "The committed packet no longer matches the approval.")
            is ForgeGitPacketSnapshot.Result.Invalid -> when (verified.code) {
                "GIT_COMMIT_UNAVAILABLE", "GIT_DOCUMENT_UNAVAILABLE", "GIT_SNAPSHOT_UNAVAILABLE" ->
                    StepEvidenceObservation.Unavailable(verified.code, "The approved Git packet cannot be read.")
                else -> StepEvidenceObservation.Invalid(verified.code, "The approved Git packet is invalid.")
            }
        }
    }
}
