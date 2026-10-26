package io.whozoss.factory.web

import io.whozoss.factory.error.FactoryException
import io.whozoss.factory.persistence.TenantScope

/** Canonical HTTP success envelope `{ "data": ... }` for the surfaces that use it. */
data class FactoryDataEnvelope<T>(val data: T)

/** The authenticated caller: its tenant scope and the id it acts as. */
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

/** Build a business caller only after a concrete workstream was resolved. */
fun resolveFactoryCaller(user: AuthenticatedUser, scope: TenantScope): FactoryCaller {
    require(scope.organizationId == user.organizationId) { "Resolved scope belongs to another organization" }
    return FactoryCaller(scope, user.userId, user.userId, user.userId)
}

/** Organization-only caller used by relays and organization-level definitions. */
fun resolveOrganizationCaller(user: AuthenticatedUser): OrganizationCaller =
    OrganizationCaller(user.organizationId, user.userId, user.userId)

data class OrganizationCaller(
    val organizationId: String,
    val actorId: String,
    val externalUserId: String?,
)

/** Require the `namespaceId` query parameter exactly like the Node routes. */
fun requireNamespaceQuery(namespaceId: String?): String =
    namespaceId?.takeIf { it.isNotBlank() }
        ?: factoryError(400, "MISSING_NAMESPACE_ID", "namespaceId query param is required")

/** Map a storage failure to the Node `FORGE_STORAGE_FAILURE` (500). */
fun factoryStorageFailure(error: Throwable): Nothing =
    factoryError(500, "FORGE_STORAGE_FAILURE", error.message ?: error.toString())
