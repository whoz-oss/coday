package io.whozoss.factory.proxy

import io.whozoss.factory.Neo4jDomainIntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.content
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient

/**
 * Integration tests of the AgentOS proxy client (against a mock HTTP server)
 * and of the relay endpoint's fail-closed validation.
 */
class AgentOsProxyMockTest : Neo4jDomainIntegrationTest() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    private fun buildClient(): Pair<HttpAgentOsProxyClient, MockRestServiceServer> {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        return HttpAgentOsProxyClient(builder, "http://agentos.test") to server
    }

    @Test
    fun `fetchAgents relays the trusted external user header`() {
        val (client, server) = buildClient()
        server.expect(requestTo("http://agentos.test/api/agent-configs/by-parentId/ns-1"))
            .andExpect(header("X-External-User-Id", "user-7"))
            .andRespond(withSuccess("""[{"id":"agent-a"}]""", MediaType.APPLICATION_JSON))

        val agents = client.fetchAgents("ns-1", "user-7")

        assertThat(agents as List<*>).hasSize(1)
        server.verify()
    }

    @Test
    fun `fetchNamespace returns null on 404 and throws on other failures`() {
        val (client, server) = buildClient()
        server.expect(requestTo("http://agentos.test/api/namespaces/missing"))
            .andRespond(withStatus(HttpStatus.NOT_FOUND))
        assertThat(client.fetchNamespace("missing", null)).isNull()
        server.verify()

        val (failing, failingServer) = buildClient()
        failingServer.expect(requestTo("http://agentos.test/api/namespaces/broken"))
            .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR))
        assertThatThrownBy { failing.fetchNamespace("broken", null) }
            .isInstanceOf(AgentOsUnavailableException::class.java)
    }

    @Test
    fun `getRunCost parses the run-cost reply into the local dto`() {
        val (client, server) = buildClient()
        server.expect(requestTo("http://agentos.test/api/cases/case-1/run-cost"))
            .andRespond(
                withSuccess(
                    """{"caseId":"case-1","since":"2026-01-01T00:00:00Z","cost":12.5,""" +
                        """"unknownCostCount":2,"runCostThreshold":50.0,"paused":true,""" +
                        """"active":true,"liveTokens":1234,"pausedCases":[]}""",
                    MediaType.APPLICATION_JSON,
                ),
            )

        val runCost = client.getRunCost("case-1", null)

        assertThat(runCost).isEqualTo(
            RunCostDto(
                caseId = "case-1",
                cost = 12.5,
                unknownCostCount = 2L,
                runCostThreshold = 50.0,
                paused = true,
                active = true,
                liveTokens = 1234L,
            ),
        )
        server.verify()
    }

    @Test
    fun `getRunCost degrades to null on 404 and on AgentOS failure without throwing`() {
        val (client, server) = buildClient()
        server.expect(requestTo("http://agentos.test/api/cases/missing/run-cost"))
            .andRespond(withStatus(HttpStatus.NOT_FOUND))
        assertThat(client.getRunCost("missing", null)).isNull()
        server.verify()

        val (failing, failingServer) = buildClient()
        failingServer.expect(requestTo("http://agentos.test/api/cases/broken/run-cost"))
            .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR))
        // Unlike fetchNamespace (which throws a 502 on non-404), getRunCost
        // swallows every failure: the metrics endpoint must still answer 200.
        assertThat(failing.getRunCost("broken", null)).isNull()
        failingServer.verify()
    }

    @Test
    fun `resolveRunStoreRoot derives the run store from the namespace configPath`() {
        val (client, server) = buildClient()
        server.expect(requestTo("http://agentos.test/api/namespaces/ns-2"))
            .andRespond(
                withSuccess("""{"configPath":"/srv/repo/coday.yaml"}""", MediaType.APPLICATION_JSON),
            )

        assertThat(client.resolveRepoRoot("ns-2", null)).isEqualTo("/srv/repo")
        server.verify()

        val (storeClient, storeServer) = buildClient()
        storeServer.expect(requestTo("http://agentos.test/api/namespaces/ns-2"))
            .andRespond(
                withSuccess("""{"configPath":"/srv/repo/coday.yaml"}""", MediaType.APPLICATION_JSON),
            )
        assertThat(storeClient.resolveRunStoreRoot("ns-2", null)).isEqualTo("/srv/repo/forge/factory-runs")
        storeServer.verify()
    }

    @Test
    fun `continueRunCost relays the threshold and trusted identity`() {
        val (client, server) = buildClient()
        server.expect(requestTo("http://agentos.test/api/cases/case-1/run-cost/continue"))
            .andExpect(header("X-External-User-Id", "user-7"))
            .andExpect(content().json("""{"expectedThreshold":50.0}"""))
            .andRespond(withStatus(HttpStatus.OK))

        assertThat(client.continueRunCost("case-1", 50.0, "user-7")).isTrue()
        server.verify()
    }

    @Test
    fun `stopRunCost relays the stop and returns true on success`() {
        val (client, server) = buildClient()
        server.expect(requestTo("http://agentos.test/api/cases/case-2/run-cost/stop"))
            .andRespond(withStatus(HttpStatus.NO_CONTENT))

        assertThat(client.stopRunCost("case-2", null)).isTrue()
        server.verify()
    }

    @Test
    fun `a disabled usage-tracking surfaces as a 503 usage-tracking exception`() {
        val (client, server) = buildClient()
        server.expect(requestTo("http://agentos.test/api/cases/case-3/run-cost/stop"))
            .andRespond(
                withStatus(HttpStatus.SERVICE_UNAVAILABLE)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("""{"status":503,"message":"Usage tracking is disabled"}"""),
            )

        assertThatThrownBy { client.stopRunCost("case-3", null) }
            .isInstanceOf(UsageTrackingUnavailableException::class.java)
            .hasMessage("Usage tracking is disabled")
        server.verify()
    }

    @Test
    fun `a non-503 AgentOS failure on cost control surfaces as a 502`() {
        val (client, server) = buildClient()
        server.expect(requestTo("http://agentos.test/api/cases/case-4/run-cost/continue"))
            .andRespond(withStatus(HttpStatus.CONFLICT))

        assertThatThrownBy { client.continueRunCost("case-4", 10.0, null) }
            .isInstanceOf(AgentOsUnavailableException::class.java)
        server.verify()
    }

    @Test
    fun `the agents relay endpoint requires a namespaceId`() {
        val response = restTemplate.exchange(
            "/api/agents",
            HttpMethod.GET,
            HttpEntity<Void>(HttpHeaders()),
            Map::class.java,
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        val error = response.body?.get("error") as? Map<*, *>
        assertThat(error?.get("code")).isEqualTo("MISSING_NAMESPACE_ID")
    }
}
