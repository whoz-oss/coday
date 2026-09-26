package io.whozoss.factory.web

import com.fasterxml.jackson.databind.ObjectMapper
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Test-only Fake IdP issuer. Mints HS256 JWTs and HMAC-signed proxy headers
 * using the same algorithm as [FakeIdp], so verification can be exercised
 * without a real OIDC provider.
 */
object TestJwt {

    private val json = ObjectMapper()

    private fun base64Url(value: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))

    private fun hmac(data: String, secret: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(data.toByteArray(Charsets.UTF_8)))
    }

    fun issueJwt(claims: Map<String, Any?>, secret: String): String {
        val header = base64Url("""{"alg":"HS256","typ":"JWT"}""")
        val payload = base64Url(json.writeValueAsString(claims))
        val signature = hmac("$header.$payload", secret)
        return "$header.$payload.$signature"
    }

    /** Sign proxy headers exactly like the Node `signProxyHeaders`. */
    fun signProxyHeaders(
        principalId: String,
        principalType: String,
        scopes: List<String> = emptyList(),
        serviceIdentityId: String? = null,
        timestamp: Long = System.currentTimeMillis(),
        secret: String,
    ): Map<String, String> {
        val signed = LinkedHashMap<String, String>()
        signed[FakeIdp.PROXY_PRINCIPAL_ID_HEADER] = principalId
        signed[FakeIdp.PROXY_PRINCIPAL_TYPE_HEADER] = principalType
        if (serviceIdentityId != null) signed[FakeIdp.PROXY_SERVICE_IDENTITY_ID_HEADER] = serviceIdentityId
        if (scopes.isNotEmpty()) signed[FakeIdp.PROXY_SCOPES_HEADER] = scopes.joinToString(",")
        signed[FakeIdp.PROXY_TIMESTAMP_HEADER] = timestamp.toString()

        val payload = listOf(
            FakeIdp.PROXY_PRINCIPAL_ID_HEADER,
            FakeIdp.PROXY_PRINCIPAL_TYPE_HEADER,
            FakeIdp.PROXY_SERVICE_IDENTITY_ID_HEADER,
            FakeIdp.PROXY_SCOPES_HEADER,
            FakeIdp.PROXY_TIMESTAMP_HEADER,
        ).filter { signed.containsKey(it) }
            .joinToString("\n") { "$it:${signed[it]}" }

        signed[FakeIdp.PROXY_SIGNATURE_HEADER] = hmac(payload, secret)
        return signed
    }
}
