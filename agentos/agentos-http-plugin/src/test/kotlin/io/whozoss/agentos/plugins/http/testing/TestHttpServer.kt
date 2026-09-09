package io.whozoss.agentos.plugins.http.testing

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/** One request received by [TestHttpServer]; [query] is the raw query string, null when absent. */
data class RecordedRequest(
    val method: String,
    val path: String,
    val query: String?,
    val headers: Map<String, List<String>>,
    val body: String,
) {
    fun header(name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()
}

/**
 * @property delayMillis Time to wait before answering (timeout tests).
 * @property rawBody Bytes sent instead of [body] when set (binary and oversize tests).
 */
data class TestResponse(
    val status: Int = 200,
    val body: String = "",
    val contentType: String? = "application/json",
    val headers: Map<String, String> = emptyMap(),
    val delayMillis: Long = 0,
    val rawBody: ByteArray? = null,
)

/** JDK HTTP server bound to an ephemeral loopback port; every request is recorded and answered by [handler]. */
class TestHttpServer : AutoCloseable {

    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val executor = Executors.newCachedThreadPool()

    val requests = CopyOnWriteArrayList<RecordedRequest>()

    @Volatile
    var handler: (RecordedRequest) -> TestResponse = { TestResponse(status = 404, body = """{"error":"not found"}""") }

    val baseUrl: String = "http://127.0.0.1:${server.address.port}"

    init {
        server.createContext("/") { exchange -> exchange.use { serve(it) } }
        server.executor = executor
        server.start()
    }

    private fun serve(exchange: HttpExchange) {
        val request = RecordedRequest(
            method = exchange.requestMethod,
            path = exchange.requestURI.rawPath,
            query = exchange.requestURI.rawQuery,
            headers = exchange.requestHeaders.mapValues { it.value.toList() },
            body = exchange.requestBody.readBytes().toString(Charsets.UTF_8),
        )
        requests += request
        val response = handler(request)
        if (response.delayMillis > 0) Thread.sleep(response.delayMillis)
        response.contentType?.let { exchange.responseHeaders.add("Content-Type", it) }
        response.headers.forEach { (name, value) -> exchange.responseHeaders.add(name, value) }
        val bytes = response.rawBody ?: response.body.toByteArray(Charsets.UTF_8)
        if (bytes.isEmpty() || response.status == 204 || response.status == 304) {
            exchange.sendResponseHeaders(response.status, -1)
        } else {
            exchange.sendResponseHeaders(response.status, bytes.size.toLong())
            exchange.responseBody.write(bytes)
        }
    }

    override fun close() {
        server.stop(0)
        executor.shutdownNow()
    }
}
