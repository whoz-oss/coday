package io.whozoss.agentos.plugins.factorybridge

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.sun.net.httpserver.HttpServer
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import okhttp3.OkHttpClient
import java.net.InetSocketAddress

class FactoryCheckpointClientSpec : StringSpec({
    val mapper = jacksonObjectMapper()
    val ref = FactoryCheckpointRef("wf-1", "gate-1", 4L)

    fun server(
        statusCode: Int,
        responseBody: String,
    ): Pair<HttpServer, FactoryCheckpointClient> {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val bytes = responseBody.toByteArray()
            exchange.sendResponseHeaders(statusCode, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        val client =
            FactoryCheckpointClient(
                "http://127.0.0.1:${server.address.port}",
                OkHttpClient(),
                mapper,
            )
        return server to client
    }

    "returns success on 200" {
        val (srv, client) = server(200, """{"data":{"ok":true}}""")
        try {
            val result = client.submitDecision(ref, "approve", "case-1", "user-1")
            result.isSuccess shouldBe true
        } finally {
            srv.stop(0)
        }
    }

    "returns failure with Factory code on 409" {
        val (srv, client) = server(409, """{"error":{"code":"REVISION_CONFLICT","message":"Stale revision"}}""")
        try {
            val result = client.submitDecision(ref, "approve", "case-1", "user-1")
            result.isFailure shouldBe true
            val ex = result.exceptionOrNull()
            ex.shouldBeInstanceOf<FactoryCheckpointException>()
            (ex as FactoryCheckpointException).code shouldBe "REVISION_CONFLICT"
            ex.message shouldContain "Stale revision"
        } finally {
            srv.stop(0)
        }
    }

    "returns failure with FACTORY_REJECTED on 403 without structured body" {
        val (srv, client) = server(403, "Forbidden")
        try {
            val result = client.submitDecision(ref, "reject", "case-1", "user-1")
            result.isFailure shouldBe true
            val ex = result.exceptionOrNull() as FactoryCheckpointException
            ex.code shouldBe "FACTORY_REJECTED"
        } finally {
            srv.stop(0)
        }
    }

    "returns failure on network error" {
        // port 1 is reserved and always refuses connections
        val client = FactoryCheckpointClient("http://127.0.0.1:1", OkHttpClient(), mapper)
        val result = client.submitDecision(ref, "approve", "case-1", "user-1")
        result.isFailure shouldBe true
        val ex = result.exceptionOrNull() as FactoryCheckpointException
        ex.code shouldBe "FACTORY_UNAVAILABLE"
    }

    "sends correct URL path, headers, and body" {
        var capturedPath = ""
        var capturedBody = ""
        var caseHeader = ""
        var actorHeader = ""
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            capturedPath = exchange.requestURI.path
            capturedBody = exchange.requestBody.bufferedReader().readText()
            caseHeader = exchange.requestHeaders.getFirst("x-factory-case-id")
            actorHeader = exchange.requestHeaders.getFirst("x-factory-actor-id")
            val bytes = """{"data":{"ok":true}}""".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val client = FactoryCheckpointClient("http://127.0.0.1:${server.address.port}", OkHttpClient(), mapper)
            client.submitDecision(ref, "approve", "case-42", "actor-7").isSuccess shouldBe true
            capturedPath shouldBe "/api/factory/workflows/wf-1/interactions/gate-1/reply"
            caseHeader shouldBe "case-42"
            actorHeader shouldBe "actor-7"
            val body = mapper.readTree(capturedBody)
            body.path("interactionRevision").asLong() shouldBe 4L
            body.path("decision").asText() shouldBe "approve"
        } finally {
            server.stop(0)
        }
    }
})
