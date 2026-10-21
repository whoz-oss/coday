package io.whozoss.factory.forge.web

import io.whozoss.factory.web.AuthenticatedUser
import io.whozoss.factory.web.FactoryCaller
import io.whozoss.factory.web.factoryError
import io.whozoss.factory.web.factoryStorageFailure
import io.whozoss.factory.web.resolveFactoryCaller

/** Canonical HTTP success envelope `{ "data": ... }` for the surfaces that use it. */
data class ForgeDataEnvelope<T>(val data: T)

/** The trusted Forge caller identity + AgentOS relay identity. */
typealias ForgeCaller = FactoryCaller

/** Throw a Forge HTTP failure (alias of the core generic helper). */
fun forgeError(statusCode: Int, code: String, message: String, details: Any? = null): Nothing =
    factoryError(statusCode, code, message, details)

/** Map the authenticated user of the host boundary onto the Forge caller. */
fun resolveForgeCaller(user: AuthenticatedUser): ForgeCaller = resolveFactoryCaller(user)

/** Map a storage failure to the Node `FORGE_STORAGE_FAILURE` (500). */
fun forgeStorageFailure(error: Throwable): Nothing = factoryStorageFailure(error)
