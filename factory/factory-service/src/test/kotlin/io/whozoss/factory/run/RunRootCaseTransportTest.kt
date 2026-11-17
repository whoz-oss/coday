package io.whozoss.factory.run

import io.whozoss.factory.proxy.HttpAgentOsProxyClient
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.*
import org.springframework.test.web.client.response.MockRestResponseCreators.*
import org.springframework.web.client.RestClient

class RunRootCaseTransportTest {
    @Test
    fun `creates exact root with required worktree and no message`() {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        val client = HttpAgentOsProxyClient(builder, "http://agentos")
        server.expect(requestTo("http://agentos/api/cases/root")).andExpect(method(HttpMethod.GET))
            .andExpect(header("X-External-User-Id", "alice")).andRespond(withStatus(HttpStatus.NOT_FOUND))
        server.expect(requestTo("http://agentos/api/cases")).andExpect(method(HttpMethod.POST))
            .andExpect(content().json("""{"id":"root","namespaceId":"ns","title":"Run","worktree":true}"""))
            .andRespond(withSuccess("""{"id":"root","namespaceId":"ns"}""", MediaType.APPLICATION_JSON))
        client.ensureRunRootCase("root", "ns", "Run", "alice")
        server.verify()
    }

    @Test
    fun `root creation sends recoverable naming intent without changing launch into an agent message`() {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        val client = HttpAgentOsProxyClient(builder, "http://agentos")
        server.expect(requestTo("http://agentos/api/cases/root")).andRespond(withStatus(HttpStatus.NOT_FOUND))
        server.expect(requestTo("http://agentos/api/cases")).andExpect(method(HttpMethod.POST))
            .andExpect(content().json("""{"id":"root","namespaceId":"ns","title":"WZ-35428","worktree":true,"namingInstructions":"Analyze login","namingPrefix":"WZ-35428"}"""))
            .andRespond(withSuccess("""{"id":"root","namespaceId":"ns"}""", MediaType.APPLICATION_JSON))
        client.ensureRunRootCase("root", "ns", "WZ-35428", "alice", "Analyze login", "WZ-35428")
        server.verify()
    }

    @Test
    fun `ticket-only root is created with key and without naming intent`() {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        val client = HttpAgentOsProxyClient(builder, "http://agentos")
        server.expect(requestTo("http://agentos/api/cases/root")).andRespond(withStatus(HttpStatus.NOT_FOUND))
        server.expect(requestTo("http://agentos/api/cases")).andExpect(method(HttpMethod.POST))
            .andExpect(content().json("""{"id":"root","namespaceId":"ns","title":"WZ-42","worktree":true}""", true))
            .andRespond(withSuccess("""{"id":"root","namespaceId":"ns"}""", MediaType.APPLICATION_JSON))
        client.ensureRunRootCase("root", "ns", "WZ-42", "alice", null, null)
        server.verify()
    }

    @Test
    fun `root with no naming inputs leaves title default to AgentOS`() {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        val client = HttpAgentOsProxyClient(builder, "http://agentos")
        server.expect(requestTo("http://agentos/api/cases/root")).andRespond(withStatus(HttpStatus.NOT_FOUND))
        server.expect(requestTo("http://agentos/api/cases")).andExpect(method(HttpMethod.POST))
            .andExpect(content().json("""{"id":"root","namespaceId":"ns","worktree":true}""", true))
            .andRespond(withSuccess("""{"id":"root","namespaceId":"ns"}""", MediaType.APPLICATION_JSON))
        client.ensureRunRootCase("root", "ns", null, "alice", null, null)
        server.verify()
    }

    @Test
    fun `recovery adopts a root without overwriting any title`() {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        val client = HttpAgentOsProxyClient(builder, "http://agentos")
        server.expect(requestTo("http://agentos/api/cases/root")).andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess("""{"id":"root","namespaceId":"ns","title":"manual"}""", MediaType.APPLICATION_JSON))
        client.ensureRunRootCase("root", "ns", "WZ-42", "alice", "instructions", "WZ-42")
        server.verify()
    }

    @Test
    fun `existing mismatched namespace is refused without creation`() {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        val client = HttpAgentOsProxyClient(builder, "http://agentos")
        server.expect(requestTo("http://agentos/api/cases/root"))
            .andRespond(withSuccess("""{"id":"root","namespaceId":"other"}""", MediaType.APPLICATION_JSON))
        assertThatThrownBy { client.ensureRunRootCase("root", "ns", "Run", "alice") }.hasMessageContaining("identity mismatch")
        server.verify()
    }
}
