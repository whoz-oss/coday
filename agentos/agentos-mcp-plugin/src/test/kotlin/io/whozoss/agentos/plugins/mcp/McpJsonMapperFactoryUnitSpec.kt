package io.whozoss.agentos.plugins.mcp

import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.modelcontextprotocol.spec.McpSchema.CallToolResult
import io.modelcontextprotocol.spec.McpSchema.EmbeddedResource
import io.modelcontextprotocol.spec.McpSchema.TextResourceContents
import java.net.URLClassLoader

class McpJsonMapperFactoryUnitSpec : StringSpec({

    val resultJson =
        """
        {"content":[
          {"type":"text","text":"intro"},
          {"type":"resource","resource":{"uri":"repo://x","mimeType":"text/plain","text":"hello"}}
        ],"isError":false}
        """.trimIndent()

    /** Runs [block] with a context class loader that cannot see mcp-core, as on a Reactor thread of the service. */
    fun <T> withBlindContextClassLoader(block: () -> T): T {
        val thread = Thread.currentThread()
        val original = thread.contextClassLoader
        thread.contextClassLoader = URLClassLoader(arrayOf(), null)
        try {
            return block()
        } finally {
            thread.contextClassLoader = original
        }
    }

    "decodes an embedded text resource although the thread context class loader cannot see mcp-core" {
        val result = withBlindContextClassLoader {
            McpJsonMapperFactory.mapper.readValue(resultJson, CallToolResult::class.java)
        }

        val embedded = result.content()[1].shouldBeInstanceOf<EmbeddedResource>()
        val resource = embedded.resource().shouldBeInstanceOf<TextResourceContents>()
        resource.uri() shouldBe "repo://x"
        resource.text() shouldBe "hello"
    }

    "the mapper does not touch the thread context class loader" {
        val before = Thread.currentThread().contextClassLoader
        McpJsonMapperFactory.mapper.readValue(resultJson, CallToolResult::class.java)
        Thread.currentThread().contextClassLoader shouldBe before
    }

    "the type factory is bound to the class loader of the MCP SDK classes" {
        // A default ObjectMapper has no class loader bound (null): it then falls back to the thread context one.
        ObjectMapper().typeFactory.classLoader shouldBe null
        McpJsonMapperFactory.createObjectMapper().typeFactory.classLoader shouldBe CallToolResult::class.java.classLoader
    }

    "ignores unknown properties" {
        val json = """{"content":[{"type":"text","text":"a","unknown":1}],"isError":false,"extra":true}"""
        McpJsonMapperFactory.mapper.readValue(json, CallToolResult::class.java).content().size shouldBe 1
    }
})
