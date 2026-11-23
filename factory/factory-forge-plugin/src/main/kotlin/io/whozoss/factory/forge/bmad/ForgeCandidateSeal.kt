package io.whozoss.factory.forge.bmad

import com.fasterxml.jackson.databind.ObjectMapper
import java.security.MessageDigest

/**
 * CE07: historical successful deployment of an integration tag containing this
 * Run's work commit. Facts come from trusted Git/CI readers, not agent prose.
 * The tag is a reference shown to the human, not proof of what they tested.
 */
internal object ForgeCandidateSeal {
    data class CodeTree(
        val repository: String,
        val workBranch: String,
        val workCommit: String,
        val integrationBranch: String,
        val integrationCommit: String,
        val workCommitIncluded: Boolean,
        val clean: Boolean,
        val collisionFree: Boolean,
        val reviewed: Boolean,
        val verified: Boolean,
    )

    data class CiBuild(
        val repository: String,
        val integrationCommit: String,
        val tag: String,
        val tagCommit: String,
        val successful: Boolean,
    )

    data class Deployment(
        val target: String,
        val tag: String,
        val successful: Boolean,
        val receipt: String,
    )

    data class Candidate(
        val id: String,
        val generation: Long,
        val packetDigest: String,
        val repository: String,
        val workBranch: String,
        val workCommit: String,
        val integrationBranch: String,
        val integrationCommit: String,
        val deployedTag: String,
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
        deployment: Deployment,
    ): Result {
        if (runId.isBlank() || generation <= 0 || !SHA256.matches(approvedPacketDigest) ||
            code.repository.isBlank() || code.workBranch.isBlank() || code.integrationBranch.isBlank() ||
            !COMMIT_SHA.matches(code.workCommit) || !COMMIT_SHA.matches(code.integrationCommit) ||
            build.tag.isBlank() || deployment.target.isBlank() || deployment.receipt.isBlank()
        ) return Result.Refused("INCOMPLETE_CANDIDATE_IDENTITY")
        if (!code.clean || !code.collisionFree) return Result.Refused("UNSAFE_INTEGRATION_TREE")
        if (!code.reviewed || !code.verified) return Result.Refused("UNVERIFIED_INTEGRATION_TREE")
        if (!code.workCommitIncluded) return Result.Refused("WORK_COMMIT_NOT_INTEGRATED")
        if (!build.successful || build.repository != code.repository ||
            build.integrationCommit != code.integrationCommit || build.tagCommit != code.integrationCommit
        ) return Result.Refused("CI_SOURCE_MISMATCH")
        if (!deployment.successful) return Result.Refused("DEPLOYMENT_UNSUCCESSFUL")
        if (deployment.tag != build.tag) return Result.Refused("DEPLOYMENT_MISMATCH")

        val identity = listOf(runId, generation, approvedPacketDigest, code.repository,
            code.workBranch, code.workCommit, code.integrationBranch, code.integrationCommit,
            build.tag, deployment.target, deployment.receipt)
        val id = "candidate-" + sha256(ObjectMapper().writeValueAsBytes(identity)).take(32)
        return Result.Sealed(Candidate(id, generation, approvedPacketDigest, code.repository,
            code.workBranch, code.workCommit, code.integrationBranch, code.integrationCommit,
            build.tag, deployment.target, deployment.receipt))
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private val SHA256 = Regex("^[0-9a-f]{64}$")
    private val COMMIT_SHA = Regex("^[0-9a-f]{40}$")
}
