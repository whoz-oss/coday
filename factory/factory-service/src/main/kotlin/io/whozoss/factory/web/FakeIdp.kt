package io.whozoss.factory.web

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Fake Identity Provider — HS256 JWT.
 *
 * Deliberately offline and dependency-free: it lets the HTTP boundary be exercised
 * end-to-end without an external OIDC provider. Only *signatures* are trusted.
 */
object FakeIdp {

    /** Secret used when none is configured (local dev only). */
    const val DEFAULT_SECRET = "coday-fake-idp-dev-secret"

    private val json = ObjectMapper()
    private val claimsType = object : TypeReference<Map<String, Any?>>() {}

    data class JwtVerification(
        val valid: Boolean,
        val claims: Map<String, Any?> = emptyMap(),
        val reason: String? = null,
    )

    private fun normalizeSecret(secret: String?): String =
        if (!secret.isNullOrEmpty()) secret else DEFAULT_SECRET

    private fun hmac(data: String, secret: String?): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(normalizeSecret(secret).toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(data.toByteArray(Charsets.UTF_8)))
    }

    private fun constantTimeEquals(left: String, right: String): Boolean =
        MessageDigest.isEqual(left.toByteArray(Charsets.UTF_8), right.toByteArray(Charsets.UTF_8))

    private fun base64Url(value: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))

    private fun decodeBase64Url(value: String): String =
        String(Base64.getUrlDecoder().decode(value), Charsets.UTF_8)

    /** Mint a compact HS256 JWT carrying [claims]. */
    fun issueJwt(claims: Map<String, Any?>, secret: String?): String {
        val header = base64Url("""{"alg":"HS256","typ":"JWT"}""")
        val payload = base64Url(json.writeValueAsString(claims))
        return "$header.$payload.${hmac("$header.$payload", secret)}"
    }

    /**
     * Verify a compact JWT: structure, HMAC signature, expiry (`exp`), not-before
     * (`nbf`). A tampered, malformed or expired token is rejected.
     */
    fun verifyJwt(
        token: String?,
        secret: String?,
        nowSeconds: Long = System.currentTimeMillis() / 1000,
    ): JwtVerification {
        if (token.isNullOrEmpty()) return JwtVerification(false, reason = "missing-token")
        val parts = token.split(".")
        if (parts.size != 3) return JwtVerification(false, reason = "malformed-token")
        val (encodedHeader, encodedPayload, signature) = parts
        if (encodedHeader.isEmpty() || encodedPayload.isEmpty() || signature.isEmpty()) {
            return JwtVerification(false, reason = "malformed-token")
        }
        val expected = hmac("$encodedHeader.$encodedPayload", secret)
        if (!constantTimeEquals(signature, expected)) return JwtVerification(false, reason = "invalid-signature")

        val claims = try {
            json.readValue(decodeBase64Url(encodedPayload), claimsType)
        } catch (e: Exception) {
            return JwtVerification(false, reason = "unparseable-payload")
        }

        val exp = (claims["exp"] as? Number)?.toLong()
        if (exp != null && nowSeconds > exp) return JwtVerification(false, reason = "expired")
        val nbf = (claims["nbf"] as? Number)?.toLong()
        if (nbf != null && nowSeconds < nbf) return JwtVerification(false, reason = "not-yet-valid")
        return JwtVerification(true, claims = claims)
    }
}
