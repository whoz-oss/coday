package io.whozoss.factory.web

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

class StaticServiceRouteGuardFilterTest {

    private val filter = StaticServiceRouteGuardFilter()

    private fun run(method: String, uri: String, authMethod: String): Pair<Int, Boolean> {
        val request = MockHttpServletRequest(method, uri).apply {
            setAttribute(TrustContextFilter.ATTRIBUTE, TrustContext(authenticationMethod = authMethod))
        }
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()
        filter.doFilter(request, response, chain)
        return response.status to (chain.request != null)
    }

    @Test
    fun `the worker credential reaches only the step result route`() {
        assertThat(run("POST", "/api/factory/agent-step-results", TrustContext.AUTH_STATIC_SERVICE_BEARER).second).isTrue()
        listOf(
            "GET" to "/api/factory/agent-step-results",
            "POST" to "/api/factory/workflows",
            "GET" to "/api/factory/workflows",
            "POST" to "/api/factory/workstream-service/workflows",
            "DELETE" to "/api/factory/workflows/abc",
        ).forEach { (method, uri) ->
            val (status, forwarded) = run(method, uri, TrustContext.AUTH_STATIC_SERVICE_BEARER)
            assertThat(status).describedAs("$method $uri").isEqualTo(403)
            assertThat(forwarded).describedAs("$method $uri").isFalse()
        }
    }

    @Test
    fun `the workstream credential reaches only the dedicated facade with GET or POST`() {
        listOf(
            "GET" to "/api/factory/workstream-service/workflows",
            "POST" to "/api/factory/workstream-service/workflows",
            "GET" to "/api/factory/workstream-service/workflows/wf-1/attempts",
            "POST" to "/api/factory/workstream-service/workflows/wf-1/retries",
            "GET" to "/api/factory/workstream-service/workstreams/ws-1",
        ).forEach { (method, uri) ->
            assertThat(run(method, uri, TrustContext.AUTH_STATIC_WORKSTREAM_BEARER).second).describedAs("$method $uri").isTrue()
        }
        listOf(
            "POST" to "/api/factory/agent-step-results",
            "GET" to "/api/factory/workflows",
            "POST" to "/api/factory/workflows",
            "PUT" to "/api/factory/workstream-service/workflows",
            "DELETE" to "/api/factory/workstream-service/workflows/wf-1",
            "GET" to "/api/factory/workstream-service/",
            "GET" to "/api/factory/workstream-service/../workflows",
            "GET" to "/api/factory/workstream-service/workflows//x",
            "GET" to "/api/factory/workstream-service/%2e%2e/workflows",
            "GET" to "/api/factory/workstream-service/workflows/",
            "GET" to "/api/factory/workstreams",
            "POST" to "/api/factory/workstreams/ws-1/controller-case",
        ).forEach { (method, uri) ->
            val (status, forwarded) = run(method, uri, TrustContext.AUTH_STATIC_WORKSTREAM_BEARER)
            assertThat(status).describedAs("$method $uri").isEqualTo(403)
            assertThat(forwarded).describedAs("$method $uri").isFalse()
        }
    }

    @Test
    fun `other authentication methods are not constrained by this filter`() {
        listOf(TrustContext.AUTH_JWT, TrustContext.AUTH_PROXY_SIGNATURE, TrustContext.AUTH_LOOPBACK_DEV, TrustContext.AUTH_ANONYMOUS)
            .forEach { method ->
                assertThat(run("GET", "/api/factory/workflows", method).second).describedAs(method).isTrue()
            }
    }
}
