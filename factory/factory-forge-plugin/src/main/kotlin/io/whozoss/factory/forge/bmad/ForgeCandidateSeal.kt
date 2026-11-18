package io.whozoss.factory.forge.bmad

import com.fasterxml.jackson.databind.ObjectMapper
import java.security.MessageDigest

/** CE07 facts obtained by the host from Git, CI and deployment observation, not agent prose. */
internal object ForgeCandidateSeal {
    data class CodeTree(
        val repository: String,
        val branch: String,
        val commit: String,
        val clean: Boolean,
        val collisionFree: Boolean,
        val reviewed: Boolean,
        val verified: Boolean,
    )

    data class CiBuild(val repository: String, val commit: String, val artifactDigest: String)

    data class Deployment(
        val target: String,
        val repository: String,
        val commit: String,
        val artifactDigest: String,
        val receipt: String,
    )

    data class Candidate(
        val id: String,
        val generation: Long,
        val packetDigest: String,
        val repository: String,
        val branch: String,
        val commit: String,
        val buildDigest: String,
        val target: String,
        val deploymentReceipt: String,
    )

    sealed interface Result {
        data class Sealed(val candidate: Candidate) : Result
        data class Refused(val code: String) : Result
    }

    fun seal(
        runId: String,
        generation: Long,
        approvedPacketDigest: String,
        code: CodeTree,
        build: CiBuild,
        observed: Deployment,
    ): Result {
        if (runId.isBlank() || generation <= 0 || !SHA256.matches(approvedPacketDigest) ||
            code.repository.isBlank() || code.branch.isBlank() || !COMMIT_SHA.matches(code.commit) ||
            !SHA256.matches(build.artifactDigest) || observed.target.isBlank() || observed.receipt.isBlank()
        ) return Result.Refused("INCOMPLETE_CANDIDATE_IDENTITY")
        if (!code.clean || !code.collisionFree) return Result.Refused("UNSAFE_INTEGRATION_TREE")
        if (!code.reviewed || !code.verified) return Result.Refused("UNVERIFIED_INTEGRATION_TREE")
        if (build.repository != code.repository || build.commit != code.commit) return Result.Refused("CI_SOURCE_MISMATCH")
        if (observed.repository != code.repository || observed.commit != code.commit ||
            observed.artifactDigest != build.artifactDigest
        ) return Result.Refused("DEPLOYMENT_MISMATCH")

        val identity = listOf(runId, generation, approvedPacketDigest, code.repository, code.branch,
            code.commit, build.artifactDigest, observed.target, observed.receipt)
        val id = "candidate-" + sha256(ObjectMapper().writeValueAsBytes(identity)).take(32)
        return Result.Sealed(Candidate(id, generation, approvedPacketDigest, code.repository,
            code.branch, code.commit, build.artifactDigest, observed.target, observed.receipt))
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private val SHA256 = Regex("^[0-9a-f]{64}$")
    private val COMMIT_SHA = Regex("^[0-9a-f]{40}$")
}
