package io.whozoss.factory.forge.web

import io.whozoss.factory.forge.port.ForgePluginHttpException
import io.whozoss.factory.sdk.spi.FactoryCallerIdentity

/** Canonical HTTP success envelope for Forge surfaces. */
data class ForgeDataEnvelope<T>(val data: T)

/** Canonical HTTP error envelope matching `{ "error": { "code", "message", "details" } }`. */
data class ForgeErrorDetail(
    val code: String,
    val message: String,
    val details: Any? = null,
)

data class ForgeErrorEnvelope(
    val error: ForgeErrorDetail,
)

/** Relay and audit identity resolved from [FactoryCallerIdentity]. */
data class ForgeCaller(
    val organizationId: String,
    val actorId: String,
    val authorityId: String,
    val externalUserId: String,
)

fun forgeError(statusCode: Int, code: String, message: String, details: Any? = null): Nothing =
    throw ForgePluginHttpException(statusCode, code, message, details)

fun resolveForgeCaller(caller: FactoryCallerIdentity): ForgeCaller =
    ForgeCaller(caller.organizationId, caller.userId, caller.userId, caller.userId)
