package io.whozoss.agentos.plugins.file

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe

class FileAccessConfigTest : StringSpec({

    "should deserialize minimal config with defaults" {
        val json = """{"rootPath": "/tmp/project"}"""
        val config = jacksonObjectMapper().readValue(json, FileAccessConfig::class.java)

        config.rootPath shouldBe "/tmp/project"
        config.readOnly shouldBe false
        config.readMaxSizeMb shouldBe 10
        config.extraDenyPatterns shouldBe emptyList()
    }

    "should deserialize full config" {
        val json = """
        {
            "rootPath": "/tmp/project",
            "readOnly": true,
            "readMaxSizeMb": 25,
            "extraDenyPatterns": ["*.secret", "internal/"]
        }
        """.trimIndent()
        val config = jacksonObjectMapper().readValue(json, FileAccessConfig::class.java)

        config.readOnly shouldBe true
        config.readMaxSizeMb shouldBe 25
        config.extraDenyPatterns shouldBe listOf("*.secret", "internal/")
    }

    "should ignore unknown properties for forward compatibility" {
        val json = """{"rootPath": "/tmp", "futureField": 42}"""
        val config = jacksonObjectMapper().readValue(json, FileAccessConfig::class.java)
        config.rootPath shouldBe "/tmp"
    }

    "should fail deserialization when rootPath is missing" {
        val json = """{"readOnly": true}"""
        shouldThrow<Exception> {
            jacksonObjectMapper().readValue(json, FileAccessConfig::class.java)
        }
    }

    "effectiveDenyPatterns should merge defaults with extras" {
        val config = FileAccessConfig(
            rootPath = "/tmp",
            extraDenyPatterns = listOf("*.secret"),
        )
        config.effectiveDenyPatterns shouldContainAll SensitiveFilePatterns.DEFAULT_PATTERNS
        config.effectiveDenyPatterns shouldContain "*.secret"
    }

    "effectiveDenyPatterns should return only defaults when no extras" {
        val config = FileAccessConfig(rootPath = "/tmp")
        config.effectiveDenyPatterns shouldBe SensitiveFilePatterns.DEFAULT_PATTERNS
    }

    "readMaxSizeBytes should clamp below 1 MB" {
        val config = FileAccessConfig(rootPath = "/tmp", readMaxSizeMb = 0)
        config.readMaxSizeBytes shouldBe 1L * 1024 * 1024
    }

    "readMaxSizeBytes should clamp above 50 MB" {
        val config = FileAccessConfig(rootPath = "/tmp", readMaxSizeMb = 999)
        config.readMaxSizeBytes shouldBe 50L * 1024 * 1024
    }

    "readMaxSizeBytes should convert MB to bytes" {
        val config = FileAccessConfig(rootPath = "/tmp", readMaxSizeMb = 25)
        config.readMaxSizeBytes shouldBe 25L * 1024 * 1024
    }

    "should deserialize null extraDenyPatterns as empty list" {
        val json = """{"rootPath": "/tmp/project", "extraDenyPatterns": null}"""
        val config = jacksonObjectMapper().readValue(json, FileAccessConfig::class.java)
        config.extraDenyPatterns shouldBe emptyList()
    }

    "should deserialize via treeToValue (actual codepath)" {
        val mapper = jacksonObjectMapper()
        val jsonNode = mapper.readTree("""
        {
            "rootPath": "/tmp/project",
            "readOnly": true,
            "readMaxSizeMb": 25,
            "extraDenyPatterns": ["*.secret"]
        }
        """.trimIndent())

        val config = mapper.treeToValue(jsonNode, FileAccessConfig::class.java)

        config.rootPath shouldBe "/tmp/project"
        config.readOnly shouldBe true
        config.readMaxSizeMb shouldBe 25
        config.extraDenyPatterns shouldBe listOf("*.secret")
    }

    "should deserialize via treeToValue with null extraDenyPatterns" {
        val mapper = jacksonObjectMapper()
        val jsonNode = mapper.readTree("""
        {
            "rootPath": "/tmp/project",
            "extraDenyPatterns": null
        }
        """.trimIndent())

        val config = mapper.treeToValue(jsonNode, FileAccessConfig::class.java)

        config.extraDenyPatterns shouldBe emptyList()
    }

    "should deserialize via treeToValue with minimal config" {
        val mapper = jacksonObjectMapper()
        val jsonNode = mapper.readTree("""{"rootPath": "/tmp"}""")

        val config = mapper.treeToValue(jsonNode, FileAccessConfig::class.java)

        config.rootPath shouldBe "/tmp"
        config.readOnly shouldBe false
        config.readMaxSizeMb shouldBe 10
        config.extraDenyPatterns shouldBe emptyList()
    }

    "should deserialize via treeToValue with unknown fields" {
        val mapper = jacksonObjectMapper()
        val jsonNode = mapper.readTree("""{"rootPath": "/tmp", "futureField": 42}""")

        val config = mapper.treeToValue(jsonNode, FileAccessConfig::class.java)

        config.rootPath shouldBe "/tmp"
    }
})
