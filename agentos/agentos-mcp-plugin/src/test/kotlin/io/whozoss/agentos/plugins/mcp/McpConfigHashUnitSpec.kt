package io.whozoss.agentos.plugins.mcp

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class McpConfigHashUnitSpec : StringSpec({

    "identical configs produce the same hash" {
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
})
