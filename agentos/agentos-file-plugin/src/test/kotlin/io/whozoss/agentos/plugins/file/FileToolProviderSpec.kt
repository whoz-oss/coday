package io.whozoss.agentos.plugins.file

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.pathString
import kotlin.io.path.writeText

class FileToolProviderSpec : StringSpec() {
    private lateinit var tempDir: Path
    private val ctx = ToolContext(UUID.randomUUID(), null, null, emptyList())

    init {
        beforeEach {
            tempDir = Files.createTempDirectory("test")
        }

        afterEach {
            tempDir.toFile().deleteRecursively()
        }

        "null config returns tools with defaults" {
            val provider = FileToolProvider()
            provider.provideTools(null) shouldBe emptyList()
        }

        "read-write config produces 6 tools" {
            val config = jacksonObjectMapper().readTree("""{"rootPath": "${tempDir.pathString}"}"""
            )
            val tools = FileToolProvider().provideTools(config, "TEST")
            tools.size shouldBe 6
            tools.map { it.name } shouldContainAll listOf(
                "TEST__listFiles",
                "TEST__readFile",
                "TEST__searchFiles",
                "TEST__editFiles",
                "TEST__remove",
                "TEST__moveFile",
            )
        }

        "read-only config produces 3 tools" {
            val config = jacksonObjectMapper().readTree(
                """{"rootPath": "${tempDir.pathString}", "readOnly": true}""",
            )
            val tools = FileToolProvider().provideTools(config, "TEST")
            tools.size shouldBe 3
            tools.map { it.name } shouldContainAll listOf(
                "TEST__listFiles",
                "TEST__readFile",
                "TEST__searchFiles",
            )
        }

        "readMaxSizeMb propagation to ReadFileTool" {
            val bigFile = tempDir.resolve("big.txt")
            bigFile.writeText("x".repeat(2 * 1024 * 1024))

            val config = jacksonObjectMapper().readTree(
                """{"rootPath": "${tempDir.pathString}", "readMaxSizeMb": 1}""",
            )
            val tools = FileToolProvider().provideTools(config, "TEST")
            val readTool = tools.first { it.name.contains("readFile") }

            val result = readTool.executeWithJson("""{"filePath": "big.txt"}""", ctx)
            result shouldContain "exceeds maximum size"
        }

        "extraDenyPatterns propagation to tools" {
            val secretFile = tempDir.resolve("data.custom-secret")
            secretFile.writeText("secret data")

            val config = jacksonObjectMapper().readTree(
                """{"rootPath": "${tempDir.pathString}", "extraDenyPatterns": ["*.custom-secret"]}""",
            )
            val tools = FileToolProvider().provideTools(config, "TEST")
            val readTool = tools.first { it.name.contains("readFile") }

            val result = readTool.executeWithJson("""{"filePath": "data.custom-secret"}""", ctx)
            result shouldContain "Access denied"
        }

        "null extraDenyPatterns in JSON handled gracefully" {
            val config = jacksonObjectMapper().readTree(
                """{"rootPath": "${tempDir.pathString}", "extraDenyPatterns": null}""",
            )
            val tools = FileToolProvider().provideTools(config, "TEST")
            tools.size shouldBe 6
        }
    }
}
