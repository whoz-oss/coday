package io.whozoss.factory.forge.web

import io.whozoss.factory.error.FactoryException
import io.whozoss.factory.persistence.TenantScope
import io.whozoss.factory.persistence.TenantScopeProvider
import io.whozoss.factory.web.TrustContext

/** Canonical HTTP success envelope `{ "data": ... }` for the surfaces that use it. */
data class ForgeDataEnvelope<T>(val data: T)

/** The trusted Forge caller identity + AgentOS relay identity. */
data class ForgeCaller(
    val scope: TenantScope,
    val actorId: String,
    val authorityId: String,
    val externalUserId: String?,
)

/** A Forge HTTP failure carrying an explicit status and machine code. */
class ForgeHttpException(
    statusCode: Int,
    errorCode: String,
    message: String,
    details: Any? = null,
    cause: Throwable? = null,
) : FactoryException(statusCode, errorCode, message, details, cause)

/** Throw a Forge HTTP failure. */
fun forgeError(statusCode: Int, code: String, message: String, details: Any? = null): Nothing =
    throw ForgeHttpException(statusCode, code, message, details)

/**
 * Resolves the trusted caller from the verified [TrustContext], failing closed
 * with `401 TRUST_CONTEXT_UNAVAILABLE`. The organization/workstream scope and
 * the relay identity are never read from client input.
 */
fun resolveForgeCaller(trustContext: TrustContext?, tenantScopeProvider: TenantScopeProvider): ForgeCaller {
    val scope = tenantScopeProvider.scopeOf(trustContext)
        ?: forgeError(401, "TRUST_CONTEXT_UNAVAILABLE", "Trust context unavailable")
    val actorId = trustContext?.principalId?.takeIf { it.isNotBlank() } ?: "factory-operator"
    val authorityId = trustContext?.serviceIdentityId?.takeIf { it.isNotBlank() } ?: actorId
    return ForgeCaller(scope, actorId, authorityId, trustContext?.principalId)
}

/** Require the `namespaceId` query parameter exactly like the Node routes. */
fun requireNamespaceQuery(namespaceId: String?): String =
    namespaceId?.takeIf { it.isNotBlank() }
        ?: forgeError(400, "MISSING_NAMESPACE_ID", "namespaceId query param is required")

/** Map a storage failure to the Node `FORGE_STORAGE_FAILURE` (500). */
fun forgeStorageFailure(error: Throwable): Nothing =
    forgeError(500, "FORGE_STORAGE_FAILURE", error.message ?: error.toString())
