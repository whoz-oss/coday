package io.whozoss.factory

import io.whozoss.factory.config.FactoryProperties
import io.whozoss.factory.error.FactoryExceptionHandler
import io.whozoss.factory.web.BoundaryTestController
import io.whozoss.factory.web.CorrelationIdFilter
import io.whozoss.factory.web.LocalDevMembershipResolver
import io.whozoss.factory.web.TrustContextExtractor
import io.whozoss.factory.web.TrustContextFilter
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

/**
 * Web-layer boundary tests. They exercise the real servlet filter chain
 * ([CorrelationIdFilter] then [TrustContextFilter]), the
 * [FactoryExceptionHandler] advice and a test controller — without a database,
 * so they run on any machine (no Docker required).
 */
class HttpBoundaryIntegrationTest {

    private val properties = FactoryProperties(
        security = FactoryProperties.Security(
            allowLoopbackDev = true,
            fakeIdpSecret = "test-fake-idp-secret",
        ),
    )

    private val mockMvc: MockMvc = buildMockMvc(properties)

    /** Same boundary but with loopback-dev disabled (fail-closed anonymous). */
    private val strictMockMvc: MockMvc = buildMockMvc(
        FactoryProperties(
            security = FactoryProperties.Security(
                allowLoopbackDev = false,
                fakeIdpSecret = "test-fake-idp-secret",
            ),
        ),
    )

    private fun buildMockMvc(properties: FactoryProperties): MockMvc {
        val extractor = TrustContextExtractor(properties, LocalDevMembershipResolver())
        val builder = MockMvcBuilders.standaloneSetup(BoundaryTestController())
        builder.setControllerAdvice(FactoryExceptionHandler())
        builder.addFilters<StandaloneMockMvcBuilder>(CorrelationIdFilter(), TrustContextFilter(extractor))
        return builder.build()
    }

    @Test
    fun `generates a correlation id when the header is absent`() {
        mockMvc.perform(get("/test/context"))
            .andExpect(status().isOk)
            .andExpect(header().string("X-Correlation-Id", matchesPattern("coday-corr-.+")))
    }

    @Test
    fun `propagates an inbound correlation id`() {
        mockMvc.perform(get("/test/context").header("X-Correlation-Id", "corr-abc-123"))
            .andExpect(status().isOk)
            .andExpect(header().string("X-Correlation-Id", "corr-abc-123"))
            .andExpect(jsonPath("$.correlationId").value("corr-abc-123"))
    }

    @Test
    fun `uncaught exception produces the canonical error envelope`() {
        mockMvc.perform(get("/test/boom"))
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
        mockMvc.perform(get("/test/not-found"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
            .andExpect(jsonPath("$.error.message").value("missing resource"))
            .andExpect(jsonPath("$.error.details").value(nullValue()))
    }

    @Test
    fun `illegal argument produces a BAD_REQUEST envelope`() {
        mockMvc.perform(post("/test/illegal"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.code").value("BAD_REQUEST"))
            .andExpect(jsonPath("$.error.message").value("bad arg"))
    }

    @Test
    fun `anonymous caller gets a zero-privilege context`() {
        strictMockMvc.perform(get("/test/context"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.authenticationMethod").value("anonymous"))
            .andExpect(jsonPath("$.principalId").value(nullValue()))
            .andExpect(jsonPath("$.roles").isEmpty)
            .andExpect(jsonPath("$.scopes").isEmpty)
    }

    @Test
    fun `loopback-dev caller gets the wildcard context`() {
        mockMvc.perform(get("/test/context"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.authenticationMethod").value("loopback-dev"))
            .andExpect(jsonPath("$.principalId").value("local-dev-user"))
            .andExpect(jsonPath("$.scopes[0]").value("*"))
    }

    @Test
    fun `admin guard rejects an unauthorized caller with 403 FORBIDDEN_ADMIN_REQUIRED`() {
        strictMockMvc.perform(get("/test/admin"))
            .andExpect(status().isForbidden)
            .andExpect(
                content().json(
                    """{"error":{"code":"FORBIDDEN_ADMIN_REQUIRED","message":"Admin authorization required (UNAUTHENTICATED)","details":null}}""",
                    JsonCompareMode.STRICT,
                ),
            )
    }

    @Test
    fun `admin guard authorizes the loopback-dev wildcard`() {
        mockMvc.perform(get("/test/admin"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.authorized").value(true))
    }

    @Test
    fun `correlation id is echoed even on error responses`() {
        val result = mockMvc.perform(get("/test/boom").header("X-Correlation-Id", "corr-error"))
            .andExpect(status().isInternalServerError)
            .andReturn()
        assertThat(result.response.getHeader("X-Correlation-Id")).isEqualTo("corr-error")
    }
}
