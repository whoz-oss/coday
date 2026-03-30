package io.whozoss.agentos.security

import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.agentos.user.User
import io.whozoss.agentos.user.UserService
import jakarta.servlet.http.HttpServletRequest
import mu.KLogging
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.util.Base64

/**
 * Auth-mode implementation of [SecurityService].
 *
 * Resolves the current user from a Cloudflare Access JWT carried in [CF_AUTHORIZATION_HEADER].
 * Cloudflare validates the signature at the edge before the request reaches AgentOS, so this
 * implementation only decodes the payload to extract the `email` claim — no signature
 * verification needed.
 *
 * Falls back to [X_FORWARDED_EMAIL_HEADER] when the CF header is absent (matches Coday
 * Express behaviour for non-CF deployments).
 *
 * Throws 401 when no identity can be resolved, and 404 when the resolved email does not
 * match any persisted [User]. Clients must POST /api/users to register before their first
 * authenticated request.
 */
class AuthSecurityService(
    private val userService: UserService,
    private val objectMapper: ObjectMapper,
) : SecurityService {

    override fun resolveCurrentUser(request: HttpServletRequest): User {
        val email = resolveEmail(request)
            ?: throw ResponseStatusException(
                HttpStatus.UNAUTHORIZED,
                "No identity header found. Expected '$CF_AUTHORIZATION_HEADER' or '$X_FORWARDED_EMAIL_HEADER'.",
            )

        return userService.findByExternalId(email)
            ?: throw ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "No user registered for identity '$email'. POST /api/users to create one.",
            )
    }

    private fun resolveEmail(request: HttpServletRequest): String? {
        val cfJwt = request.getHeader(CF_AUTHORIZATION_HEADER)
        if (!cfJwt.isNullOrBlank()) {
            return extractEmailFromJwt(cfJwt)
        }
        val forwarded = request.getHeader(X_FORWARDED_EMAIL_HEADER)
        if (!forwarded.isNullOrBlank()) {
            logger.debug { "[Security/auth] Resolved identity from $X_FORWARDED_EMAIL_HEADER: $forwarded" }
            return forwarded
        }
        return null
    }

    /**
     * Decodes the JWT payload (middle segment) and extracts the `email` claim.
     * No signature verification — Cloudflare Access guarantees the token is valid
     * before forwarding the request.
     */
    internal fun extractEmailFromJwt(token: String): String? {
        return try {
            val payload = token.split(".").getOrNull(1)
                ?: return null
            val decoded = Base64.getUrlDecoder().decode(payload)
            @Suppress("UNCHECKED_CAST")
            val claims = objectMapper.readValue(decoded, Map::class.java) as Map<String, Any?>
            val email = claims["email"] as? String
            if (email.isNullOrBlank()) {
                logger.warn { "[Security/auth] JWT payload has no 'email' claim" }
                null
            } else {
                logger.debug { "[Security/auth] Resolved identity from CF JWT: $email" }
                email
            }
        } catch (e: Exception) {
            logger.warn(e) { "[Security/auth] Failed to decode JWT payload" }
            null
        }
    }

    companion object : KLogging() {
        const val CF_AUTHORIZATION_HEADER = "CF_Authorization"
        const val X_FORWARDED_EMAIL_HEADER = "x-forwarded-email"
    }
}
