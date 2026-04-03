package io.whozoss.agentos.security

import com.fasterxml.jackson.databind.ObjectMapper
import mu.KLogging
import org.springframework.http.HttpStatus
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import org.springframework.web.server.ResponseStatusException
import java.util.Base64

/**
 * Auth-mode implementation of [SecurityService].
 *
 * Resolves the caller's identity (email address) from a Cloudflare Access JWT carried
 * in [CF_AUTHORIZATION_HEADER]. Cloudflare validates the signature at the edge before
 * the request reaches AgentOS, so this implementation only decodes the payload to
 * extract the `email` claim — no signature verification needed.
 *
 * Falls back to [X_FORWARDED_EMAIL_HEADER] when the CF header is absent (matches Coday
 * Express behaviour for non-CF deployments).
 *
 * The current request is read from [RequestContextHolder] — no
 * [jakarta.servlet.http.HttpServletRequest] parameter needed at the callsite.
 *
 * Throws 401 when no identity can be resolved.
 *
 * User persistence (lookup / auto-create) is handled upstream by
 * [io.whozoss.agentos.user.UserService.resolveOrCreateByExternalId].
 */
class AuthSecurityService(
    private val objectMapper: ObjectMapper,
) : SecurityService {

    override fun resolveCurrentIdentity(): String {
        val request = (RequestContextHolder.currentRequestAttributes() as ServletRequestAttributes).request

        val cfHeader = request.getHeader(CF_AUTHORIZATION_HEADER)
        val emailHeader = request.getHeader(X_FORWARDED_EMAIL_HEADER)
        logger.info { "[Security/auth] Resolving identity — CF_Authorization present=${!cfHeader.isNullOrBlank()}, x-forwarded-email='$emailHeader'" }

        return resolveEmail(cfHeader, emailHeader)
            ?: run {
                logger.warn { "[Security/auth] No identity header found — returning 401" }
                throw ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    "No identity header found. Expected '$CF_AUTHORIZATION_HEADER' or '$X_FORWARDED_EMAIL_HEADER'.",
                )
            }
    }

    private fun resolveEmail(cfJwt: String?, forwardedEmail: String?): String? =
        when {
            !cfJwt.isNullOrBlank() -> extractEmailFromJwt(cfJwt)
            !forwardedEmail.isNullOrBlank() -> {
                logger.info { "[Security/auth] Resolved identity from $X_FORWARDED_EMAIL_HEADER: $forwardedEmail" }
                forwardedEmail
            }
            else -> null
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
