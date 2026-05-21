package io.whozoss.agentos.security

import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.agentos.security.AuthSecurityService.Companion.CF_AUTHORIZATION_HEADER
import io.whozoss.agentos.security.AuthSecurityService.Companion.X_EXTERNAL_USER_ID_HEADER
import io.whozoss.agentos.security.AuthSecurityService.Companion.X_FORWARDED_EMAIL_HEADER
import mu.KLogging
import org.springframework.http.HttpStatus
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import org.springframework.web.server.ResponseStatusException
import java.util.Base64

/**
 * Auth-mode implementation of [SecurityService].
 *
 * Resolves the caller's identity using the following priority chain:
 *
 * 1. [X_EXTERNAL_USER_ID_HEADER] — set by an upstream gateway after authentication
 *    (e.g. the Whoz Express proxy). Trusted unconditionally when present.
 * 2. [CF_AUTHORIZATION_HEADER] — Cloudflare Access JWT. Cloudflare validates the
 *    signature at the edge; this implementation only decodes the payload to extract
 *    the `email` claim. No signature verification needed.
 * 3. `Authorization: Bearer <jwt>` — extracts `preferred_username` from the JWT payload.
 * 4. [X_FORWARDED_EMAIL_HEADER] — plain email header, fallback for non-CF deployments
 *    (matches Coday Express behaviour).
 *
 * Throws 401 when no identity can be resolved from any header.
 *
 * User persistence (lookup / auto-create) is handled upstream by
 * [io.whozoss.agentos.user.UserService.resolveOrCreateByExternalId].
 */
class AuthSecurityService(
    private val objectMapper: ObjectMapper,
) : SecurityService {
    override fun resolveCurrentIdentity(): String {
        val request = (RequestContextHolder.currentRequestAttributes() as ServletRequestAttributes).request

        val externalUserIdHeader = request.getHeader(X_EXTERNAL_USER_ID_HEADER)
        val cfHeader = request.getHeader(CF_AUTHORIZATION_HEADER)
        val authHeader = request.getHeader(AUTHORIZATION_HEADER)
        val emailHeader = request.getHeader(X_FORWARDED_EMAIL_HEADER)
        logger.info {
            "[Security/auth] Resolving identity — " +
                "$X_EXTERNAL_USER_ID_HEADER present=${!externalUserIdHeader.isNullOrBlank()}, " +
                "$CF_AUTHORIZATION_HEADER present=${!cfHeader.isNullOrBlank()}, " +
                "$AUTHORIZATION_HEADER present=${!authHeader.isNullOrBlank()}, " +
                "$X_FORWARDED_EMAIL_HEADER='$emailHeader'"
        }

        return resolveIdentity(
            externalUserIdHeader = externalUserIdHeader,
            cfHeader = cfHeader,
            authHeader = authHeader,
            emailHeader = emailHeader,
        ) ?: run {
            logger.warn { "[Security/auth] No identity header found — returning 401" }
            throw ResponseStatusException(
                HttpStatus.UNAUTHORIZED,
                "No identity header found. Expected '$X_EXTERNAL_USER_ID_HEADER', " +
                    "'$CF_AUTHORIZATION_HEADER' or '$X_FORWARDED_EMAIL_HEADER'.",
            )
        }
    }

    private fun resolveIdentity(
        externalUserIdHeader: String?,
        cfHeader: String?,
        authHeader: String?,
        emailHeader: String?,
    ): String? {
        val identityFromToken =
            when {
                !externalUserIdHeader.isNullOrBlank() -> {
                    logger.debug { "[Security/auth] Resolved identity from $X_EXTERNAL_USER_ID_HEADER: $externalUserIdHeader" }
                    externalUserIdHeader
                }

                !cfHeader.isNullOrBlank() -> {
                    extractEmailFromJwt(cfHeader)
                }

                !authHeader.isNullOrBlank() -> {
                    extractPreferredUsernameHeader(authHeader)
                }

                else -> {
                    null
                }
            }

        return identityFromToken ?: if (!emailHeader.isNullOrBlank()) {
            logger.debug { "[Security/auth] Resolved identity from $X_FORWARDED_EMAIL_HEADER: $emailHeader" }
            emailHeader
        } else {
            null
        }
    }

    /**
     * Decodes the JWT payload (middle segment) and extracts the `email` claim.
     * No signature verification — Cloudflare Access guarantees the token is valid
     * before forwarding the request.
     */
    internal fun extractEmailFromJwt(token: String): String? =
        try {
            val email =
                extractJwtBodyFromHeaderValue(token)?.let {
                    it[EMAIL_CLAIM] as? String
                }
            if (email.isNullOrBlank()) {
                logger.warn { "[Security/auth] JWT payload has no '$EMAIL_CLAIM' claim" }
                null
            } else {
                logger.debug { "[Security/auth] Resolved identity from CF JWT: $email" }
                email
            }
        } catch (e: Exception) {
            logger.warn(e) { "[Security/auth] Failed to decode JWT payload" }
            null
        }

    private fun extractJwtBodyFromHeaderValue(headerValue: String): Map<String, Any?>? =
        try {
            headerValue
                .split(".")
                .getOrNull(1)
                ?.let { encodedPayload ->
                    val decoded = Base64.getUrlDecoder().decode(encodedPayload)
                    @Suppress("UNCHECKED_CAST")
                    objectMapper.readValue(decoded, Map::class.java) as Map<String, Any?>?
                }
        } catch (e: Exception) {
            logger.warn(e) { "[Security/auth] Failed to decode JWT payload" }
            null
        }

    private fun extractPreferredUsernameHeader(authHeader: String): String? =
        try {
            val bearerToken = authHeader.substring(BEARER.length).trim()
            val preferredUsername =
                extractJwtBodyFromHeaderValue(bearerToken)?.let {
                    it[PREFERRED_USERNAME] as? String
                }
            if (preferredUsername.isNullOrBlank()) {
                logger.warn { "[Security/auth] JWT payload has no '$PREFERRED_USERNAME' claim" }
                null
            } else {
                logger.debug { "[Security/auth] Resolved identity from JWT: $preferredUsername" }
                preferredUsername
            }
        } catch (e: Exception) {
            logger.warn(e) { "[Security/auth] Failed to decode JWT payload" }
            null
        }

    companion object : KLogging() {
        const val X_EXTERNAL_USER_ID_HEADER = "X-External-User-Id"
        const val CF_AUTHORIZATION_HEADER = "CF_Authorization"
        const val X_FORWARDED_EMAIL_HEADER = "x-forwarded-email"
        const val AUTHORIZATION_HEADER = "Authorization"
        const val BEARER = "Bearer "
        const val PREFERRED_USERNAME = "preferred_username"
        const val EMAIL_CLAIM = "email"
    }
}
