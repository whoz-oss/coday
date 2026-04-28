package io.whozoss.agentos.plugins.mcp

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class McpConfigParserUnitSpec : StringSpec({

    val mapper = jacksonObjectMapper()

    "parses minimal valid config" {
        val json = mapper.readTree("""{"command": "docker"}""")
        val config = McpConfigParser.parse(json)
        config.command shouldBe "docker"
        config.args shouldBe emptyList()
        config.env shouldBe emptyMap()
        config.cwd shouldBe null
        config.timeoutSeconds shouldBe DEFAULT_CONNECT_TIMEOUT_SECONDS
        config.toolCallTimeoutSeconds shouldBe DEFAULT_TOOL_CALL_TIMEOUT_SECONDS
        config.idleTimeoutMinutes shouldBe DEFAULT_IDLE_TIMEOUT_MINUTES
    }

    "parses full config" {
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
        config.command shouldBe "docker"
        config.args shouldBe listOf("run", "-i", "--rm", "ghcr.io/github/github-mcp-server")
        config.env shouldBe mapOf("GITHUB_PERSONAL_ACCESS_TOKEN" to "ghp_test")
        config.cwd shouldBe "/tmp"
        config.timeoutSeconds shouldBe 15L
        config.toolCallTimeoutSeconds shouldBe 120L
        config.idleTimeoutMinutes shouldBe 5L
    }

    "rejects missing command" {
        val json = mapper.readTree("""{"args": ["run"]}""")
        val ex = shouldThrow<IllegalArgumentException> { McpConfigParser.parse(json) }
        ex.message shouldContain "command"
    }

    "rejects blank command" {
        val json = mapper.readTree("""{"command": "  "}""")
        val ex = shouldThrow<IllegalArgumentException> { McpConfigParser.parse(json) }
        ex.message shouldContain "command"
    }

    "rejects args with blank entry" {
        val json = mapper.readTree("""{"command": "docker", "args": ["run", ""]}""")
        val ex = shouldThrow<IllegalArgumentException> { McpConfigParser.parse(json) }
        ex.message shouldContain "args"
    }

    "rejects non-positive timeoutSeconds" {
        val json = mapper.readTree("""{"command": "docker", "timeoutSeconds": 0}""")
        val ex = shouldThrow<IllegalArgumentException> { McpConfigParser.parse(json) }
        ex.message shouldContain "timeoutSeconds"
    }

    "rejects non-positive toolCallTimeoutSeconds" {
        val json = mapper.readTree("""{"command": "docker", "toolCallTimeoutSeconds": -1}""")
        val ex = shouldThrow<IllegalArgumentException> { McpConfigParser.parse(json) }
        ex.message shouldContain "toolCallTimeoutSeconds"
    }

    "rejects non-positive idleTimeoutMinutes" {
        val json = mapper.readTree("""{"command": "docker", "idleTimeoutMinutes": 0}""")
        val ex = shouldThrow<IllegalArgumentException> { McpConfigParser.parse(json) }
        ex.message shouldContain "idleTimeoutMinutes"
    }

    "ignores null cwd" {
        val json = mapper.readTree("""{"command": "docker", "cwd": null}""")
        val config = McpConfigParser.parse(json)
        config.cwd shouldBe null
    }
})
