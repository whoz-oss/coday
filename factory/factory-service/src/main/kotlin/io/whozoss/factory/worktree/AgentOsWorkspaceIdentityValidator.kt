package io.whozoss.factory.worktree

import io.whozoss.factory.proxy.AgentOsProxyClient
import java.nio.file.Path
import org.springframework.stereotype.Service

class AgentOsWorkspaceIdentityValidationException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** Fail-closed comparison of the persisted local worktree with AgentOS' equipped READY workspace. */
@Service
class AgentOsWorkspaceIdentityValidator(
    private val proxy: AgentOsProxyClient,
    private val identities: LocalWorktreeResourceIdentityResolver = LocalWorktreeResourceIdentityResolver(),
) {
    fun validate(
        caseId: String,
        persistedWorktreePath: Path,
        externalUserId: String?,
    ): String {
        val expected = resolve("Persisted worktree", persistedWorktreePath)
        val raw = try {
            proxy.fetchWorkspaceChanges(caseId, externalUserId)
        } catch (error: Exception) {
            throw AgentOsWorkspaceIdentityValidationException("AgentOS workspace could not be read", error)
        } ?: throw AgentOsWorkspaceIdentityValidationException("AgentOS workspace was not found")

        if (raw["equipped"] != true) {
            throw AgentOsWorkspaceIdentityValidationException("AgentOS workspace is not equipped")
        }
        val status = raw["status"] as? String
        if (status != READY_STATUS) {
            throw AgentOsWorkspaceIdentityValidationException("AgentOS workspace is not ready")
        }
        val rawPath = (raw["path"] as? String)?.takeIf { it.isNotBlank() }
            ?: throw AgentOsWorkspaceIdentityValidationException("AgentOS workspace path is absent")
        val observed = try {
            Path.of(rawPath)
        } catch (error: Exception) {
            throw AgentOsWorkspaceIdentityValidationException("AgentOS workspace path is invalid", error)
        }
        val actual = resolve("AgentOS workspace", observed)
        if (actual != expected) {
            throw AgentOsWorkspaceIdentityValidationException("AgentOS workspace does not match the persisted worktree")
        }
        return expected
    }

    private fun resolve(label: String, path: Path): String = try {
        identities.resolve(path)
    } catch (error: Exception) {
        throw AgentOsWorkspaceIdentityValidationException("$label cannot be resolved locally", error)
    }

    companion object {
        // Existing /workspace/changes contract consumed by WorkspaceDeltaCollector.
        const val READY_STATUS = "READY"
    }
}
