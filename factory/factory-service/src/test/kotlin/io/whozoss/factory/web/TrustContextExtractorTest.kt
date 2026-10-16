package io.whozoss.factory.web

import io.whozoss.factory.config.FactoryProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest

class TrustContextExtractorTest {

    private val secret = "unit-test-secret"

    private fun extractor(
        allowLoopbackDev: Boolean,
        httpApiSubmission: FactoryProperties.HttpApiSubmission = FactoryProperties.HttpApiSubmission(),
    ): TrustContextExtractor =
        TrustContextExtractor(
            FactoryProperties(
                security = FactoryProperties.Security(
                    allowLoopbackDev = allowLoopbackDev,
                    fakeIdpSecret = secret,
                    httpApiSubmission = httpApiSubmission,
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
    fun `loopback-dev honours the actor-id header`() {
        val request = request().apply { addHeader("x-factory-actor-id", "alice") }
        val context = extractor(allowLoopbackDev = true).extract(request)

        assertThat(context.principalId).isEqualTo("alice")
    }

    @Test
    fun `configured HTTP API bearer yields a narrow service identity`() {
        val request = request().apply {
            remoteAddr = "203.0.113.10"
            addHeader("Authorization", "Bearer http-api-test-token")
            addHeader("x-factory-namespace-id", "forged-namespace")
            addHeader("x-factory-case-id", "forged-case")
        }
        val context = extractor(
            allowLoopbackDev = false,
            httpApiSubmission = FactoryProperties.HttpApiSubmission(
                enabled = true,
                bearerToken = "http-api-test-token",
                serviceIdentityId = "agentos-http-test",
            ),
        ).extract(request)

        assertThat(context.authenticationMethod).isEqualTo(TrustContext.AUTH_STATIC_SERVICE_BEARER)
        assertThat(context.principalType).isEqualTo(TrustContext.PRINCIPAL_TYPE_SERVICE)
        assertThat(context.serviceIdentityId).isEqualTo("agentos-http-test")
        assertThat(context.scopes).containsExactly(TrustContextExtractor.HTTP_API_SUBMISSION_SCOPE)
        assertThat(context.namespaceId).isNull()
        assertThat(context.caseId).isNull()
    }

    private val workstreamToken = "workstream-token-at-least-32-bytes-long!"

    private fun workstreamAccess(
        enabled: Boolean = true,
        permissions: Set<String> = setOf("read", "start_workflow"),
        token: String = workstreamToken,
    ) = FactoryProperties.WorkstreamServiceAccess(
        enabled = enabled,
        bearerToken = token,
        serviceIdentityId = "agentos-workstream-test",
        organizationId = "org-configured",
        workstreamId = "ws-configured",
        permissions = permissions,
    )

    private fun workstreamExtractor(access: FactoryProperties.WorkstreamServiceAccess): TrustContextExtractor =
        TrustContextExtractor(
            FactoryProperties(
                security = FactoryProperties.Security(
                    allowLoopbackDev = false,
                    fakeIdpSecret = secret,
                    workstreamService = access,
                ),
            ),
            LocalDevMembershipResolver(),
        )

    @Test
    fun `workstream bearer yields a service identity bound to its configured tenant and permissions`() {
        val request = request().apply {
            remoteAddr = "203.0.113.10"
            addHeader("Authorization", "Bearer $workstreamToken")
            // Caller-controlled attribution must never influence the tenant or the scopes.
            addHeader("x-factory-namespace-id", "forged")
            addHeader("x-proxy-principal-id", "forged")
        }
        val context = workstreamExtractor(workstreamAccess()).extract(request)

        assertThat(context.authenticationMethod).isEqualTo(TrustContext.AUTH_STATIC_WORKSTREAM_BEARER)
        assertThat(context.principalType).isEqualTo(TrustContext.PRINCIPAL_TYPE_SERVICE)
        assertThat(context.serviceIdentityId).isEqualTo("agentos-workstream-test")
        assertThat(context.organizationId).isEqualTo("org-configured")
        assertThat(context.workstreamId).isEqualTo("ws-configured")
        assertThat(context.roles).isEmpty()
        assertThat(context.scopes).containsExactlyInAnyOrder(
            WorkstreamServicePermissions.READ_SCOPE,
            WorkstreamServicePermissions.START_SCOPE,
        )
        assertThat(context.namespaceId).isNull()
        assertThat(context.caseId).isNull()
    }

    @Test
    fun `workstream bearer never authenticates when disabled or misconfigured`() {
        listOf(
            workstreamAccess(enabled = false),
            workstreamAccess(permissions = emptySet()),
            workstreamAccess(token = "short"),
        ).forEach { access ->
            val request = request().apply {
                remoteAddr = "203.0.113.10"
                addHeader("Authorization", "Bearer ${access.bearerToken}")
            }
            val context = workstreamExtractor(access).extract(request)
            assertThat(context.authenticationMethod).isEqualTo(TrustContext.AUTH_ANONYMOUS)
            assertThat(context.scopes).isEmpty()
        }
    }

    @Test
    fun `a wrong bearer does not authenticate as the workstream identity`() {
        val request = request().apply {
            remoteAddr = "203.0.113.10"
            addHeader("Authorization", "Bearer ${workstreamToken}x")
        }
        val context = workstreamExtractor(workstreamAccess()).extract(request)
        assertThat(context.authenticationMethod).isEqualTo(TrustContext.AUTH_ANONYMOUS)
    }

    @Test
    fun `HTTP API bearer is rejected while the opt in is disabled`() {
        val request = request().apply {
            remoteAddr = "203.0.113.10"
            addHeader("Authorization", "Bearer http-api-test-token")
        }
        val context = extractor(
            allowLoopbackDev = false,
            httpApiSubmission = FactoryProperties.HttpApiSubmission(
                enabled = false,
                bearerToken = "http-api-test-token",
            ),
        ).extract(request)

        assertThat(context.authenticationMethod).isEqualTo(TrustContext.AUTH_ANONYMOUS)
        assertThat(context.scopes).isEmpty()
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
