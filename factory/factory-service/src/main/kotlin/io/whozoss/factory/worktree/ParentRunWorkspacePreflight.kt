package io.whozoss.factory.worktree

import io.whozoss.factory.adapter.agentos.TrustedCaseBinding
import io.whozoss.factory.agentattempt.domain.DurableAgentAttempt
import io.whozoss.factory.environment.persistence.WorkEnvironmentRepository
import io.whozoss.factory.persistence.TenantScope
import io.whozoss.factory.workflow.domain.WorkflowInstanceRecord
import java.nio.file.Path

/** Shared fail-closed workspace preflight for a new turn belonging to a parent Run. */
class ParentRunWorkspacePreflight(
    private val environments: WorkEnvironmentRepository?,
    private val validator: AgentOsWorkspaceIdentityValidator?,
) {
    fun validate(
        scope: TenantScope,
        namespaceId: String,
        instance: WorkflowInstanceRecord?,
        attempt: DurableAgentAttempt,
        binding: TrustedCaseBinding,
    ) {
        if (parentRunIdOf(instance) == null) return
        val workspaceValidator = validator
            ?: error("AgentOS workspace identity validator is required for parent Run fragments")
        val repository = environments
            ?: error("Work environment repository is required for parent Run fragments")
        val environmentRef = attempt.environmentRef
            ?: error("Parent Run attempt has no captured environmentRef")
        val environment = repository.findByEnvironmentId(scope, environmentRef)
            ?: error("Captured work environment '$environmentRef' is unavailable")
        if (environment.revision != attempt.expectedEnvironmentRevision) error("Captured work environment revision is stale")
        if (environment.namespaceId != namespaceId) error("Captured work environment namespace mismatch")
        if (environment.parentCaseId != instance?.rootCaseId) error("Captured work environment root case mismatch")
        workspaceValidator.validate(binding.caseId, Path.of(environment.worktreePath), binding.externalUserId)
    }

    private fun parentRunIdOf(instance: WorkflowInstanceRecord?): String? {
        if (instance == null) return null
        (instance.instance["runId"] as? String)?.takeIf { it.isNotBlank() }?.let { return it }
        val relations = instance.instance["relations"] as? Map<*, *>
        return (relations?.get("runId") as? String)?.takeIf { it.isNotBlank() }
    }
}
