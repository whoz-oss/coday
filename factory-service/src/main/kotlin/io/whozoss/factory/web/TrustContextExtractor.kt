package io.whozoss.factory.web

import io.whozoss.factory.config.FactoryProperties
import jakarta.servlet.http.HttpServletRequest
import org.springframework.stereotype.Component
import java.util.Locale

/**
 * Extracts the trusted identity + security context at the HTTP boundary.
 *
 * Faithful port of `extractTrustContext` in
 * `factory/dashboard/http-utils.mjs`. Identity is never inferred from the body
 * or the working directory; it comes only from a *verified* credential:
 *
 *   1. a valid `Authorization: Bearer <jwt>` verified against the Fake IdP;
 *   2. otherwise signed proxy headers (`x-proxy-signature`, ...) verified
 *      against the shared secret;
 *   3. otherwise a loopback-dev context — only when the socket is a loopback
 *      address AND `factory.security.allow-loopback-dev` is enabled;
 *   4. otherwise an anonymous context with strictly zero privilege.
 *
 * Memberships are resolved server-side from the authenticated principal and are
 * never read from client headers.
 */
@Component
class TrustContextExtractor(
    private val properties: FactoryProperties,
    private val membershipResolver: MembershipResolver,
) {

    fun extract(request: HttpServletRequest, correlationId: String? = null): TrustContext {
        val headers = headersOf(request)
        val header: (String) -> String? = { name ->
            headers[name.lowercase(Locale.ROOT)]?.takeIf { it.isNotEmpty() }
        }

        val remoteAddress = request.remoteAddr
        val loopback = isLoopbackAddress(remoteAddress)
        val allowLoopbackDev = properties.security.allowLoopbackDev
        val secret = properties.security.fakeIdpSecret.ifEmpty { FakeIdp.DEFAULT_SECRET }

        var authenticationMethod = TrustContext.AUTH_ANONYMOUS
        var principalId: String? = null
        var principalType = TrustContext.PRINCIPAL_TYPE_HUMAN
        var serviceIdentityId: String? = null
        var scopes: List<String> = emptyList()

        // 1. JWT — `Authorization: Bearer <token>` verified against the Fake IdP.
        val jwt = bearerToken(header("authorization"))
        if (!jwt.isNullOrEmpty()) {
            val verification = FakeIdp.verifyJwt(jwt, secret)
            if (verification.valid) {
                authenticationMethod = TrustContext.AUTH_JWT
                principalId = pickPrincipalId(verification.claims)
                principalType = pickPrincipalType(verification.claims["principalType"])
                serviceIdentityId = stringClaim(verification.claims["serviceIdentityId"])
                scopes = stringListClaim(verification.claims["scopes"])
            }
        }

        // 2. Signed proxy headers — trusted only when the signature verifies.
        if (authenticationMethod == TrustContext.AUTH_ANONYMOUS && FakeIdp.hasProxySignature(headers)) {
            val verification = FakeIdp.verifyProxyHeaders(headers, secret)
            if (verification.valid) {
                authenticationMethod = TrustContext.AUTH_PROXY_SIGNATURE
                principalId = verification.principalId
                principalType = verification.principalType ?: TrustContext.PRINCIPAL_TYPE_HUMAN
                serviceIdentityId = verification.serviceIdentityId
                scopes = verification.scopes
            }
        }

        // 3. Fallback — loopback development vs unauthenticated anonymous.
        // Both a loopback socket AND an explicit opt-in are required; otherwise
        // the caller stays anonymous with zero privilege (fail-closed).
        if (authenticationMethod == TrustContext.AUTH_ANONYMOUS) {
            if (loopback && allowLoopbackDev) {
                authenticationMethod = TrustContext.AUTH_LOOPBACK_DEV
                principalId = header("x-factory-actor-id") ?: TrustContext.LOOPBACK_DEV_PRINCIPAL_ID
                principalType = TrustContext.PRINCIPAL_TYPE_HUMAN
                serviceIdentityId = null
                scopes = listOf(TrustContext.ADMIN_WILDCARD_SCOPE)
            } else {
                authenticationMethod = TrustContext.AUTH_ANONYMOUS
                principalId = null
                principalType = TrustContext.PRINCIPAL_TYPE_HUMAN
                serviceIdentityId = null
                scopes = emptyList()
            }
        }

        // 4. Memberships are resolved server-side from the authenticated principal.
        // An anonymous caller is forced to a strictly empty membership.
        val membership = if (authenticationMethod == TrustContext.AUTH_ANONYMOUS) {
            MembershipInfo.EMPTY
        } else {
            membershipResolver.resolveMembership(principalId, principalType)
        }

        return TrustContext(
            principalId = principalId,
            principalType = principalType,
            organizationId = membership.organizationId,
            workstreamId = membership.workstreamId,
            squadId = membership.squadId,
            roles = membership.roles,
            scopes = scopes,
            correlationId = correlationId,
            authenticationMethod = authenticationMethod,
            serviceIdentityId = serviceIdentityId,
            loopback = loopback,
        )
    }

    private fun headersOf(request: HttpServletRequest): Map<String, String> {
        val result = HashMap<String, String>()
        val names = request.headerNames ?: return result
        while (names.hasMoreElements()) {
            val name = names.nextElement()
            val value = request.getHeader(name) ?: continue
            result[name.lowercase(Locale.ROOT)] = value
        }
        return result
    }

    private fun bearerToken(authorization: String?): String? {
        if (authorization.isNullOrEmpty()) return null
        val match = BEARER_PATTERN.find(authorization) ?: return null
        return match.groupValues[1].trim().ifEmpty { null }
    }

    private fun pickPrincipalId(claims: Map<String, Any?>): String? =
        stringClaim(claims["principalId"]) ?: stringClaim(claims["sub"])

    private fun pickPrincipalType(raw: Any?): String {
        val value = raw as? String
        return if (TrustContext.isKnownPrincipalType(value)) {
            value!!
        } else {
            TrustContext.PRINCIPAL_TYPE_HUMAN
        }
    }

    private fun stringClaim(raw: Any?): String? = (raw as? String)?.takeIf { it.isNotEmpty() }

    private fun stringListClaim(raw: Any?): List<String> =
        (raw as? List<*>)?.filterIsInstance<String>() ?: emptyList()

    companion object {
        private val BEARER_PATTERN = Regex("^Bearer\\s+(.+)$", RegexOption.IGNORE_CASE)

        private val LOOPBACK_ADDRESSES = setOf("127.0.0.1", "::1", "::ffff:127.0.0.1")

        /**
         * True when a socket address is a loopback address. Mirrors the Node
         * `isLoopbackAddress`: an unavailable address is treated as loopback, but
         * the loopback-dev fallback still requires `allowLoopbackDev` to be set.
         */
        fun isLoopbackAddress(address: String?): Boolean {
            if (address == null) return true
            return LOOPBACK_ADDRESSES.contains(address) || address.startsWith("127.")
        }
    }
}
