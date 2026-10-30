package io.whozoss.agentos.plugins.http.response

import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldStartWith
import io.whozoss.agentos.plugins.http.config.ResponseFormat
import io.whozoss.agentos.plugins.http.openapi.ResponseShaping

class ResponseShaperUnitSpec : StringSpec({
    isolationMode = IsolationMode.InstancePerLeaf

    fun shaping(
        keepPaths: List<String> = emptyList(),
        ignorePaths: List<String> = emptyList(),
        format: ResponseFormat = ResponseFormat.JSON,
        maxResponseChars: Int = 500,
    ): ResponseShaping = ResponseShaping(
        keepPaths = keepPaths,
        ignorePaths = ignorePaths,
        responseFormat = format,
        maxResponseChars = maxResponseChars,
    )

    "renders a JSON response compact after filtering" {
        val shaped = ResponseShaper.shape(
            text = """{ "ticket": { "id": 1, "subject": "x", "via": {} }, "count": 1 }""",
            contentType = "application/json; charset=utf-8",
            bytesTruncated = false,
            shaping = shaping(keepPaths = listOf("ticket.id", "count")),
        )
        shaped.text shouldBe """{"ticket":{"id":1},"count":1}"""
        shaped.truncated shouldBe false
    }

    "parses text that is JSON even without a JSON content type" {
        val shaped = ResponseShaper.shape(
            text = """{"a": 1}""",
            contentType = "text/plain",
            bytesTruncated = false,
            shaping = shaping(ignorePaths = listOf("a")),
        )
        shaped.text shouldBe "{}"
    }

    "returns non-JSON text as is" {
        val shaped = ResponseShaper.shape(
            text = "plain text: not json",
            contentType = "text/plain",
            bytesTruncated = false,
            shaping = shaping(keepPaths = listOf("a")),
        )
        shaped.text shouldBe "plain text: not json"
    }

    "renders YAML when the format is YAML" {
        val shaped = ResponseShaper.shape(
            text = """{"ticket":{"id":1,"subject":"Login broken"}}""",
            contentType = "application/json",
            bytesTruncated = false,
            shaping = shaping(format = ResponseFormat.YAML),
        )
        shaped.text shouldBe "ticket:\n  id: 1\n  subject: Login broken\n"
    }

    "caps the output with a marker giving the shown and total sizes" {
        val body = "x".repeat(1200)
        val shaped = ResponseShaper.shape(
            text = body,
            contentType = "text/plain",
            bytesTruncated = false,
            shaping = shaping(maxResponseChars = 500),
        )
        shaped.truncated shouldBe true
        shaped.text shouldStartWith "x".repeat(500)
        shaped.text shouldEndWith "... [truncated: showing 500 of 1200 chars; narrow with keepPaths or paginate]"
    }

    "flags a body truncated at the byte cap even when it fits the char cap" {
        val shaped = ResponseShaper.shape(
            text = "{\"a\":[1,2,",
            contentType = "application/json",
            bytesTruncated = true,
            shaping = shaping(),
        )
        shaped.truncated shouldBe true
        shaped.text shouldStartWith "{\"a\":[1,2,"
        shaped.text shouldContain "truncated"
    }

    "says the total is a lower bound when the body was cut at the byte cap and the text exceeds the char cap" {
        val shaped = ResponseShaper.shape(
            text = "y".repeat(1200),
            contentType = "text/plain",
            bytesTruncated = true,
            shaping = shaping(maxResponseChars = 500),
        )
        shaped.truncated shouldBe true
        shaped.text shouldStartWith "y".repeat(500)
        shaped.text shouldEndWith
            "... [truncated: showing 500 of more than 1200 chars; narrow with keepPaths or paginate]"
    }

    listOf("application/json", "application/vnd.api+json", "text/html", "application/xml", "application/yaml", null)
        .forEach { contentType ->
            "treats $contentType as textual" { ResponseShaper.isTextual(contentType) shouldBe true }
        }

    listOf("application/octet-stream", "image/png", "application/pdf", "application/zip").forEach { contentType ->
        "treats $contentType as binary" { ResponseShaper.isTextual(contentType) shouldBe false }
    }
})
