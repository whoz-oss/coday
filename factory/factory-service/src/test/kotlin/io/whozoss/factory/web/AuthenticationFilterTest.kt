package io.whozoss.factory.web

import io.whozoss.factory.config.FactoryProperties
import io.whozoss.factory.persistence.TenantScope
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

class AuthenticationFilterTest {

    private val secret = "unit-test-secret"

    private fun filter(insecureMode: Boolean, resolver: MembershipResolver = LocalDevMembershipResolver()): AuthenticationFilter {
        val properties = FactoryProperties(
            security = FactoryProperties.Security(fakeIdpSecret = secret, insecureMode = insecureMode),
        )
        return AuthenticationFilter(properties, JwtTokenVerifier(properties), resolver)
    }

    private fun jwt(claims: Map<String, Any?>, signedWith: String = secret): String =
        FakeIdp.issueJwt(claims, signedWith)

    private fun validJwt(userId: String = "alice"): String =
        jwt(mapOf("sub" to userId, "exp" to System.currentTimeMillis() / 1000 + 300))

    private class Outcome(val response: MockHttpServletResponse, val chain: MockFilterChain, val request: MockHttpServletRequest) {
        val reachedController: Boolean get() = chain.request != null
        val user: AuthenticatedUser? get() = AuthenticationFilter.from(request)
    }

    private fun run(filter: AuthenticationFilter, path: String, bearer: String? = null): Outcome {
        val request = MockHttpServletRequest("GET", path).apply {
            requestURI = path
            if (bearer != null) addHeader("Authorization", "Bearer $bearer")
        }
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()
        filter.doFilter(request, response, chain)
        return Outcome(response, chain, request)
    }

    private fun assertUnauthenticated(outcome: Outcome) {
        assertThat(outcome.response.status).isEqualTo(401)
        assertThat(outcome.response.contentAsString).isEqualTo(
            """{"error":{"code":"UNAUTHENTICATED","message":"Authentication required","details":null}}""",
        )
        assertThat(outcome.reachedController).isFalse()
    }

    // ----- secure mode ----------------------------------------------------

    @Test
    fun `a valid JWT authenticates its user with the resolved scope`() {
        val outcome = run(filter(insecureMode = false), "/api/factory/workflows", validJwt("alice"))

        assertThat(outcome.reachedController).isTrue()
        assertThat(outcome.user).isEqualTo(AuthenticatedUser("alice", LocalDevMembershipResolver.LOCAL_DEV_SCOPE))
    }

    @Test
    fun `principalId wins over sub as the user id`() {
        val token = jwt(mapOf("principalId" to "pid", "sub" to "subject"))

        assertThat(run(filter(false), "/api/factory/workflows", token).user?.userId).isEqualTo("pid")
    }

    @Test
    fun `a missing bearer is refused with the 401 envelope`() {
        assertUnauthenticated(run(filter(insecureMode = false), "/api/factory/workflows"))
    }

    @Test
    fun `an expired tampered or non-JWT bearer is refused with the 401 envelope`() {
        val expired = jwt(mapOf("sub" to "alice", "exp" to System.currentTimeMillis() / 1000 - 10))
        val tampered = validJwt().dropLast(2) + "xx"
        val wrongSecret = jwt(mapOf("sub" to "alice"), signedWith = "another-secret")

        listOf(expired, tampered, wrongSecret, "not-a-jwt", "static-token-from-an-old-auth-setting").forEach { bearer ->
            assertUnauthenticated(run(filter(insecureMode = false), "/api/factory/workflows", bearer))
        }
    }

    @Test
    fun `a valid JWT without any user claim authenticates nobody`() {
        val token = jwt(mapOf("exp" to System.currentTimeMillis() / 1000 + 300))

        assertUnauthenticated(run(filter(insecureMode = false), "/api/factory/workflows", token))
    }

    // ----- insecure mode --------------------------------------------------

    @Test
    fun `insecure mode without a bearer is the fixed insecure user`() {
        val outcome = run(filter(insecureMode = true), "/api/factory/workflows")

        assertThat(outcome.reachedController).isTrue()
        assertThat(outcome.user).isEqualTo(
            AuthenticatedUser("insecure-mode-user", LocalDevMembershipResolver.LOCAL_DEV_SCOPE),
        )
    }

    @Test
    fun `insecure mode with a valid JWT keeps the JWT user`() {
        val outcome = run(filter(insecureMode = true), "/api/factory/workflows", validJwt("alice"))

        assertThat(outcome.user?.userId).isEqualTo("alice")
    }

    @Test
    fun `insecure mode never answers 401 for an invalid expired or non-JWT bearer`() {
        val expired = jwt(mapOf("sub" to "alice", "exp" to System.currentTimeMillis() / 1000 - 10))

        listOf(expired, "not-a-jwt", "static-token-from-an-old-auth-setting").forEach { bearer ->
            val outcome = run(filter(insecureMode = true), "/api/factory/workflows", bearer)
            assertThat(outcome.response.status).describedAs(bearer).isEqualTo(200)
            assertThat(outcome.reachedController).describedAs(bearer).isTrue()
            assertThat(outcome.user?.userId).describedAs(bearer).isEqualTo("insecure-mode-user")
        }
    }

    @Test
    fun `no header can choose the insecure user`() {
        val request = MockHttpServletRequest("GET", "/api/factory/workflows").apply {
            requestURI = "/api/factory/workflows"
            addHeader("x-factory-actor-id", "mallory")
            addHeader("x-factory-namespace-id", "ns")
        }
        filter(insecureMode = true).doFilter(request, MockHttpServletResponse(), MockFilterChain())

        assertThat(AuthenticationFilter.from(request)?.userId).isEqualTo("insecure-mode-user")
    }

    // ----- scope ----------------------------------------------------------

    @Test
    fun `every kind of user resolves its scope through the membership resolver`() {
        val custom = TenantScope("org-custom", "ws-custom")
        val resolver = object : MembershipResolver {
            override fun resolveMembership(userId: String): TenantScope = custom
        }

        assertThat(run(filter(false, resolver), "/api/x", validJwt()).user?.scope).isEqualTo(custom)
        assertThat(run(filter(true, resolver), "/api/x").user?.scope).isEqualTo(custom)
        assertThat(run(filter(true, resolver), "/api/x", "garbage").user?.scope).isEqualTo(custom)
    }

    // ----- which paths are guarded ---------------------------------------

    @Test
    fun `the public allow-list and every non-api path pass through without a bearer`() {
        listOf(
            "/api/config",
            "/api/factory/openapi/factory-worker.yaml",
            "/api/factory/openapi/factory-workstream.yaml",
            "/",
            "/cockpit",
            "/cockpit-v2",
            "/cockpit-v2/main-abc.js",
            "/v3/api-docs",
            "/v3/api-docs/swagger-config",
            "/swagger-ui/index.html",
            "/actuator/health",
        ).forEach { path ->
            val outcome = run(filter(insecureMode = false), path)
            assertThat(outcome.reachedController).describedAs(path).isTrue()
            assertThat(outcome.response.status).describedAs(path).isEqualTo(200)
        }
    }

    @Test
    fun `any other api path is guarded`() {
        listOf(
            "/api/factory/workflows",
            "/api/factory/agent-step-results",
            "/api/factory/workstream-service/workflows",
            "/api/namespaces",
            "/api/configuration",
            "/api/factory/openapi-extra",
        ).forEach { path ->
            assertThat(AuthenticationFilter.requiresAuthentication(path)).describedAs(path).isTrue()
            assertUnauthenticated(run(filter(insecureMode = false), path))
        }
    }
}
