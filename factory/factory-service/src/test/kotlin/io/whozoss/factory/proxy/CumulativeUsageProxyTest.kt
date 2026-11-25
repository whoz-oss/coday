package io.whozoss.factory.proxy

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient

class CumulativeUsageProxyTest {
    @Test
    fun `union query forwards reader identity and parses every cumulative counter`() {
        val id = "00000000-0000-4000-8000-000000000001"
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        server.expect(requestTo("http://agentos.test/api/usage-records/aggregate/case-trees?caseIds=$id"))
            .andExpect(header("X-External-User-Id", "reader"))
            .andRespond(withSuccess("""{"cost":3.5,"unknownCostCount":2,"totalTokens":100,"inputTokens":60,"outputTokens":20,"cacheReadTokens":15,"cacheWriteTokens":5}""", MediaType.APPLICATION_JSON))
        val client = HttpAgentOsProxyClient(builder, "http://agentos.test")
        assertThat(client.getCumulativeUsage(listOf(id, id), "reader"))
            .isEqualTo(CumulativeUsageDto(3.5, 2, 100, 60, 20, 15, 5))
        server.verify()
    }

    @Test
    fun `legacy malformed identifiers remain unavailable without making a request`() {
        val client = HttpAgentOsProxyClient(RestClient.builder(), "http://agentos.test")
        assertThat(client.getCumulativeUsage(listOf("legacy-case"), "reader")).isNull()
    }
}
