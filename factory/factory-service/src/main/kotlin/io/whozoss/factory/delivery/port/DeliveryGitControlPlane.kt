package io.whozoss.factory.delivery.port

import org.springframework.stereotype.Component
import java.security.MessageDigest

/**
 * Git control-plane adapter port for delivery operations.
 *
 * Port of `factory/src/adapters/delivery/delivery-git-control-plane.ts`: the
 * delivery controller asserts the worktree binding and delegates every git
 * mutation here, journalling the outcome. Implementations may shell out to git;
 * the default [StubDeliveryGitControlPlane] is deterministic and filesystem-free.
 */

/** A worktree binding asserted before any git mutation. */
data class DeliveryGitWorktreeBinding(
    val worktreePath: String,
    val branch: String,
    val baseCommit: String,
    val expectedHead: String,
)

/** The verified result of a worktree inspection. */
data class DeliveryGitInspection(
    val worktreePath: String,
    val branch: String,
    val headCommit: String,
    val files: List<String>,
    val diffHash: String,
)

/** Result of a checkpoint commit. */
data class DeliveryGitCheckpointResult(
    val changed: Boolean,
    val commit: String,
    val previousHead: String?,
    val inspection: DeliveryGitInspection,
)

/** Result of a push. */
sealed interface DeliveryGitPushResult {
    val headCommit: String

    data class Ok(
        override val headCommit: String,
        val changed: Boolean,
        val previousRemoteHead: String? = null,
    ) : DeliveryGitPushResult

    data class Blocked(val errorCode: String) : DeliveryGitPushResult {
        override val headCommit: String get() = ""
    }
}

/** Git operations the delivery controller depends on. */
interface DeliveryGitControlPlane {
    /** The configured remote name, or `null` when push is unavailable. */
    val remote: String?

    /** Inspects the binding and creates a checkpoint commit when there are changes. */
    fun checkpoint(
        binding: DeliveryGitWorktreeBinding,
        message: String,
        claims: Map<String, Any?>?,
    ): DeliveryGitCheckpointResult

    /** Pushes the binding's branch to the configured remote. */
    fun push(binding: DeliveryGitWorktreeBinding): DeliveryGitPushResult
}

/**
 * Deterministic, filesystem-free [DeliveryGitControlPlane].
 *
 * Derives stable commit hashes from the binding identity so integration tests
 * observe a plausible checkpoint/push outcome without touching the machine's
 * real git repository. Production deployments can supply a real implementation
 * and mark it `@Primary`.
 */
@Component
class StubDeliveryGitControlPlane : DeliveryGitControlPlane {

    override val remote: String? = "origin"

    override fun checkpoint(
        binding: DeliveryGitWorktreeBinding,
        message: String,
        claims: Map<String, Any?>?,
    ): DeliveryGitCheckpointResult {
        val inspection = inspect(binding)
        val commit = syntheticCommit("checkpoint:${binding.expectedHead}:$message")
        return DeliveryGitCheckpointResult(
            changed = true,
            commit = commit,
            previousHead = binding.expectedHead,
            inspection = inspection,
        )
    }

    override fun push(binding: DeliveryGitWorktreeBinding): DeliveryGitPushResult =
        DeliveryGitPushResult.Ok(headCommit = binding.expectedHead, changed = true)

    private fun inspect(binding: DeliveryGitWorktreeBinding): DeliveryGitInspection = DeliveryGitInspection(
        worktreePath = binding.worktreePath,
        branch = binding.branch,
        headCommit = binding.expectedHead,
        files = emptyList(),
        diffHash = "sha256:${rawSha256("diff:${binding.expectedHead}")}",
    )

    private fun syntheticCommit(seed: String): String = rawSha256(seed)

    private fun rawSha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(40)
    }
}
