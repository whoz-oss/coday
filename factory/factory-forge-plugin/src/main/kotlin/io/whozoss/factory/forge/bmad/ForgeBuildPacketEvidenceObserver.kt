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

    override fun observe(context: StepEvidenceObservationContext): StepEvidenceObservation {
        val packet = context.run.domainState["approvedPacketCandidate"] as? Map<*, *>
            ?: return StepEvidenceObservation.Invalid("PACKET_IDENTITY_MISSING", "The approved packet is unavailable.")
        val repository = packet["repository"] as? String
        val commit = packet["commit"] as? String
        val digest = packet["manifestSha256"] as? String
        val canonical = packet["canonicalManifest"] as? String
        if (repository.isNullOrBlank() || commit.isNullOrBlank() || digest.isNullOrBlank() || canonical.isNullOrBlank()) {
            return StepEvidenceObservation.Invalid("PACKET_IDENTITY_MISSING", "The approved packet identity is incomplete.")
        }
        val reader = context.gitObjects
            ?: return StepEvidenceObservation.Unavailable("GIT_SOURCE_UNAVAILABLE", "The Run's Git objects cannot be read.")
        val source = reader.sourceRepository()
            ?: return StepEvidenceObservation.Unavailable("GIT_SOURCE_UNAVAILABLE", "The Run's Git source cannot be read.")
        if (source != repository) return StepEvidenceObservation.Invalid("PACKET_REPOSITORY_MISMATCH", "The source repository changed.")
        val objects = object : ForgeGitPacketSnapshot.GitObjects {
            override fun commitExists(worktree: Path, commit: String) = reader.commitExists(commit)
            override fun readBlob(worktree: Path, commit: String, path: String, maxBytes: Int) =
                reader.readBlob(commit, path, maxBytes)
        }
        return when (val verified = ForgeGitPacketSnapshot(objects).verify(
            canonical.toByteArray(Charsets.UTF_8), repository, Path.of(context.worktreePath),
        )) {
            is ForgeGitPacketSnapshot.Result.Valid ->
                if (verified.commit == commit && verified.sha256 == digest)
                    StepEvidenceObservation.Observed(mapOf("repository" to repository, "commit" to commit,
                        "manifestSha256" to digest))
                else StepEvidenceObservation.Invalid("PACKET_CHANGED", "The committed packet no longer matches the approval.")
            is ForgeGitPacketSnapshot.Result.Invalid -> when (verified.code) {
                "GIT_COMMIT_UNAVAILABLE", "GIT_DOCUMENT_UNAVAILABLE", "GIT_SNAPSHOT_UNAVAILABLE" ->
                    StepEvidenceObservation.Unavailable(verified.code, "The approved Git packet cannot be read.")
                else -> StepEvidenceObservation.Invalid(verified.code, "The approved Git packet is invalid.")
            }
        }
    }
}
