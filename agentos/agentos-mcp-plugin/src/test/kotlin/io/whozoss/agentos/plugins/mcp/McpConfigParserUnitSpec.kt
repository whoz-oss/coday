package io.whozoss.agentos.plugins.mcp

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class McpConfigParserUnitSpec : StringSpec({

    val mapper = jacksonObjectMapper()

    // ----- stdio -----

    "parses minimal stdio config" {
        val json = mapper.readTree("""{ "command": "docker" }""")
        val config = McpConfigParser.parse(json)
        config.transport shouldBe McpTransport.STDIO
        config.command shouldBe "docker"
        config.args shouldBe emptyList()
        config.env shouldBe emptyMap()
        config.cwd shouldBe null
        config.timeoutSeconds shouldBe DEFAULT_CONNECT_TIMEOUT_SECONDS
        config.toolCallTimeoutSeconds shouldBe DEFAULT_TOOL_CALL_TIMEOUT_SECONDS
        config.idleTimeoutMinutes shouldBe DEFAULT_IDLE_TIMEOUT_MINUTES
    }

    "parses full stdio config" {
        val json = mapper.readTree("""
            {
                "command": "docker",
                "args": ["run", "-i", "--rm", "ghcr.io/github/github-mcp-server"],
                "env": {"GITHUB_PERSONAL_ACCESS_TOKEN": "ghp_test"},
                "cwd": "/tmp",
                "timeoutSeconds": 15,
                "toolCallTimeoutSeconds": 120,
                "idleTimeoutMinutes": 5
            }
        """)
        val config = McpConfigParser.parse(json)
        config.transport shouldBe McpTransport.STDIO
        config.command shouldBe "docker"
        config.args shouldBe listOf("run", "-i", "--rm", "ghcr.io/github/github-mcp-server")
        config.env shouldBe mapOf("GITHUB_PERSONAL_ACCESS_TOKEN" to "ghp_test")
        config.cwd shouldBe "/tmp"
        config.timeoutSeconds shouldBe 15L
        config.toolCallTimeoutSeconds shouldBe 120L
        config.idleTimeoutMinutes shouldBe 5L
    }

    "rejects blank command" {
        val json = mapper.readTree("""{ "command": "  " }""")
        val ex = shouldThrow<IllegalArgumentException> { McpConfigParser.parse(json) }
        ex.message shouldContain "command"
    }

    "rejects args with blank entry" {
        val json = mapper.readTree("""{ "command": "docker", "args": ["run", ""] }""")
        val ex = shouldThrow<IllegalArgumentException> { McpConfigParser.parse(json) }
        ex.message shouldContain "args"
    }

    "ignores null cwd" {
        val json = mapper.readTree("""{ "command": "docker", "cwd": null }""")
        val config = McpConfigParser.parse(json)
        config.cwd shouldBe null
    }

    // ----- HTTP -----

    "parses minimal HTTP config" {
        val json = mapper.readTree("""{ "url": "https://mcp.example.com/sse" }""")
        val config = McpConfigParser.parse(json)
        config.transport shouldBe McpTransport.HTTP
        config.url shouldBe "https://mcp.example.com/sse"
        config.authToken shouldBe null
        config.timeoutSeconds shouldBe DEFAULT_CONNECT_TIMEOUT_SECONDS
        config.toolCallTimeoutSeconds shouldBe DEFAULT_TOOL_CALL_TIMEOUT_SECONDS
        config.idleTimeoutMinutes shouldBe DEFAULT_IDLE_TIMEOUT_MINUTES
    }

    "parses HTTP config with authToken" {
        val json = mapper.readTree("""
            {
                "url": "https://mcp.example.com/sse",
                "authToken": "secret-token",
                "timeoutSeconds": 10,
                "toolCallTimeoutSeconds": 30,
                "idleTimeoutMinutes": 3
            }
        """)
        val config = McpConfigParser.parse(json)
        config.transport shouldBe McpTransport.HTTP
        config.url shouldBe "https://mcp.example.com/sse"
        config.authToken shouldBe "secret-token"
        config.timeoutSeconds shouldBe 10L
        config.toolCallTimeoutSeconds shouldBe 30L
        config.idleTimeoutMinutes shouldBe 3L
    }

    "rejects blank url" {
        val json = mapper.readTree("""{ "url": "   " }""")
        val ex = shouldThrow<IllegalArgumentException> { McpConfigParser.parse(json) }
        ex.message shouldContain "url"
    }

    "rejects blank authToken" {
        val json = mapper.readTree("""{ "url": "https://mcp.example.com", "authToken": "" }""")
        val ex = shouldThrow<IllegalArgumentException> { McpConfigParser.parse(json) }
        ex.message shouldContain "authToken"
    }

    // ----- mutual exclusion / missing transport -----

    "rejects config with neither command nor url" {
        val json = mapper.readTree("""{ "args": ["run"] }""")
        val ex = shouldThrow<IllegalArgumentException> { McpConfigParser.parse(json) }
        ex.message shouldContain "command"
        ex.message shouldContain "url"
    }

    "rejects config with both command and url" {
        val json = mapper.readTree("""{ "command": "docker", "url": "https://mcp.example.com" }""")
        val ex = shouldThrow<IllegalArgumentException> { McpConfigParser.parse(json) }
        ex.message shouldContain "mutually exclusive"
    }

    // ----- shared timeouts -----

    "rejects non-positive timeoutSeconds" {
        val json = mapper.readTree("""{ "command": "docker", "timeoutSeconds": 0 }""")
        val ex = shouldThrow<IllegalArgumentException> { McpConfigParser.parse(json) }
        ex.message shouldContain "timeoutSeconds"
    }

    "rejects non-positive toolCallTimeoutSeconds" {
        val json = mapper.readTree("""{ "command": "docker", "toolCallTimeoutSeconds": -1 }""")
        val ex = shouldThrow<IllegalArgumentException> { McpConfigParser.parse(json) }
        ex.message shouldContain "toolCallTimeoutSeconds"
    }

    "rejects non-positive idleTimeoutMinutes" {
        val json = mapper.readTree("""{ "command": "docker", "idleTimeoutMinutes": 0 }""")
        val ex = shouldThrow<IllegalArgumentException> { McpConfigParser.parse(json) }
        ex.message shouldContain "idleTimeoutMinutes"
    }
})
