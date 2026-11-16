package io.whozoss.agentos.plugins.mcp

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.modelcontextprotocol.spec.McpSchema.CallToolResult

class McpResultFormatterUnitSpec : StringSpec({

    /** Decodes a result the way the SDK does on the wire, so the specs do not depend on SDK constructors. */
    fun result(contentJson: String): CallToolResult =
        McpJsonMapperFactory.mapper.readValue("""{"content":$contentJson,"isError":false}""", CallToolResult::class.java)

    fun text(value: String) = """{"type":"text","text":"$value"}"""

    fun textResource(uri: String, mimeType: String?, value: String): String {
        val mime = mimeType?.let { """"mimeType":"$it",""" } ?: ""
        return """{"type":"resource","resource":{"uri":"$uri",$mime"text":"$value"}}"""
    }

    fun blobResource(uri: String, mimeType: String, blob: String) =
        """{"type":"resource","resource":{"uri":"$uri","mimeType":"$mimeType","blob":"$blob"}}"""

    "a single text content is rendered as is" {
        McpResultFormatter.format(result("[${text("hello")}]")) shouldBe "hello"
    }

    "several text contents are joined with a newline" {
        McpResultFormatter.format(result("[${text("a")},${text("b")}]")) shouldBe "a\nb"
    }

    "an embedded text resource is rendered with a uri and mime type header, then its text" {
        val output = McpResultFormatter.format(result("[${textResource("repo://x", "text/plain", "hello")}]"))
        output shouldBe "[resource repo://x (text/plain)]\nhello"
    }

    "an embedded text resource without a mime type omits it from the header" {
        val output = McpResultFormatter.format(result("[${textResource("repo://x", null, "hello")}]"))
        output shouldBe "[resource repo://x]\nhello"
    }

    "an embedded blob resource is summarised and the blob is never dumped" {
        val blob = "QUJDREVGRw=="
        val output = McpResultFormatter.format(result("[${blobResource("repo://img", "image/png", blob)}]"))
        output shouldBe "[resource repo://img: binary image/png, ${blob.length} bytes base64]"
        output shouldNotContain blob
    }

    "text and resources mixed keep their order" {
        val content = listOf(
            text("intro"),
            textResource("repo://x", "text/plain", "body"),
            blobResource("repo://b", "application/pdf", "AAAA"),
        ).joinToString(",", prefix = "[", postfix = "]")

        McpResultFormatter.format(result(content)).lines() shouldBe listOf(
            "intro",
            "[resource repo://x (text/plain)]",
            "body",
            "[resource repo://b: binary application/pdf, 4 bytes base64]",
        )
    }

    "an image keeps the unsupported marker" {
        val output = McpResultFormatter.format(result("""[{"type":"image","data":"AAAA","mimeType":"image/png"}]"""))
        output shouldContain "unsupported content type"
    }

    "an empty content list gives the no-output text" {
        McpResultFormatter.format(result("[]")) shouldBe "(no output)"
    }

    "a missing content gives the no-output text" {
        val missing = McpJsonMapperFactory.mapper.readValue("""{"isError":false}""", CallToolResult::class.java)
        McpResultFormatter.format(missing) shouldBe "(no output)"
    }
})
