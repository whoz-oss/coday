package io.whozoss.factory

import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.factory.config.FactoryProperties
import io.whozoss.factory.config.WebConfig
import io.whozoss.factory.error.FactoryExceptionHandler
import io.whozoss.factory.error.ResourceNotFoundException
import io.whozoss.factory.web.AuthenticatedUser
import io.whozoss.factory.web.AuthenticatedUserArgumentResolver
import io.whozoss.factory.web.AuthenticationFilter
import io.whozoss.factory.web.CorrelationIdFilter
import io.whozoss.factory.web.FakeIdp
import io.whozoss.factory.web.JwtTokenVerifier
import io.whozoss.factory.web.LocalDevMembershipResolver
import io.whozoss.factory.web.security.AuthIdentityResolver
import io.whozoss.factory.web.security.IdentityResolver
import io.whozoss.factory.web.security.LocalIdentityResolver
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.matchesPattern
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.json.JsonCompareMode
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.test.web.servlet.setup.StandaloneMockMvcBuilder
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Web-layer boundary tests. They exercise the real servlet filter chain
 * ([CorrelationIdFilter] then [AuthenticationFilter]), the argument resolver that
 * injects the [AuthenticatedUser], the [FactoryExceptionHandler] advice and a test
 * controller — without a database, so they run on any machine (no Docker).
 */
class HttpBoundaryIntegrationTest {

    /** Test-only controller, instantiated directly (never registered in the real context). */
    @RestController
    class BoundaryController {
        @GetMapping("/api/test/user", produces = [MediaType.APPLICATION_JSON_VALUE])
        fun user(user: AuthenticatedUser): Map<String, Any?> =
            mapOf("userId" to user.userId, "organizationId" to user.scope.organizationId, "workstreamId" to user.scope.workstreamId)

        @GetMapping("/api/test/boom", produces = [MediaType.APPLICATION_JSON_VALUE])
        fun boom(): Nothing = throw IllegalStateException("boom")

        @GetMapping("/api/test/not-found", produces = [MediaType.APPLICATION_JSON_VALUE])
        fun notFound(): Nothing = throw ResourceNotFoundException("missing resource")

        @PostMapping("/api/test/illegal", produces = [MediaType.APPLICATION_JSON_VALUE])
        fun illegal(): Nothing = throw IllegalArgumentException("bad arg")
    }

    private val secret = "test-fake-idp-secret"
    private val LOCAL_USER = "factory-test-user"

    private fun buildMockMvc(identityResolver: IdentityResolver): MockMvc {
        val filter = AuthenticationFilter(identityResolver, LocalDevMembershipResolver())
        val builder = MockMvcBuilders.standaloneSetup(BoundaryController())
        builder.setControllerAdvice(FactoryExceptionHandler())
        builder.setCustomArgumentResolvers(AuthenticatedUserArgumentResolver())
        builder.addFilters<StandaloneMockMvcBuilder>(CorrelationIdFilter(), filter)
        return builder.build()
    }

    /** Local mode with a fixed OS user, so the test never depends on the machine's user. */
    private val localMockMvc: MockMvc = buildMockMvc(LocalIdentityResolver { LOCAL_USER })

    /** Auth mode: an unauthenticated request never reaches a controller. */
    private val strictMockMvc: MockMvc = buildMockMvc(
        FactoryProperties(security = FactoryProperties.Security(fakeIdpSecret = secret)).let {
            AuthIdentityResolver(JwtTokenVerifier(it), ObjectMapper())
        },
    )

    private fun bearer(userId: String): String =
        "Bearer " + FakeIdp.issueJwt(mapOf("sub" to userId, "exp" to System.currentTimeMillis() / 1000 + 300), secret)

    @Test
    fun `generates a correlation id when the header is absent`() {
        localMockMvc.perform(get("/api/test/user"))
            .andExpect(status().isOk)
            .andExpect(header().string("X-Correlation-Id", matchesPattern("coday-corr-.+")))
    }

    @Test
    fun `propagates an inbound correlation id`() {
        localMockMvc.perform(get("/api/test/user").header("X-Correlation-Id", "corr-abc-123"))
            .andExpect(status().isOk)
            .andExpect(header().string("X-Correlation-Id", "corr-abc-123"))
    }

    @Test
    fun `uncaught exception produces the canonical error envelope`() {
        localMockMvc.perform(get("/api/test/boom"))
            .andExpect(status().isInternalServerError)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(
                content().json(
                    """{"error":{"code":"INTERNAL_ERROR","message":"boom","details":null}}""",
                    JsonCompareMode.STRICT,
                ),
            )
    }

    @Test
    fun `not-found exception produces the NOT_FOUND envelope`() {
        localMockMvc.perform(get("/api/test/not-found"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
            .andExpect(jsonPath("$.error.message").value("missing resource"))
            .andExpect(jsonPath("$.error.details").value(nullValue()))
    }

    @Test
    fun `illegal argument produces a BAD_REQUEST envelope`() {
        localMockMvc.perform(post("/api/test/illegal"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.code").value("BAD_REQUEST"))
            .andExpect(jsonPath("$.error.message").value("bad arg"))
    }

    @Test
    fun `an unauthenticated request is refused with 401 UNAUTHENTICATED before the controller`() {
        strictMockMvc.perform(get("/api/test/user"))
            .andExpect(status().isUnauthorized)
            .andExpect(
                content().json(
                    """{"error":{"code":"UNAUTHENTICATED","message":"Authentication required","details":null}}""",
                    JsonCompareMode.STRICT,
                ),
            )
    }

    @Test
    fun `a valid JWT reaches the controller as an AuthenticatedUser`() {
        strictMockMvc.perform(get("/api/test/user").header("Authorization", bearer("alice")))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.userId").value("alice"))
            .andExpect(jsonPath("$.organizationId").value("org-local-dev"))
            .andExpect(jsonPath("$.workstreamId").value("ws-default"))
    }

    @Test
    fun `local mode gives the OS user without any bearer`() {
        localMockMvc.perform(get("/api/test/user"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.userId").value(LOCAL_USER))
    }

    @Test
    fun `local mode refuses a system account with 403 before the controller`() {
        buildMockMvc(LocalIdentityResolver { "root" }).perform(get("/api/test/user"))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error.code").value("FORBIDDEN_SYSTEM_ACCOUNT"))
    }

    @Test
    fun `correlation id is echoed even on error responses`() {
        val result = localMockMvc.perform(get("/api/test/boom").header("X-Correlation-Id", "corr-error"))
            .andExpect(status().isInternalServerError)
            .andReturn()
        assertThat(result.response.getHeader("X-Correlation-Id")).isEqualTo("corr-error")
    }

    @Test
    fun `the web configuration registers the user argument resolver`() {
        val resolvers = mutableListOf<org.springframework.web.method.support.HandlerMethodArgumentResolver>()
        WebConfig().addArgumentResolvers(resolvers)

        assertThat(resolvers).hasAtLeastOneElementOfType(AuthenticatedUserArgumentResolver::class.java)
    }
}
