package io.whozoss.agentos.plugins.http.net

import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.whozoss.agentos.plugins.http.testing.TestHttpServer
import io.whozoss.agentos.plugins.http.testing.TestResponse

class SpecFetcherUnitSpec : StringSpec({
    isolationMode = IsolationMode.InstancePerLeaf

    val policy = OutboundUrlPolicy(allowLoopbackForTests = true)
    val holder = HttpClientHolder(policy)
    val server = TestHttpServer()
    afterSpec {
        server.close()
        holder.shutdown()
    }
    val fetcher = SpecFetcher(holder.client, policy)
    val document = "openapi: 3.0.0\ninfo: {title: T, version: '1'}\npaths: {}\n"

    "fetches the document unauthenticated with an Accept header and returns its etag" {
        server.handler = {
            TestResponse(body = document, contentType = "application/yaml", headers = mapOf("ETag" to "\"v1\""))
        }
        val outcome = fetcher.fetch("${server.baseUrl}/openapi.yaml", maxBytes = 10_000, etag = null)
        outcome shouldBe FetchOutcome.Fetched(text = document, etag = "\"v1\"")
        val request = server.requests.single()
        request.method shouldBe "GET"
        request.path shouldBe "/openapi.yaml"
        request.header("Accept") shouldBe "application/json, application/yaml, text/yaml, text/plain;q=0.5"
        request.header("Authorization") shouldBe null
        request.header("If-None-Match") shouldBe null
    }

    "sends If-None-Match and maps 304 to NotModified" {
        server.handler = { TestResponse(status = 304) }
        fetcher.fetch("${server.baseUrl}/openapi.yaml", maxBytes = 10_000, etag = "\"v1\"") shouldBe
            FetchOutcome.NotModified
        server.requests.single().header("If-None-Match") shouldBe "\"v1\""
    }

    "fails on a non-2xx status without echoing the body" {
        server.handler = { TestResponse(status = 404, body = """{"secret":"do-not-echo"}""") }
        val failed = fetcher.fetch("${server.baseUrl}/missing.yaml", maxBytes = 10_000, etag = null)
        val reason = failed.shouldBeInstanceOf<FetchOutcome.Failed>().reason
        reason shouldContain "404"
        reason shouldNotContain "do-not-echo"
    }

    "fails on a redirect, which is never followed" {
        val location = mapOf("Location" to "http://127.0.0.1:1/x")
        server.handler = { TestResponse(status = 302, body = "", headers = location) }
        val failed = fetcher.fetch("${server.baseUrl}/openapi.yaml", maxBytes = 10_000, etag = null)
        failed.shouldBeInstanceOf<FetchOutcome.Failed>().reason shouldContain "302"
        server.requests.size shouldBe 1
    }

    "fails when the document exceeds maxBytes" {
        server.handler = { TestResponse(body = "x".repeat(2000), contentType = "text/plain") }
        val failed = fetcher.fetch("${server.baseUrl}/big.yaml", maxBytes = 1024, etag = null)
        failed.shouldBeInstanceOf<FetchOutcome.Failed>().reason shouldContain "larger than the allowed 1024 bytes"
    }

    "rejects a URL refused by the policy without any request" {
        val failed = fetcher.fetch("https://10.0.0.1/openapi.yaml", maxBytes = 10_000, etag = null)
        failed.shouldBeInstanceOf<FetchOutcome.Failed>().reason shouldContain "private"
        server.requests.size shouldBe 0
    }

    "maps a connection failure to Failed" {
        val closed = TestHttpServer().also { it.close() }
        val failed = fetcher.fetch("${closed.baseUrl}/openapi.yaml", maxBytes = 10_000, etag = null)
        failed.shouldBeInstanceOf<FetchOutcome.Failed>().reason shouldContain "ConnectException"
    }
})
