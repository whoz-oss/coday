package io.whozoss.factory.workflow.web

import io.whozoss.factory.persistence.TenantScope
import io.whozoss.factory.persistence.TenantScopeProvider
import io.whozoss.factory.web.TrustContext
import io.whozoss.factory.workflow.domain.WorkflowErrorCodes
import io.whozoss.factory.workflow.domain.workflowException

/** Canonical HTTP success envelope: `{ "data": ... }`. */
data class WorkflowDataEnvelope<T>(
    val data: T,
)

/** The trusted caller identity a workflow request is bound to. */
data class WorkflowCaller(
    val scope: TenantScope,
    val namespaceId: String,
    val caseId: String?,
    val actorId: String,
)

/** Namespace UUID accepted by the trusted workflow HTTP boundary. */
private val NAMESPACE_ID = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-8][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$")
private val SAFE_ACTOR = Regex("^[A-Za-z0-9][A-Za-z0-9._:@-]{0,255}$")

/** Whether [namespaceId] is a valid namespace UUID. */
fun isValidNamespaceId(namespaceId: String?): Boolean = namespaceId != null && NAMESPACE_ID.matches(namespaceId)

/** Whether [actorId] is a machine-safe actor attribution. */
fun isSafeActor(actorId: String?): Boolean = actorId != null && SAFE_ACTOR.matches(actorId)

/**
 * Resolves the trusted caller identity from the verified [TrustContext], or
 * fails closed with `401 TRUST_CONTEXT_UNAVAILABLE`. The organization/workstream
 * scope is never read from client input; the namespace comes from the query
 * parameter when supplied, otherwise from the trusted context.
 */
fun resolveWorkflowCaller(
    trustContext: TrustContext?,
    tenantScopeProvider: TenantScopeProvider,
    namespaceId: String? = null,
    requireNamespace: Boolean = true,
): WorkflowCaller {
    val scope = tenantScopeProvider.scopeOf(trustContext)
        ?: throw workflowException(
            WorkflowErrorCodes.TRUST_CONTEXT_UNAVAILABLE,
            "Trust context unavailable",
        )
    val resolvedNamespace = namespaceId?.takeIf { it.isNotBlank() }
        ?: trustContext?.namespaceId?.takeIf { it.isNotBlank() }
    if (requireNamespace && resolvedNamespace == null) {
        throw workflowException(WorkflowErrorCodes.INVALID_NAMESPACE_ID, "A valid namespaceId is required.")
    }
    val actorId = trustContext?.principalId?.takeIf { isSafeActor(it) } ?: "factory-operator"
    return WorkflowCaller(scope, resolvedNamespace ?: "", trustContext?.caseId, actorId)
}

/** Validates a required namespace query parameter. */
fun requireNamespaceId(namespaceId: String?): String {
    if (!isValidNamespaceId(namespaceId)) {
        throw workflowException(WorkflowErrorCodes.INVALID_NAMESPACE_ID, "A valid namespaceId query parameter is required.")
    }
    return namespaceId!!
}
