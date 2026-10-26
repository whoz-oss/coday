package io.whozoss.factory.web.security

import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.factory.web.TokenVerifier
import jakarta.servlet.http.HttpServletRequest
import java.util.Base64

/**
 * `auth` security mode: identity comes from the request, first match wins.
 *
 *  1. `Authorization: Bearer <jwt>` accepted by the [TokenVerifier] (signature-verified
 *     Factory HS256 token, `principalId` then `sub`). Keeps HTTP_API worker tokens and
 *     `mintToken` working.
 *  2. [X_EXTERNAL_USER_ID_HEADER], set by an upstream gateway: trusted as is.
 *  3. [CF_ACCESS_JWT_ASSERTION_HEADER], a Cloudflare Access JWT: `email` claim.
 *  4. an `Authorization: Bearer` that step 1 did not accept: `preferred_username` claim.
 *  5. [X_FORWARDED_EMAIL_HEADER].
 *  6. otherwise [IdentityResult.Unauthenticated].
 *
 * Steps 2 to 5 mirror `io.whozoss.agentos.security.AuthSecurityService` of
 * `agentos-service` (the builds are separate: this is a copy, keep both in sync). Like
 * AgentOS, they only DECODE the payload of a token, they never check its signature: the
 * deployment must put Factory behind a proxy that authenticates and strips these headers
 * from outside callers. A payload that cannot be decoded never throws, it falls through
 * to the next step.
 */
class AuthIdentityResolver(
    private val tokenVerifier: TokenVerifier,
    private val objectMapper: ObjectMapper,
) : IdentityResolver {

    override fun resolve(request: HttpServletRequest): IdentityResult {
        val bearer = bearerToken(request.getHeader(AUTHORIZATION_HEADER))
        val userId = bearer?.let(tokenVerifier::verify)
            ?: request.getHeader(X_EXTERNAL_USER_ID_HEADER)?.takeIf { it.isNotBlank() }
            ?: request.getHeader(CF_ACCESS_JWT_ASSERTION_HEADER)?.let { claim(it, EMAIL_CLAIM) }
            ?: bearer?.let { claim(it, PREFERRED_USERNAME_CLAIM) }
            ?: request.getHeader(X_FORWARDED_EMAIL_HEADER)?.takeIf { it.isNotBlank() }
        return when (userId) {
            null -> IdentityResult.Unauthenticated
            else -> IdentityResult.Authenticated(userId)
        }
    }

    private fun bearerToken(authorization: String?): String? =
        authorization?.let(BEARER_PATTERN::find)?.groupValues?.get(1)?.trim()?.ifEmpty { null }

    /** The string [name] claim of the (unverified) payload of [jwt], or null. Never throws. */
    private fun claim(jwt: String, name: String): String? =
        try {
            jwt.trim().split(".").getOrNull(1)?.let { payload ->
                val json = objectMapper.readTree(Base64.getUrlDecoder().decode(payload))
                json?.get(name)?.takeIf { it.isTextual }?.asText()?.takeIf { it.isNotBlank() }
            }
        } catch (_: Exception) {
            null
        }

    companion object {
        const val X_EXTERNAL_USER_ID_HEADER = "X-External-User-Id"
        const val CF_ACCESS_JWT_ASSERTION_HEADER = "Cf-Access-Jwt-Assertion"
        const val X_FORWARDED_EMAIL_HEADER = "x-forwarded-email"
        const val AUTHORIZATION_HEADER = "Authorization"
        const val PREFERRED_USERNAME_CLAIM = "preferred_username"
        const val EMAIL_CLAIM = "email"

        private val BEARER_PATTERN = Regex("^Bearer\\s+(.+)$", RegexOption.IGNORE_CASE)
    }
}
