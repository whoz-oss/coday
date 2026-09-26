package io.whozoss.factory.artifact.infrastructure.blob

import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.time.Instant

/**
 * Unit tests for the S3 / MinIO client. No external S3 is required: the SigV4
 * presigner is asserted directly, and the object operations are exercised
 * against a local `com.sun.net.httpserver.HttpServer` stand-in.
 */
class S3ArtifactBlobClientTest {

    private val client = S3ArtifactBlobClient(
        endpoint = "http://localhost:9000",
        region = "us-east-1",
        bucket = "coday-artifacts",
        accessKeyId = "factory",
        secretAccessKey = "factory_dev_pass",
    )

    private val fixedNow: Instant = Instant.parse("2026-01-01T00:00:00Z")

    private fun queryParams(url: String): Map<String, String> =
        url.substringAfter('?')
            .split('&')
            .associate { part ->
                val (name, value) = part.split('=', limit = 2)
                URLDecoder.decode(name, Charsets.UTF_8) to URLDecoder.decode(value, Charsets.UTF_8)
            }

    private fun withServer(handler: (com.sun.net.httpserver.HttpExchange) -> Unit, block: (Int) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            handler(exchange)
            exchange.close()
        }
        server.start()
        try {
            block(server.address.port)
        } finally {
            server.stop(0)
        }
    }

    private fun clientFor(port: Int): S3ArtifactBlobClient = S3ArtifactBlobClient(
        endpoint = "http://127.0.0.1:$port",
        region = "us-east-1",
        bucket = "coday-artifacts",
        accessKeyId = "factory",
        secretAccessKey = "factory_dev_pass",
    )

    @Test
    fun `getSignedUrl produces a SigV4 query-parameter GET URL`() {
        val url = client.getSignedUrl("objects/abc123", now = fixedNow)

        assertThat(url).startsWith("http://localhost:9000/coday-artifacts/objects/abc123?")
        val params = queryParams(url)
        assertThat(params["X-Amz-Algorithm"]).isEqualTo("AWS4-HMAC-SHA256")
        assertThat(params["X-Amz-Credential"]).isEqualTo("factory/20260101/us-east-1/s3/aws4_request")
        assertThat(params["X-Amz-Date"]).isEqualTo("20260101T000000Z")
        assertThat(params["X-Amz-Expires"]).isEqualTo("900")
        assertThat(params["X-Amz-SignedHeaders"]).isEqualTo("host")
        assertThat(params["X-Amz-Signature"]).matches("[0-9a-f]{64}")
    }

    @Test
    fun `explicit expiresInSeconds overrides the default TTL`() {
        val url = client.getSignedUrl("objects/abc123", expiresInSeconds = 3600, now = fixedNow)
        assertThat(queryParams(url)["X-Amz-Expires"]).isEqualTo("3600")
    }

    @Test
    fun `custom default TTL is applied when no option is given`() {
        val custom = S3ArtifactBlobClient(
            endpoint = "http://localhost:9000",
            region = "us-east-1",
            bucket = "coday-artifacts",
            accessKeyId = "factory",
            secretAccessKey = "factory_dev_pass",
            defaultSignedUrlTtlSeconds = 1800,
        )
        assertThat(queryParams(custom.getSignedUrl("objects/abc123", now = fixedNow))["X-Amz-Expires"]).isEqualTo("1800")
    }

    @Test
    fun `getObject returns null when the object is missing`() {
        withServer(handler = { exchange -> exchange.sendResponseHeaders(404, -1) }) { port ->
            assertThat(clientFor(port).getObject("objects/missing")).isNull()
        }
    }

    @Test
    fun `getObject streams the payload when the object exists`() {
        val payload = "hello-object".toByteArray()
        withServer(
            handler = { exchange ->
                exchange.sendResponseHeaders(200, payload.size.toLong())
                exchange.responseBody.use { it.write(payload) }
            },
        ) { port ->
            assertThat(clientFor(port).getObject("objects/present")!!.readBytes()).isEqualTo(payload)
        }
    }

    @Test
    fun `headObject distinguishes present from missing objects`() {
        withServer(
            handler = { exchange ->
                val status = if (exchange.requestURI.path.endsWith("present")) 200 else 404
                exchange.sendResponseHeaders(status, -1)
            },
        ) { port ->
            val local = clientFor(port)
            assertThat(local.headObject("objects/present")).isTrue()
            assertThat(local.headObject("objects/missing")).isFalse()
        }
    }
}
