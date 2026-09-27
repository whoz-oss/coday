package io.whozoss.factory.workflow

import io.whozoss.factory.DomainIntegrationTest
import io.whozoss.factory.workflow.sse.WorkflowProjectionEvents
import io.whozoss.factory.workflow.sse.WorkflowSseHub
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * HTTP integration test of the SSE endpoint through the real servlet stack.
 *
 * Connects to `GET /api/factory/workflows/stream` (a real network stream), then
 * publishes a projection invalidation and asserts the exact named-event framing
 * arrives on the wire.
 */
class WorkflowSseHttpTest : DomainIntegrationTest() {

    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var hub: WorkflowSseHub

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    private val namespace = "0d4bd471-df37-43d8-a8f7-c989f95e71d7"

    /**
     * An error raised while opening the SSE stream must still be rendered as a
     * regular `application/json` error envelope. Without an explicit content
     * type the endpoint's `produces = text/event-stream` would be reused for the
     * error response, and no converter can write `ErrorResponse` as an event
     * stream (`HttpMessageNotWritableException`).
     */
    @Test
    fun `stream error is rendered as a json error envelope`() {
        val headers = HttpHeaders().apply {
            accept = listOf(MediaType.TEXT_EVENT_STREAM)
            add("X-Forwarded-For", "203.0.113.9")
        }

        val response = restTemplate.exchange(
            "/api/factory/workflows/stream?namespaceId=$namespace",
            HttpMethod.GET,
            HttpEntity<Void>(headers),
            String::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
        assertThat(response.headers.contentType?.toString().orEmpty()).contains("application/json")
        assertThat(response.body).contains("TRUST_CONTEXT_UNAVAILABLE")
        assertThat(response.body).contains("\"error\"")
    }

    @Test
    fun `stream emits the named projection event through the servlet stack`() {
        val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
        val request = HttpRequest.newBuilder(URI.create("http://localhost:$port/api/factory/workflows/stream?namespaceId=$namespace"))
            .header("Accept", "text/event-stream")
            .timeout(Duration.ofSeconds(10))
            .GET()
            .build()

        val future = client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream())

        val deadline = System.currentTimeMillis() + 5_000
        while (hub.size(namespace) == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }
        assertThat(hub.size(namespace)).isGreaterThan(0)

        hub.publish(
            namespace,
            linkedMapOf("workflowId" to "wf-sse-http", "namespaceId" to namespace, "revision" to 1),
            WorkflowProjectionEvents.UPDATED,
        )

        val response = future.get(10, TimeUnit.SECONDS)
        assertThat(response.statusCode()).isEqualTo(200)
        assertThat(response.headers().firstValue("content-type").orElse("")).contains("text/event-stream")
        assertThat(response.headers().firstValue("cache-control").orElse("")).contains("no-cache")
        assertThat(response.headers().firstValue("x-accel-buffering").orElse("")).isEqualTo("no")

        val lines = mutableListOf<String>()
        BufferedReader(InputStreamReader(response.body(), Charsets.UTF_8)).use { reader ->
            val readDeadline = System.currentTimeMillis() + 5_000
            while (System.currentTimeMillis() < readDeadline) {
                val line = reader.readLine() ?: break
                lines.add(line)
                if (line.isEmpty() && lines.any { it.startsWith("data:") }) break
            }
        }

        assertThat(lines).contains("event: workflow-projection-updated")
        assertThat(lines).anyMatch { it.startsWith("data: {\"workflowId\":\"wf-sse-http\"") }
    }
}
