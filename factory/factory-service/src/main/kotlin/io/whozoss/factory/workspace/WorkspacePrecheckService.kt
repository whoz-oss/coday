package io.whozoss.factory.workspace

import io.whozoss.factory.persistence.TenantScope
import mu.KotlinLogging
import org.springframework.stereotype.Service

/**
 * Read-only workspace pre-check (Lot F, PRÉCONTRÔLE WORKSPACE).
 *
 * Run BEFORE/AT the creation of the run's root case, it verifies every
 * prerequisite a runnable worktree depends on:
 *
 *  1. the namespace is accessible to the caller;
 *  2. Git workspaces are enabled on the instance;
 *  3. the namespace has an adapted Git configuration (repository associated, the
 *     internal clone not failed);
 *  4. automatic worktree allocation for root cases is enabled;
 *  5. the workstream context is resolvable;
 *  6. every required agent is available, *when the read API exposes it* (see
 *     [WorkspacePrecheckPort.availableAgents] — a missing list is reported as
 *     unverified, never assumed satisfied).
 *
 * It is strictly read-only: it NEVER repairs the namespace and NEVER mutates any
 * configuration. A missing prerequisite fails fast with an actionable
 * [WorkspacePrecheckException] carrying a stable code, rather than letting the
 * run proceed and fail later against a directory that does not exist.
 */
@Service
class WorkspacePrecheckService(
    private val readPort: WorkspacePrecheckPort,
    private val contextPort: WorkspaceContextPort,
    private val properties: WorkspaceProperties = WorkspaceProperties(),
) {

    private val logger = KotlinLogging.logger {}

    /**
     * Runs every applicable check and returns the report. Throws
     * [WorkspacePrecheckException] on the FIRST unmet prerequisite.
     *
     * @param requiredAgents agent names the run needs; an empty list skips the
     *   agent check entirely.
     */
    fun precheck(
        scope: TenantScope,
        namespaceId: String,
        callerExternalUserId: String? = null,
        requiredAgents: List<String> = emptyList(),
    ): WorkspacePrecheckReport {
        if (!properties.enabled) {
            return WorkspacePrecheckReport(enabled = false, namespaceId = namespaceId, verified = emptyList(), unverified = emptyList())
        }
        val verified = ArrayList<String>()
        val unverified = ArrayList<String>()

        // 1. Namespace accessibility.
        if (!readPort.isNamespaceAccessible(namespaceId, callerExternalUserId)) {
            throw WorkspacePrecheckException(
                WorkspacePrecheckCodes.NAMESPACE_INACCESSIBLE,
                "The namespace '$namespaceId' is not accessible to the caller.",
                details = mapOf("namespaceId" to namespaceId, "remedy" to "Grant the caller READ access to the namespace."),
            )
        }
        verified += "namespace"

        // 2. Git workspaces enabled (Factory kill-switch).
        if (!properties.gitWorkspacesEnabled) {
            throw WorkspacePrecheckException(
                WorkspacePrecheckCodes.GIT_WORKSPACES_DISABLED,
                "Git workspaces are disabled; a run worktree cannot be provisioned.",
                details = mapOf("namespaceId" to namespaceId, "remedy" to "Enable git workspaces (agentos.git.workspaces.enabled)."),
            )
        }

        // 3. Git configuration adapted (association + checkout state).
        val git = readPort.namespaceGitState(namespaceId, callerExternalUserId)
        if (git == null || !git.gitAvailable) {
            throw WorkspacePrecheckException(
                WorkspacePrecheckCodes.GIT_WORKSPACES_DISABLED,
                "Git workspaces are not available for namespace '$namespaceId' (no Git plugin/association endpoint).",
                details = mapOf("namespaceId" to namespaceId, "remedy" to "Load the GIT plugin on the AgentOS instance."),
            )
        }
        verified += "gitWorkspaces"
        if (!git.associated) {
            throw WorkspacePrecheckException(
                WorkspacePrecheckCodes.GIT_CONFIG_MISSING,
                "The namespace '$namespaceId' has no repository associated; no worktree can be provisioned.",
                details = mapOf(
                    "namespaceId" to namespaceId,
                    "remedy" to "Associate a repository with the namespace (PUT /api/namespaces/{id}/git).",
                ),
            )
        }
        if (git.checkoutStatus?.equals("FAILED", ignoreCase = true) == true) {
            throw WorkspacePrecheckException(
                WorkspacePrecheckCodes.GIT_CONFIG_MISSING,
                "The Git checkout of namespace '$namespaceId' is FAILED: ${git.failureReason ?: "unknown cause"}.",
                details = mapOf(
                    "namespaceId" to namespaceId,
                    "checkoutStatus" to git.checkoutStatus,
                    "failureReason" to git.failureReason,
                    "remedy" to "Repair the namespace Git association and retry the checkout before running.",
                ),
            )
        }
        verified += "gitConfiguration"

        // 4. Automatic worktree allocation for root cases.
        if (!properties.autoWorktreeForRootCases) {
            throw WorkspacePrecheckException(
                WorkspacePrecheckCodes.AUTO_WORKTREE_DISABLED,
                "Automatic worktree allocation for root cases is disabled.",
                details = mapOf("namespaceId" to namespaceId, "remedy" to "Enable factory.workspace.auto-worktree-for-root-cases."),
            )
        }
        verified += "autoWorktree"

        // 5. Workstream context resolvable.
        if (!contextPort.isWorkstreamResolvable(scope)) {
            throw WorkspacePrecheckException(
                WorkspacePrecheckCodes.WORKSTREAM_UNRESOLVABLE,
                "The workstream '${scope.workstreamId}' of the run is not resolvable.",
                details = mapOf(
                    "organizationId" to scope.organizationId,
                    "workstreamId" to scope.workstreamId,
                    "remedy" to "Create the workstream or run under an existing one.",
                ),
            )
        }
        verified += "workstream"

        // 6. Required agents available (best-effort: only when the read API exposes them).
        val wanted = requiredAgents.mapNotNull { it.trim().takeIf(String::isNotEmpty) }.distinct()
        if (wanted.isNotEmpty()) {
            val available = readPort.availableAgents(namespaceId, callerExternalUserId)
            if (available == null) {
                unverified += "agents"
                logger.debug {
                    "Agent availability for namespace '$namespaceId' is not exposed by the read API; " +
                        "requiredAgents=$wanted are left unverified."
                }
            } else {
                val missing = wanted.filter { it !in available }
                if (missing.isNotEmpty()) {
                    throw WorkspacePrecheckException(
                        WorkspacePrecheckCodes.REQUIRED_AGENTS_MISSING,
                        "Required agent(s) ${missing.joinToString()} are not available in namespace '$namespaceId'.",
                        details = mapOf(
                            "namespaceId" to namespaceId,
                            "missingAgents" to missing,
                            "availableAgents" to available,
                            "remedy" to "Publish/enable the required agents in the namespace.",
                        ),
                    )
                }
                verified += "agents"
            }
        }

        logger.debug { "Workspace pre-check passed for namespace '$namespaceId' (verified=$verified, unverified=$unverified)" }
        return WorkspacePrecheckReport(enabled = true, namespaceId = namespaceId, verified = verified, unverified = unverified)
    }
}
