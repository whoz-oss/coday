package io.whozoss.factory.web

import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.factory.config.FactoryProperties
import io.whozoss.factory.persistence.TenantScope
import io.whozoss.factory.web.security.AuthIdentityResolver
import io.whozoss.factory.web.security.IdentityResolver
import io.whozoss.factory.web.security.LocalIdentityResolver
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.util.Base64

/**
 * The `local` / `auth` security-mode matrix of [AuthenticationFilter], with the real
 * resolvers. The OS user is always a supplied value, never the machine's.
 */
class AuthenticationFilterTest {

    private val secret = "unit-test-secret"
    private val osUser = "factory-test-user"
    private val mapper = ObjectMapper()

    private fun properties() = FactoryProperties(security = FactoryProperties.Security(fakeIdpSecret = secret))

    private fun filterWith(identity: IdentityResolver, membership: MembershipResolver = LocalDevMembershipResolver()) =
        AuthenticationFilter(identity, membership)

    private fun localFilter(os: String? = osUser, membership: MembershipResolver = LocalDevMembershipResolver()) =
        filterWith(LocalIdentityResolver { os }, membership)

    private fun authFilter(membership: MembershipResolver = LocalDevMembershipResolver()) =
        filterWith(AuthIdentityResolver(JwtTokenVerifier(properties()), mapper), membership)

    private fun jwt(claims: Map<String, Any?>, signedWith: String = secret): String =
        FakeIdp.issueJwt(claims, signedWith)

    private fun validJwt(userId: String = "alice"): String =
        jwt(mapOf("sub" to userId, "exp" to System.currentTimeMillis() / 1000 + 300))

