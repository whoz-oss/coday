package io.whozoss.factory.environment.port

import io.whozoss.factory.environment.domain.WorkEnvironment
import io.whozoss.factory.environment.domain.WorkEnvironmentState
import org.springframework.stereotype.Component
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest

/**
 * Deterministic, filesystem-free [GitProvisioner] used by default (and by tests).
 *
 * It never touches the machine's real Git repository: it derives a stable
 * virtual worktree path from the environment identity, a fixed base commit and
 * a deterministic head commit. Production deployments can supply a real
 * implementation and mark it `@Primary`.
 */
@Component
class FakeGitProvisioner : GitProvisioner {

    private val root: Path = defaultRoot()

    override fun provisionWorktree(request: GitProvisionRequest): GitProvisionFacts {
        val worktreePath = root.resolve(sanitize(request.environmentId)).normalize().toString()
        return GitProvisionFacts(
            repoRoot = root.resolve("repo").normalize().toString(),
            worktreePath = worktreePath,
            baseCommit = BASE_COMMIT,
            headCommit = syntheticCommit("head:${request.environmentId}:${request.branch}"),
        )
    }

    override fun reconcile(environment: WorkEnvironment): GitReconciliation {
        if (environment.lifecycleState == WorkEnvironmentState.DECOMMISSIONED) return GitReconciliation.Absent
        return GitReconciliation.Owned(
            headCommit = syntheticCommit("head:${environment.environmentId}:${environment.branch}"),
            baseCommit = environment.baseCommit ?: BASE_COMMIT,
        )
    }

    override fun removeWorktree(environment: WorkEnvironment) {
        // The fake provisioner owns no real filesystem resource.
    }

    private fun syntheticCommit(seed: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(seed.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun sanitize(value: String): String = value.replace(Regex("[^A-Za-z0-9._-]"), "_")

    companion object {
        /** A syntactically valid 40-hex base commit (never resolved against a real repo). */
        val BASE_COMMIT: String = "0".repeat(40)

        private fun defaultRoot(): Path =
            Paths.get(System.getProperty("java.io.tmpdir"), "factory-service", "worktrees")
    }
}
