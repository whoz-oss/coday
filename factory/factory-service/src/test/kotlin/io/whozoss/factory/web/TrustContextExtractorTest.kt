package io.whozoss.factory.web

import io.whozoss.factory.config.FactoryProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest

class TrustContextExtractorTest {

    private val secret = "unit-test-secret"

    private fun extractor(
        allowLoopbackDev: Boolean,
        trustedCidrs: List<String> = emptyList(),
    ): TrustContextExtractor =
        TrustContextExtractor(
            FactoryProperties(
                security = FactoryProperties.Security(
                    allowLoopbackDev = allowLoopbackDev,
                    fakeIdpSecret = secret,
                    trustedCidrs = trustedCidrs,
                ),
            ),
            LocalDevMembershipResolver(),
        )

    private fun request(): MockHttpServletRequest = MockHttpServletRequest().apply {
        remoteAddr = "127.0.0.1"
    }

    @Test
    fun `remote caller without credentials is anonymous with zero privilege`() {
        val request = request().apply { remoteAddr = "203.0.113.10" }
        val context = extractor(allowLoopbackDev = true).extract(request, "corr-1")

        assertThat(context.authenticationMethod).isEqualTo(TrustContext.AUTH_ANONYMOUS)
        assertThat(context.principalId).isNull()
        assertThat(context.roles).isEmpty()
        assertThat(context.scopes).isEmpty()
        assertThat(context.organizationId).isNull()
        assertThat(context.correlationId).isEqualTo("corr-1")
    }

    @Test
    fun `loopback detection accepts canonical and scoped local addresses`() {
        assertThat(TrustContextExtractor.isLoopbackAddress("127.0.0.1")).isTrue()
        assertThat(TrustContextExtractor.isLoopbackAddress("::1")).isTrue()
        assertThat(TrustContextExtractor.isLoopbackAddress("0:0:0:0:0:0:0:1")).isTrue()
        assertThat(TrustContextExtractor.isLoopbackAddress("[::1]")).isTrue()
        assertThat(TrustContextExtractor.isLoopbackAddress("::1%lo0")).isTrue()
        assertThat(TrustContextExtractor.isLoopbackAddress("203.0.113.10")).isFalse()
    }

    @Test
    fun `loopback caller with loopback-dev enabled gets the wildcard`() {
        val context = extractor(allowLoopbackDev = true).extract(request())

        assertThat(context.authenticationMethod).isEqualTo(TrustContext.AUTH_LOOPBACK_DEV)
        assertThat(context.principalId).isEqualTo(TrustContext.LOOPBACK_DEV_PRINCIPAL_ID)
        assertThat(context.scopes).containsExactly("*")
        assertThat(context.roles).containsExactly(TrustContext.FACTORY_MEMBER_ROLE)
        assertThat(context.loopback).isTrue()
    }

    @Test
    fun `loopback caller without loopback-dev opt-in stays anonymous`() {
        val context = extractor(allowLoopbackDev = false).extract(request())

        assertThat(context.authenticationMethod).isEqualTo(TrustContext.AUTH_ANONYMOUS)
        assertThat(context.principalId).isNull()
        assertThat(context.scopes).isEmpty()
    }

    @Test
    fun `caller inside a trusted cidr gets the loopback-dev context`() {
        val request = request().apply { remoteAddr = "172.18.0.3" }
        val context = extractor(allowLoopbackDev = true, trustedCidrs = listOf("10.0.0.0/8", "172.16.0.0/12"))
            .extract(request)

        assertThat(context.authenticationMethod).isEqualTo(TrustContext.AUTH_LOOPBACK_DEV)
        assertThat(context.principalId).isEqualTo(TrustContext.LOOPBACK_DEV_PRINCIPAL_ID)
        assertThat(context.scopes).containsExactly("*")
        assertThat(context.organizationId).isNotNull()
        // The flag keeps reporting the real socket: this caller is trusted, not loopback.
        assertThat(context.loopback).isFalse()
    }

    @Test
    fun `caller outside every trusted cidr stays anonymous`() {
        val request = request().apply { remoteAddr = "203.0.113.10" }
        val context = extractor(allowLoopbackDev = true, trustedCidrs = listOf("172.16.0.0/12")).extract(request)

        assertThat(context.authenticationMethod).isEqualTo(TrustContext.AUTH_ANONYMOUS)
        assertThat(context.scopes).isEmpty()
    }

    @Test
    fun `trusted cidr does nothing without the loopback-dev opt-in`() {
        val request = request().apply { remoteAddr = "172.18.0.3" }
        val context = extractor(allowLoopbackDev = false, trustedCidrs = listOf("172.16.0.0/12")).extract(request)

        assertThat(context.authenticationMethod).isEqualTo(TrustContext.AUTH_ANONYMOUS)
    }

    @Test
    fun `trusted cidr honours the cockpit namespace and case headers`() {
        val request = request().apply {
            remoteAddr = "172.18.0.3"
            addHeader("x-factory-namespace-id", "ns-1")
            addHeader("x-factory-case-id", "case-1")
        }
        val context = extractor(allowLoopbackDev = true, trustedCidrs = listOf("172.16.0.0/12")).extract(request)

        assertThat(context.namespaceId).isEqualTo("ns-1")
        assertThat(context.caseId).isEqualTo("case-1")
    }

