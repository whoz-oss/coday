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
