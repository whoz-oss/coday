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

/** Maps the authenticated user onto the caller shape the services expect. No decision is taken here. */
fun resolveFactoryCaller(user: AuthenticatedUser): FactoryCaller =
    FactoryCaller(user.scope, user.userId, user.userId, user.userId)

/** Require the `namespaceId` query parameter exactly like the Node routes. */
fun requireNamespaceQuery(namespaceId: String?): String =
    namespaceId?.takeIf { it.isNotBlank() }
        ?: factoryError(400, "MISSING_NAMESPACE_ID", "namespaceId query param is required")

/** Map a storage failure to the Node `FORGE_STORAGE_FAILURE` (500). */
fun factoryStorageFailure(error: Throwable): Nothing =
    factoryError(500, "FORGE_STORAGE_FAILURE", error.message ?: error.toString())
