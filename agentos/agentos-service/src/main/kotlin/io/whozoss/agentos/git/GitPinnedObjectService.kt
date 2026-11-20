package io.whozoss.agentos.git

import io.whozoss.agentos.exception.BadRequestException
import io.whozoss.agentos.exception.ConflictException
import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.git.core.GitCommandException
import io.whozoss.agentos.git.core.GitCommandRunner
import io.whozoss.agentos.git.core.GitInvocation
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service
import java.time.Duration

/**
 * Reads immutable Git objects from an authorized Case family's equipped workspace.
 * The caller supplies only a commit ID and repository-relative path; the repository
 * and Git directories always come from the persisted Case binding.
 */
@Service
@ConditionalOnProperty(prefix = "agentos.git.workspaces", name = ["enabled"], havingValue = "true")
class GitPinnedObjectService(
    private val runner: GitCommandRunner,
    private val status: GitWorkspaceStatusService,
) {
    fun requireCommit(root: GitExchangeRoot, commit: String) {
        requireCommitId(commit)
        // This Case may share a Git object database with other worktrees. Only
        // commits in its own current history are valid packet revisions.
        read(root, listOf("merge-base", "--is-ancestor", commit, "HEAD"), 0)
        read(root, listOf("cat-file", "-e", "$commit^{commit}"), 0)
    }

    fun readBlob(root: GitExchangeRoot, commit: String, path: String): ByteArray {
        requireCommitId(commit)
        if (!safePath(path)) throw BadRequestException("A safe repository-relative path is required")
        requireCommit(root, commit)
        return read(root, listOf("cat-file", "blob", "$commit:$path"), MAX_BLOB_BYTES)
    }

    private fun read(root: GitExchangeRoot, args: List<String>, maxBytes: Int): ByteArray {
        val binding = root.binding ?: throw ResourceNotFoundException("This case has no repository")
        if (binding.status != CaseResourceStatus.READY) throw ConflictException("The worktree is not available yet")
        val path = root.repositoryPath.toAbsolutePath().normalize()
        return try {
            runner.runBinaryOrThrow(
                GitInvocation(
                    args = args,
                    gitDir = status.worktreeGitDir(binding),
                    workTree = path,
                    workingDirectory = path,
                    timeout = Duration.ofSeconds(10),
                ),
                maxBytes,
            )
        } catch (_: GitCommandException) {
            throw ConflictException("The pinned Git object is unavailable or exceeds the read limit")
        }
    }

    private fun requireCommitId(commit: String) {
        if (!COMMIT_SHA.matches(commit)) throw BadRequestException("A full commit SHA-1 is required")
    }

    private fun safePath(path: String): Boolean = path.isNotBlank() && path.length <= 1_024 &&
        !path.startsWith('/') && !path.contains('\\') && !path.contains(':') && !path.contains('\u0000') &&
        path.split('/').all { it.isNotEmpty() && it != "." && it != ".." }

    private companion object {
        val COMMIT_SHA = Regex("^[0-9a-f]{40}$")
        const val MAX_BLOB_BYTES = 16 * 1024 * 1024
    }
}
