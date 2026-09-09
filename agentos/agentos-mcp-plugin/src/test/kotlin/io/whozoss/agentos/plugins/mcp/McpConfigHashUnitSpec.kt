package io.whozoss.agentos.plugins.mcp

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class McpConfigHashUnitSpec : StringSpec({

    // ----- stdio -----

    "identical stdio configs produce the same hash" {
        val a = McpServerConfig(command = "docker", args = listOf("run", "--rm", "img"))
        val b = McpServerConfig(command = "docker", args = listOf("run", "--rm", "img"))
        a.configHash() shouldBe b.configHash()
    }

    "different commands produce different hashes" {
        val a = McpServerConfig(command = "docker")
        val b = McpServerConfig(command = "npx")
        a.configHash() shouldNotBe b.configHash()
    }

    "different args produce different hashes" {
        val a = McpServerConfig(command = "docker", args = listOf("run", "img-a"))
        val b = McpServerConfig(command = "docker", args = listOf("run", "img-b"))
        a.configHash() shouldNotBe b.configHash()
    }

    "arg order matters" {
        val a = McpServerConfig(command = "npx", args = listOf("-y", "server"))
        val b = McpServerConfig(command = "npx", args = listOf("server", "-y"))
        a.configHash() shouldNotBe b.configHash()
    }

    "env key order does not matter" {
        val a = McpServerConfig(command = "docker", env = mapOf("A" to "1", "B" to "2"))
        val b = McpServerConfig(command = "docker", env = mapOf("B" to "2", "A" to "1"))
        a.configHash() shouldBe b.configHash()
    }

    "different env values produce different hashes" {
        val a = McpServerConfig(command = "docker", env = mapOf("TOKEN" to "secret1"))
        val b = McpServerConfig(command = "docker", env = mapOf("TOKEN" to "secret2"))
        a.configHash() shouldNotBe b.configHash()
    }

    "different cwd produces different hash" {
        val a = McpServerConfig(command = "docker", cwd = "/path/a")
        val b = McpServerConfig(command = "docker", cwd = "/path/b")
        a.configHash() shouldNotBe b.configHash()
    }

    "idleTimeoutMinutes does not affect hash" {
        val a = McpServerConfig(command = "docker", idleTimeoutMinutes = 5)
        val b = McpServerConfig(command = "docker", idleTimeoutMinutes = 30)
        // idle timeout is pool policy, not server behaviour
        a.configHash() shouldBe b.configHash()
    }

    // ----- HTTP -----

    "identical HTTP configs produce the same hash" {
        val a = McpServerConfig(url = "https://mcp.example.com/sse", authToken = "tok")
        val b = McpServerConfig(url = "https://mcp.example.com/sse", authToken = "tok")
        a.configHash() shouldBe b.configHash()
    }

    "different URLs produce different hashes" {
        val a = McpServerConfig(url = "https://mcp-a.example.com/sse")
        val b = McpServerConfig(url = "https://mcp-b.example.com/sse")
        a.configHash() shouldNotBe b.configHash()
    }

    "different authTokens produce different hashes" {
        val a = McpServerConfig(url = "https://mcp.example.com/sse", authToken = "token-a")
        val b = McpServerConfig(url = "https://mcp.example.com/sse", authToken = "token-b")
        a.configHash() shouldNotBe b.configHash()
    }

    "HTTP without token and HTTP with token produce different hashes" {
        val a = McpServerConfig(url = "https://mcp.example.com/sse")
        val b = McpServerConfig(url = "https://mcp.example.com/sse", authToken = "token")
        a.configHash() shouldNotBe b.configHash()
    }

    // ----- transport isolation -----

    "stdio and HTTP configs with same text do not collide" {
        // Pathological case: command value equals url value
        val stdio = McpServerConfig(command = "https://mcp.example.com/sse")
        val http = McpServerConfig(url = "https://mcp.example.com/sse")
        stdio.configHash() shouldNotBe http.configHash()
    }
})