    @Test
    fun `a malformed trusted cidr fails fast at construction`() {
        org.assertj.core.api.Assertions.assertThatThrownBy { extractor(allowLoopbackDev = true, trustedCidrs = listOf("172.16.0.0/99")) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `loopback-dev honours the actor-id header`() {
        val request = request().apply { addHeader("x-factory-actor-id", "alice") }
        val context = extractor(allowLoopbackDev = true).extract(request)

        assertThat(context.principalId).isEqualTo("alice")
    }

    @Test
    fun `valid JWT is trusted`() {
        val token = TestJwt.issueJwt(
            mapOf(
                "sub" to "user-42",
                "principalType" to "human",
                "scopes" to listOf("workflow:read"),
                "exp" to (System.currentTimeMillis() / 1000 + 300),
            ),
            secret,
        )
        val request = request().apply { addHeader("Authorization", "Bearer $token") }
        val context = extractor(allowLoopbackDev = false).extract(request)

        assertThat(context.authenticationMethod).isEqualTo(TrustContext.AUTH_JWT)
        assertThat(context.principalId).isEqualTo("user-42")
        assertThat(context.scopes).containsExactly("workflow:read")
        assertThat(context.roles).containsExactly(TrustContext.FACTORY_MEMBER_ROLE)
    }

    @Test
    fun `expired JWT is ignored`() {
        val token = TestJwt.issueJwt(
            mapOf(
                "sub" to "user-42",
                "exp" to (System.currentTimeMillis() / 1000 - 10),
            ),
            secret,
        )
        val request = request().apply { addHeader("Authorization", "Bearer $token") }
        val context = extractor(allowLoopbackDev = false).extract(request)

        assertThat(context.authenticationMethod).isEqualTo(TrustContext.AUTH_ANONYMOUS)
    }

    @Test
    fun `tampered JWT is ignored`() {
        val request = request().apply { addHeader("Authorization", "Bearer not.a.jwt") }
        val context = extractor(allowLoopbackDev = false).extract(request)

        assertThat(context.authenticationMethod).isEqualTo(TrustContext.AUTH_ANONYMOUS)
    }

    @Test
    fun `signed proxy headers are trusted`() {
        val headers = TestJwt.signProxyHeaders(
            principalId = "proxy-actor",
            principalType = "service",
            scopes = listOf("admin:*"),
            serviceIdentityId = "svc-1",
            namespaceId = "namespace-1",
            caseId = "case-1",
            secret = secret,
        )
        val request = request().apply { headers.forEach { (name, value) -> addHeader(name, value) } }
        val context = extractor(allowLoopbackDev = false).extract(request)

        assertThat(context.authenticationMethod).isEqualTo(TrustContext.AUTH_PROXY_SIGNATURE)
        assertThat(context.principalId).isEqualTo("proxy-actor")
        assertThat(context.principalType).isEqualTo(TrustContext.PRINCIPAL_TYPE_SERVICE)
        assertThat(context.serviceIdentityId).isEqualTo("svc-1")
        assertThat(context.scopes).containsExactly("admin:*")
        assertThat(context.namespaceId).isEqualTo("namespace-1")
        assertThat(context.caseId).isEqualTo("case-1")
    }

    @Test
    fun `tampering signed namespace or case fails closed`() {
        val signed = TestJwt.signProxyHeaders(
            principalId = "proxy-actor",
            principalType = "service",
            namespaceId = "namespace-1",
            caseId = "case-1",
            secret = secret,
        )
        listOf(
            FakeIdp.PROXY_NAMESPACE_ID_HEADER to "namespace-2",
            FakeIdp.PROXY_CASE_ID_HEADER to "case-2",
        ).forEach { (header, tamperedValue) ->
            val request = request().apply {
                signed.forEach { (name, value) -> addHeader(name, if (name == header) tamperedValue else value) }
            }
            val context = extractor(allowLoopbackDev = false).extract(request)
            assertThat(context.authenticationMethod).isEqualTo(TrustContext.AUTH_ANONYMOUS)
            assertThat(context.namespaceId).isNull()
            assertThat(context.caseId).isNull()
        }
    }

    @Test
    fun `missing signature or trusted field fails closed`() {
        val signed = TestJwt.signProxyHeaders(
            principalId = "proxy-actor",
            principalType = "service",
            namespaceId = "namespace-1",
            caseId = "case-1",
            secret = secret,
        )
        listOf(
            FakeIdp.PROXY_SIGNATURE_HEADER,
            FakeIdp.PROXY_NAMESPACE_ID_HEADER,
            FakeIdp.PROXY_CASE_ID_HEADER,
        ).forEach { missing ->
            val request = request().apply {
                signed.filterKeys { it != missing }.forEach { (name, value) -> addHeader(name, value) }
            }
            val context = extractor(allowLoopbackDev = false).extract(request)
            if (missing == FakeIdp.PROXY_SIGNATURE_HEADER) {
                assertThat(context.authenticationMethod).isEqualTo(TrustContext.AUTH_ANONYMOUS)
            } else {
                assertThat(context.authenticationMethod).isEqualTo(TrustContext.AUTH_ANONYMOUS)
            }
            assertThat(context.namespaceId).isNull()
            assertThat(context.caseId).isNull()
        }
    }

    @Test
    fun `unsigned proxy headers are ignored`() {
        val request = request().apply {
            addHeader(FakeIdp.PROXY_PRINCIPAL_ID_HEADER, "forged")
            addHeader(FakeIdp.PROXY_PRINCIPAL_TYPE_HEADER, "human")
            addHeader(FakeIdp.PROXY_SIGNATURE_HEADER, "deadbeef")
            addHeader(FakeIdp.PROXY_TIMESTAMP_HEADER, System.currentTimeMillis().toString())
        }
        val context = extractor(allowLoopbackDev = false).extract(request)

        assertThat(context.authenticationMethod).isEqualTo(TrustContext.AUTH_ANONYMOUS)
        assertThat(context.principalId).isNull()
    }
}
