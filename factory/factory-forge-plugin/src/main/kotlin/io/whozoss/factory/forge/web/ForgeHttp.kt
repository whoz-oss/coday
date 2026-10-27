package io.whozoss.factory.forge.web

import io.whozoss.factory.web.AuthenticatedUser
import io.whozoss.factory.web.factoryError
import io.whozoss.factory.web.factoryStorageFailure

/** Canonical HTTP success envelope for Forge surfaces. */
data class ForgeDataEnvelope<T>(val data: T)

/** Relay and audit identity. Ledger handlers resolve their namespace separately. */
data class ForgeCaller(
    val organizationId: String,
    val actorId: String,
    val authorityId: String,
    val externalUserId: String,
)

fun forgeError(statusCode: Int, code: String, message: String, details: Any? = null): Nothing =
    factoryError(statusCode, code, message, details)

/** Authentication never invents a ledger's workstream. */
fun resolveForgeCaller(user: AuthenticatedUser): ForgeCaller =
    ForgeCaller(user.organizationId, user.userId, user.userId, user.userId)

fun forgeStorageFailure(error: Throwable): Nothing = factoryStorageFailure(error)