    /** An unsigned token whose payload carries [claims], like the ones an IdP or Cloudflare issues. */
    private fun foreignJwt(claims: Map<String, Any?>): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val header = encoder.encodeToString("""{"alg":"RS256"}""".toByteArray())
        val payload = encoder.encodeToString(mapper.writeValueAsBytes(claims))
        return "$header.$payload.signature"
    }

    private class Outcome(val response: MockHttpServletResponse, val chain: MockFilterChain, val request: MockHttpServletRequest) {
        val reachedController: Boolean get() = chain.request != null
        val user: AuthenticatedUser? get() = AuthenticationFilter.from(request)
    }

    private fun run(
        filter: AuthenticationFilter,
        path: String = "/api/factory/workflows",
        bearer: String? = null,
        headers: Map<String, String> = emptyMap(),
    ): Outcome {
        val request = MockHttpServletRequest("GET", path).apply {
            requestURI = path
            if (bearer != null) addHeader("Authorization", "Bearer $bearer")
            headers.forEach { (name, value) -> addHeader(name, value) }
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

    // ----- local mode -----------------------------------------------------

    @Test
    fun `local mode without any credential is the OS user with the resolved scope`() {
        val outcome = run(localFilter())

        assertThat(outcome.reachedController).isTrue()
        assertThat(outcome.user).isEqualTo(AuthenticatedUser(osUser, LocalDevMembershipResolver.LOCAL_DEV_SCOPE))
    }

    @Test
    fun `local mode ignores every bearer and never answers 401`() {
        val expired = jwt(mapOf("sub" to "alice", "exp" to System.currentTimeMillis() / 1000 - 10))
        val wrongSecret = jwt(mapOf("sub" to "alice"), signedWith = "another-secret")

        listOf("not-a-jwt", "static-token-from-an-old-auth-setting", expired, wrongSecret, validJwt("alice")).forEach { bearer ->
            val outcome = run(localFilter(), bearer = bearer)
            assertThat(outcome.response.status).describedAs(bearer).isEqualTo(200)
            assertThat(outcome.reachedController).describedAs(bearer).isTrue()
            assertThat(outcome.user?.userId).describedAs(bearer).isEqualTo(osUser)
        }
    }

    @Test
    fun `local mode ignores identity headers`() {
        val outcome = run(
            localFilter(),
            bearer = foreignJwt(mapOf("preferred_username" to "mallory")),
            headers = mapOf(
                "X-External-User-Id" to "mallory",
                "Cf-Access-Jwt-Assertion" to foreignJwt(mapOf("email" to "mallory@corp.com")),
                "x-forwarded-email" to "mallory@corp.com",
                "x-factory-actor-id" to "mallory",
            ),
        )

        assertThat(outcome.user?.userId).isEqualTo(osUser)
    }

    @Test
    fun `local mode refuses a system account with the 403 envelope`() {
        LocalIdentityResolver.FORBIDDEN_USERNAMES.forEach { account ->
            val outcome = run(localFilter(os = account))

            assertThat(outcome.response.status).describedAs(account).isEqualTo(403)
            assertThat(outcome.response.contentAsString).describedAs(account).isEqualTo(
                """{"error":{"code":"FORBIDDEN_SYSTEM_ACCOUNT","message":"Factory is running under a system account ('$account'). Start it under a personal user account instead.","details":null}}""",
            )
            assertThat(outcome.reachedController).describedAs(account).isFalse()
        }
    }

    @Test
    fun `local mode forbids exactly the AgentOS list of system accounts`() {
        assertThat(LocalIdentityResolver.FORBIDDEN_USERNAMES).containsExactlyInAnyOrder(
            "root", "admin", "administrator", "daemon", "nobody", "www-data", "nginx", "apache",
            "httpd", "node", "app", "service", "docker", "ubuntu", "ec2-user",
        )
    }

    @Test
    fun `local mode with an undeterminable OS user answers 500 OS_USERNAME_UNAVAILABLE`() {
        listOf(null, "", "  ").forEach { os ->
            val outcome = run(localFilter(os = os))

            assertThat(outcome.response.status).describedAs(os).isEqualTo(500)
            assertThat(outcome.response.contentAsString).describedAs(os).contains("\"code\":\"OS_USERNAME_UNAVAILABLE\"")
            assertThat(outcome.reachedController).describedAs(os).isFalse()
        }
    }

    @Test
    fun `local mode accepts any username outside the forbidden list, whatever its characters`() {
        val outcome = run(localFilter(os = "admin\"\\x"))

        assertThat(outcome.user?.userId).isEqualTo("admin\"\\x")
    }

    @Test
    fun `a forbidden username is matched exactly, not by prefix or case`() {
        assertThat(run(localFilter(os = "rooty")).user?.userId).isEqualTo("rooty")
        assertThat(run(localFilter(os = "Root")).user?.userId).isEqualTo("Root")
    }

    // ----- auth mode ------------------------------------------------------

    @Test
    fun `auth mode a valid Factory JWT authenticates its user with the resolved scope`() {
        val outcome = run(authFilter(), bearer = validJwt("alice"))

        assertThat(outcome.reachedController).isTrue()
        assertThat(outcome.user).isEqualTo(AuthenticatedUser("alice", LocalDevMembershipResolver.LOCAL_DEV_SCOPE))
    }

    @Test
    fun `auth mode principalId wins over sub as the user id`() {
        val token = jwt(mapOf("principalId" to "pid", "sub" to "subject"))

        assertThat(run(authFilter(), bearer = token).user?.userId).isEqualTo("pid")
    }

    @Test
    fun `auth mode a valid Factory JWT wins over every header`() {
        val outcome = run(
            authFilter(),
            bearer = validJwt("alice"),
            headers = mapOf(
                "X-External-User-Id" to "external",
                "Cf-Access-Jwt-Assertion" to foreignJwt(mapOf("email" to "cf@corp.com")),
                "x-forwarded-email" to "fwd@corp.com",
            ),
        )

        assertThat(outcome.user?.userId).isEqualTo("alice")
    }

    @Test
    fun `auth mode X-External-User-Id beats the Cloudflare JWT and the forwarded email`() {
        val outcome = run(
            authFilter(),
            headers = mapOf(
                "X-External-User-Id" to "external",
                "Cf-Access-Jwt-Assertion" to foreignJwt(mapOf("email" to "cf@corp.com")),
                "x-forwarded-email" to "fwd@corp.com",
            ),
        )

        assertThat(outcome.user?.userId).isEqualTo("external")
    }

    @Test
    fun `auth mode reads the email claim of the Cloudflare JWT`() {
        val outcome = run(
            authFilter(),
            headers = mapOf(
                "Cf-Access-Jwt-Assertion" to foreignJwt(mapOf("email" to "cf@corp.com")),
                "x-forwarded-email" to "fwd@corp.com",
            ),
        )

        assertThat(outcome.user?.userId).isEqualTo("cf@corp.com")
    }

    @Test
    fun `auth mode an unverified bearer gives its preferred_username`() {
        val outcome = run(authFilter(), bearer = foreignJwt(mapOf("preferred_username" to "bob@corp.com")))

        assertThat(outcome.user?.userId).isEqualTo("bob@corp.com")
    }

    @Test
    fun `auth mode the Cloudflare JWT comes before the bearer preferred_username`() {
        val outcome = run(
            authFilter(),
            bearer = foreignJwt(mapOf("preferred_username" to "bob@corp.com")),
            headers = mapOf("Cf-Access-Jwt-Assertion" to foreignJwt(mapOf("email" to "cf@corp.com"))),
        )

        assertThat(outcome.user?.userId).isEqualTo("cf@corp.com")
    }

    @Test
    fun `auth mode x-forwarded-email is the last resort`() {
        val outcome = run(authFilter(), headers = mapOf("x-forwarded-email" to "fwd@corp.com"))

        assertThat(outcome.user?.userId).isEqualTo("fwd@corp.com")
    }

    @Test
    fun `auth mode an undecodable credential falls through to the next one instead of failing`() {
        val outcome = run(
            authFilter(),
            bearer = "not-a-jwt",
            headers = mapOf(
                "Cf-Access-Jwt-Assertion" to "a.%%%.c",
                "x-forwarded-email" to "fwd@corp.com",
            ),
        )

        assertThat(outcome.user?.userId).isEqualTo("fwd@corp.com")
    }

    @Test
    fun `auth mode blank headers and claims of the wrong type are not identities`() {
        val outcome = run(
            authFilter(),
            bearer = foreignJwt(mapOf("preferred_username" to 42)),
            headers = mapOf("X-External-User-Id" to "  ", "x-forwarded-email" to " "),
        )

        assertUnauthenticated(outcome)
    }

    @Test
    fun `auth mode nothing at all is refused with the exact 401 envelope`() {
        assertUnauthenticated(run(authFilter()))
    }

    @Test
    fun `auth mode a garbage bearer alone is refused with the 401 envelope`() {
        val expired = jwt(mapOf("sub" to "alice", "exp" to System.currentTimeMillis() / 1000 - 10))
        val tampered = validJwt().dropLast(2) + "xx"
        val wrongSecret = jwt(mapOf("sub" to "alice"), signedWith = "another-secret")

        listOf(expired, tampered, wrongSecret, "not-a-jwt", "static-token-from-an-old-auth-setting").forEach { bearer ->
            assertUnauthenticated(run(authFilter(), bearer = bearer))
        }
    }

    @Test
    fun `auth mode a valid Factory JWT without any user claim authenticates nobody`() {
        val token = jwt(mapOf("exp" to System.currentTimeMillis() / 1000 + 300))

        assertUnauthenticated(run(authFilter(), bearer = token))
    }

    // ----- scope ----------------------------------------------------------

    @Test
    fun `every user of every mode resolves its scope through the membership resolver`() {
        val custom = TenantScope("org-custom", "ws-custom")
        val membership = object : MembershipResolver {
            override fun resolveMembership(userId: String): TenantScope = custom
        }

        assertThat(run(localFilter(membership = membership)).user?.scope).isEqualTo(custom)
        assertThat(run(localFilter(membership = membership), bearer = "garbage").user?.scope).isEqualTo(custom)
        assertThat(run(authFilter(membership), bearer = validJwt()).user?.scope).isEqualTo(custom)
        assertThat(run(authFilter(membership), headers = mapOf("x-forwarded-email" to "a@b.c")).user?.scope).isEqualTo(custom)
    }

    // ----- which paths are guarded ---------------------------------------

    @Test
    fun `the public allow-list and every non-api path pass through without a credential`() {
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
            val outcome = run(authFilter(), path)
            assertThat(outcome.reachedController).describedAs(path).isTrue()
            assertThat(outcome.response.status).describedAs(path).isEqualTo(200)
        }
    }

    @Test
    fun `a system account is not refused on a public path`() {
        assertThat(run(localFilter(os = "root"), "/api/config").reachedController).isTrue()
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
            assertUnauthenticated(run(authFilter(), path))
        }
    }
}
