package io.whozoss.factory.workflow

import io.whozoss.factory.Neo4jDomainIntegrationTest
import io.whozoss.factory.workflow.sse.WorkflowProjectionEvents
import io.whozoss.factory.workflow.sse.WorkflowSseHub
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.server.LocalServerPort
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
class WorkflowSseHttpTest : Neo4jDomainIntegrationTest() {

    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var hub: WorkflowSseHub

    private val namespace = "0d4bd471-df37-43d8-a8f7-c989f95e71d7"

    @Autowired
    private lateinit var workstreams: io.whozoss.factory.workstream.WorkstreamService

    @org.junit.jupiter.api.BeforeEach
    fun registerNamespaceMapping() {
        workstreams.create(scope, io.whozoss.factory.workstream.web.CreateWorkstreamRequest(
            slug = scope.workstreamId, name = "SSE fixture", status = "active", namespaceId = namespace,
        ))
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
        while (hub.size(scope, namespace) == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }
        assertThat(hub.size(scope, namespace)).isGreaterThan(0)

        hub.publish(
            scope,
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

    /** No implicit subscription is allowed when the namespace is missing. */
    @Test
    fun `stream without namespaceId is rejected before subscription`() {
        val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
        val request = HttpRequest.newBuilder(URI.create("http://localhost:$port/api/factory/workflows/stream"))
            .header("Accept", "text/event-stream")
            .timeout(Duration.ofSeconds(10))
            .GET()
            .build()

        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        assertThat(response.statusCode()).isEqualTo(400)
        assertThat(response.body()).contains("INVALID_NAMESPACE_ID")
        assertThat(hub.size(scope, null)).isZero()
        assertThat(hub.size("")).isZero()
    }
}
