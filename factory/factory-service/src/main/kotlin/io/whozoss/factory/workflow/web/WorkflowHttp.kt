package io.whozoss.factory.workflow.web

import io.whozoss.factory.persistence.TenantScope
import io.whozoss.factory.web.AuthenticatedUser
import io.whozoss.factory.workflow.domain.WorkflowErrorCodes
import io.whozoss.factory.workflow.domain.workflowException
import io.whozoss.factory.workstream.WorkstreamService

/** Canonical HTTP success envelope: `{ "data": ... }`. */
data class WorkflowDataEnvelope<T>(
    val data: T,
)

/** The authenticated caller a workflow request is bound to. */
data class WorkflowCaller(
    val scope: TenantScope,
    val namespaceId: String,
    val actorId: String,
)

/** Namespace UUID accepted by the workflow HTTP boundary. */
private val NAMESPACE_ID = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-8][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$")

/** Whether [namespaceId] is a valid namespace UUID. */
fun isValidNamespaceId(namespaceId: String?): Boolean = namespaceId != null && NAMESPACE_ID.matches(namespaceId)

/**
 * Maps the authenticated user and the request's `namespaceId` (a body field, or a
 * query parameter on a GET) onto a [WorkflowCaller]. The only decision taken is
 * input validation: a missing namespace is `400 INVALID_NAMESPACE_ID`.
 */
fun resolveWorkflowCaller(
    user: AuthenticatedUser,
    namespaceId: String? = null,
    requireNamespace: Boolean = true,
    workstreamService: WorkstreamService? = null,
): WorkflowCaller {
    val resolvedNamespace = namespaceId?.trim()?.takeIf { it.isNotEmpty() }?.lowercase()
    if (!isValidNamespaceId(resolvedNamespace)) {
        throw workflowException(WorkflowErrorCodes.INVALID_NAMESPACE_ID, "A valid namespaceId is required.")
    }
    if (resolvedNamespace == null || workstreamService == null) {
        throw workflowException(WorkflowErrorCodes.INVALID_NAMESPACE_ID, "A valid namespaceId is required.")
    }
    val workstream = workstreamService.findByNamespace(user.organizationId, resolvedNamespace)
        ?: throw workflowException(
            WorkflowErrorCodes.WORKFLOW_NOT_FOUND,
            "No workstream is mapped to the requested namespace.",
        )
    return WorkflowCaller(TenantScope(user.organizationId, workstream.workstreamId), resolvedNamespace, user.userId)
}

/** Validates a required namespace query parameter. */
fun requireNamespaceId(namespaceId: String?): String {
    if (!isValidNamespaceId(namespaceId)) {
        throw workflowException(WorkflowErrorCodes.INVALID_NAMESPACE_ID, "A valid namespaceId query parameter is required.")
    }
    return namespaceId!!
}
