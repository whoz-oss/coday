package io.whozoss.agentos.security

import io.whozoss.agentos.security.AuthSecurityService.Companion.X_EXTERNAL_USER_ID_HEADER
import mu.KLogging
import org.springframework.http.HttpStatus
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import org.springframework.web.server.ResponseStatusException

/**
 * Auth-mode implementation of [SecurityService].
 *
 * Resolves the caller's external identity from the [X_EXTERNAL_USER_ID_HEADER] request
 * header. The header is expected to be set by the upstream gateway / reverse proxy after
 * authentication — AgentOS trusts it unconditionally.
 *
 * Throws 401 when the header is absent or blank.
 *
 * User persistence (lookup / auto-create) is handled upstream by
 * [io.whozoss.agentos.user.UserService.resolveOrCreateByExternalId].
 */
class AuthSecurityService : SecurityService {

    override fun resolveCurrentIdentity(): String {
        val request = (RequestContextHolder.currentRequestAttributes() as ServletRequestAttributes).request
        val externalUserId = request.getHeader(X_EXTERNAL_USER_ID_HEADER)

        logger.debug { "[Security/auth] $X_EXTERNAL_USER_ID_HEADER='$externalUserId'" }

        if (externalUserId.isNullOrBlank()) {
            logger.warn { "[Security/auth] Header '$X_EXTERNAL_USER_ID_HEADER' is absent or blank — returning 401" }
            throw ResponseStatusException(
                HttpStatus.UNAUTHORIZED,
                "Missing or blank '$X_EXTERNAL_USER_ID_HEADER' header.",
            )
        }

        return externalUserId
    }

    companion object : KLogging() {
        const val X_EXTERNAL_USER_ID_HEADER = "X-External-User-Id"
    }
}
