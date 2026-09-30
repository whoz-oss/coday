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
