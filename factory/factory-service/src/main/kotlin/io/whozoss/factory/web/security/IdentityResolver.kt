package io.whozoss.factory.web.security

import jakarta.servlet.http.HttpServletRequest

/**
 * Resolves who is calling, from the request alone. The implementation is chosen by
 * `factory.security.mode` (see [SecurityConfiguration]); the
 * [io.whozoss.factory.web.AuthenticationFilter] is the only caller and turns each
 * [IdentityResult] into the matching HTTP answer.
 */
interface IdentityResolver {
    fun resolve(request: HttpServletRequest): IdentityResult
}

/** Outcome of an [IdentityResolver]. */
sealed interface IdentityResult {
    /** The caller is [userId]. */
    data class Authenticated(val userId: String) : IdentityResult

    /** No identity could be established: answered `401 UNAUTHENTICATED`. */
    data object Unauthenticated : IdentityResult

    /** The identity is established but refused: answered `403 FORBIDDEN_SYSTEM_ACCOUNT`. */
    data class Forbidden(val message: String) : IdentityResult

    /** The server cannot tell who it runs as: answered `500 OS_USERNAME_UNAVAILABLE`. */
    data class Unavailable(val message: String) : IdentityResult
}
