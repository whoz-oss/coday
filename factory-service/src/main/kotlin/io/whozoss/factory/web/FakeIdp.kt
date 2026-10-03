package io.whozoss.factory.web

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Fake Identity Provider — HS256 JWT and HMAC-signed proxy headers.
 *
 * Port of `factory/src/domain/identity/fake-idp.ts`. Deliberately offline and
 * dependency-free: it lets the HTTP boundary be exercised end-to-end without an
 * external OIDC provider. Only *signatures* are trusted; an identity header
 * supplied without a valid signature is ignored.
 */
object FakeIdp {

    /** Secret used when none is configured (local dev only). */
    const val DEFAULT_SECRET = "coday-fake-idp-dev-secret"

    const val FAKE_IDP_ISSUER = "coday-fake-idp"

    const val DEFAULT_PROXY_SIGNATURE_TTL_MS = 5L * 60L * 1000L

    const val PROXY_SIGNATURE_HEADER = "x-proxy-signature"
    const val PROXY_TIMESTAMP_HEADER = "x-proxy-timestamp"
    const val PROXY_PRINCIPAL_ID_HEADER = "x-proxy-principal-id"
    const val PROXY_PRINCIPAL_TYPE_HEADER = "x-proxy-principal-type"
    const val PROXY_SERVICE_IDENTITY_ID_HEADER = "x-proxy-service-identity-id"
    const val PROXY_SCOPES_HEADER = "x-proxy-scopes"

    /** Header names covered by the proxy signature, in canonical order. */
    private val SIGNED_PROXY_HEADERS = listOf(
        PROXY_PRINCIPAL_ID_HEADER,
        PROXY_PRINCIPAL_TYPE_HEADER,
        PROXY_SERVICE_IDENTITY_ID_HEADER,
        PROXY_SCOPES_HEADER,
        PROXY_TIMESTAMP_HEADER,
    )

    private val json = ObjectMapper()
    private val claimsType = object : TypeReference<Map<String, Any?>>() {}

    data class JwtVerification(
        val valid: Boolean,
        val claims: Map<String, Any?> = emptyMap(),
        val reason: String? = null,
    )

    data class ProxyVerification(
        val valid: Boolean,
        val principalId: String? = null,
        val principalType: String? = null,
        val serviceIdentityId: String? = null,
        val scopes: List<String> = emptyList(),
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

    private fun decodeBase64Url(value: String): String =
        String(Base64.getUrlDecoder().decode(value), Charsets.UTF_8)

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

    fun hasProxySignature(headers: Map<String, String>): Boolean =
        headers[PROXY_SIGNATURE_HEADER] != null

    /** Deterministic payload signed by the proxy: `name:value` lines in canonical order. */
    private fun canonicalProxyPayload(headers: Map<String, String>): String =
        SIGNED_PROXY_HEADERS
            .filter { headers[it] != null }
            .map { "$it:${headers[it]}" }
            .joinToString("\n")

    private fun parseScopes(value: String?): List<String> {
        if (value.isNullOrEmpty()) return emptyList()
        return value.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }

    /**
     * Verify signed proxy headers: presence, timestamp freshness (replay window)
     * and HMAC signature.
     */
    fun verifyProxyHeaders(
        headers: Map<String, String>,
        secret: String?,
        now: Long = System.currentTimeMillis(),
        ttlMs: Long = DEFAULT_PROXY_SIGNATURE_TTL_MS,
    ): ProxyVerification {
        val signature = headers[PROXY_SIGNATURE_HEADER]
            ?: return ProxyVerification(false, reason = "missing-signature")

        val timestampRaw = headers[PROXY_TIMESTAMP_HEADER]
            ?: return ProxyVerification(false, reason = "missing-timestamp")
        val timestamp = timestampRaw.toLongOrNull()
            ?: return ProxyVerification(false, reason = "invalid-timestamp")
        if (kotlin.math.abs(now - timestamp) > ttlMs) {
            return ProxyVerification(false, reason = "stale-timestamp")
        }

        val principalId = headers[PROXY_PRINCIPAL_ID_HEADER]
            ?: return ProxyVerification(false, reason = "missing-principal-id")
        val principalType = headers[PROXY_PRINCIPAL_TYPE_HEADER]
            ?: return ProxyVerification(false, reason = "missing-principal-type")

        val signed = SIGNED_PROXY_HEADERS
            .mapNotNull { name -> headers[name]?.let { name to it } }
            .toMap()
        val expected = hmac(canonicalProxyPayload(signed), secret)
        if (!constantTimeEquals(signature, expected)) return ProxyVerification(false, reason = "invalid-signature")

        return ProxyVerification(
            valid = true,
            principalId = principalId,
            principalType = if (principalType == TrustContext.PRINCIPAL_TYPE_SERVICE) {
                TrustContext.PRINCIPAL_TYPE_SERVICE
            } else {
                TrustContext.PRINCIPAL_TYPE_HUMAN
            },
            serviceIdentityId = headers[PROXY_SERVICE_IDENTITY_ID_HEADER],
            scopes = parseScopes(headers[PROXY_SCOPES_HEADER]),
        )
    }
}
