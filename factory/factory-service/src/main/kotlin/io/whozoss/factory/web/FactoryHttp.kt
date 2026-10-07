package io.whozoss.factory.web

import io.whozoss.factory.error.FactoryException
import io.whozoss.factory.persistence.TenantScope
import io.whozoss.factory.persistence.TenantScopeProvider

/** Canonical HTTP success envelope `{ "data": ... }` for the surfaces that use it. */
data class FactoryDataEnvelope<T>(val data: T)

/** The trusted caller identity + optional AgentOS relay identity. */
data class FactoryCaller(
    val scope: TenantScope,
    val actorId: String,
    val authorityId: String,
    val externalUserId: String?,
)

/** A Factory HTTP failure carrying an explicit status and machine code. */
class FactoryHttpException(
    statusCode: Int,
    errorCode: String,
    message: String,
    details: Any? = null,
    cause: Throwable? = null,
) : FactoryException(statusCode, errorCode, message, details, cause)

/** Throw a Factory HTTP failure. */
fun factoryError(statusCode: Int, code: String, message: String, details: Any? = null): Nothing =
    throw FactoryHttpException(statusCode, code, message, details)

/**
 * Resolves the trusted caller from the verified [TrustContext], failing closed
 * with `401 TRUST_CONTEXT_UNAVAILABLE`. The organization/workstream scope and
 * the relay identity are never read from client input.
 */
fun resolveFactoryCaller(trustContext: TrustContext?, tenantScopeProvider: TenantScopeProvider): FactoryCaller {
    val scope = tenantScopeProvider.scopeOf(trustContext)
        ?: factoryError(401, "TRUST_CONTEXT_UNAVAILABLE", "Trust context unavailable")
    val actorId = trustContext?.principalId?.takeIf { it.isNotBlank() } ?: "factory-operator"
    val authorityId = trustContext?.serviceIdentityId?.takeIf { it.isNotBlank() } ?: actorId
    return FactoryCaller(scope, actorId, authorityId, trustContext?.principalId)
}

/** Require the `namespaceId` query parameter exactly like the Node routes. */
fun requireNamespaceQuery(namespaceId: String?): String =
    namespaceId?.takeIf { it.isNotBlank() }
        ?: factoryError(400, "MISSING_NAMESPACE_ID", "namespaceId query param is required")

/** Map a storage failure to the Node `FORGE_STORAGE_FAILURE` (500). */
fun factoryStorageFailure(error: Throwable): Nothing =
    factoryError(500, "FORGE_STORAGE_FAILURE", error.message ?: error.toString())
