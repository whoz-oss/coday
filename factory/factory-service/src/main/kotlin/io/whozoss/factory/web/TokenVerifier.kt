package io.whozoss.factory.web

import io.whozoss.factory.config.FactoryProperties
import org.springframework.stereotype.Component

/** Turns a bearer token into the id of the user it authenticates, or `null` when it is not valid. */
interface TokenVerifier {
    fun verify(token: String): String?
}

/**
 * Verifies an HS256 JWT signed with `factory.security.fake-idp-secret`.
 *
 * The user id is the `principalId` claim, falling back to `sub`. A token that is
 * well signed and unexpired but carries neither claim authenticates nobody.
 */
@Component
class JwtTokenVerifier(
    private val properties: FactoryProperties,
) : TokenVerifier {

    override fun verify(token: String): String? {
        val secret = properties.security.fakeIdpSecret.ifEmpty { FakeIdp.DEFAULT_SECRET }
        val verification = FakeIdp.verifyJwt(token, secret)
        return when {
            !verification.valid -> null
            else -> claim(verification.claims, "principalId") ?: claim(verification.claims, "sub")
        }
    }

    private fun claim(claims: Map<String, Any?>, name: String): String? =
        (claims[name] as? String)?.takeIf { it.isNotBlank() }
}
