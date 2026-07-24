package io.whozoss.agentos.plugins.file

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
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

        "read-write config produces 9 tools" {
            val config = jacksonObjectMapper().readTree("""{"rootPath": "${tempDir.pathString}"}"""
            .trimIndent())
            val tools = FileToolProvider().provideTools(config, "TEST")
            tools.size shouldBe 9
            tools.map { it.name } shouldContainAll listOf(
                "TEST__listFiles",
                "TEST__readFile",
                "TEST__readAsImage",
                "TEST__readDocument",
                "TEST__readSpreadsheet",
                "TEST__searchFiles",
                "TEST__editFiles",
                "TEST__remove",
                "TEST__moveFile",
            )
        }

        "read-only config produces 6 tools" {
            val config = jacksonObjectMapper().readTree(
                """{"rootPath": "${tempDir.pathString}", "readOnly": true}""",
            )
            val tools = FileToolProvider().provideTools(config, "TEST")
            tools.size shouldBe 6
            tools.map { it.name } shouldContainAll listOf(
                "TEST__listFiles",
                "TEST__readFile",
                "TEST__readAsImage",
                "TEST__readDocument",
                "TEST__readSpreadsheet",
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
            result.output shouldContain "exceeds maximum size"
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
            result.output shouldContain "Access denied"
        }

        "null extraDenyPatterns in JSON handled gracefully" {
            val config = jacksonObjectMapper().readTree(
                """{"rootPath": "${tempDir.pathString}", "extraDenyPatterns": null}""",
            )
            val tools = FileToolProvider().provideTools(config, "TEST")
            tools.size shouldBe 9
        }

        "readMaxSizeMb propagation to ReadAsImageTool" {
            val bigFile = tempDir.resolve("big.png")
            bigFile.toFile().writeBytes(ByteArray(2 * 1024 * 1024))

            val config = jacksonObjectMapper().readTree(
                """{"rootPath": "${tempDir.pathString}", "readMaxSizeMb": 1}""",
            )
            val tools = FileToolProvider().provideTools(config, "TEST")
            val readAsImageTool = tools.first { it.name.contains("readAsImage") }

            val result = readAsImageTool.executeWithJson("""{"filePath": "big.png"}""", ctx)
            result.output shouldContain "exceeds maximum size"
        }

        "extraDenyPatterns propagation to ReadAsImageTool" {
            val secretFile = tempDir.resolve("scan.secret-png")
            secretFile.writeText("not really an image")

            val config = jacksonObjectMapper().readTree(
                """{"rootPath": "${tempDir.pathString}", "extraDenyPatterns": ["*.secret-png"]}""",
            )
            val tools = FileToolProvider().provideTools(config, "TEST")
            val readAsImageTool = tools.first { it.name.contains("readAsImage") }

            val result = readAsImageTool.executeWithJson("""{"filePath": "scan.secret-png"}""", ctx)
            result.output shouldContain "Access denied"
        }

        "readMaxSizeMb propagation to ReadSpreadsheetTool" {
            val bigFile = tempDir.resolve("big.xlsx")
            bigFile.toFile().writeBytes(ByteArray(2 * 1024 * 1024))

            val config = jacksonObjectMapper().readTree(
                """{"rootPath": "${tempDir.pathString}", "readMaxSizeMb": 1}""",
            )
            val tools = FileToolProvider().provideTools(config, "TEST")
            val readSpreadsheetTool = tools.first { it.name.contains("readSpreadsheet") }

            val result = readSpreadsheetTool.executeWithJson("""{"filePath": "big.xlsx"}""", ctx)
            result.output shouldContain "exceeds maximum size"
        }

        "extraDenyPatterns propagation to ReadSpreadsheetTool" {
            val secretFile = tempDir.resolve("data.secret-xlsx")
            secretFile.writeText("not really a workbook")

            val config = jacksonObjectMapper().readTree(
                """{"rootPath": "${tempDir.pathString}", "extraDenyPatterns": ["*.secret-xlsx"]}""",
            )
            val tools = FileToolProvider().provideTools(config, "TEST")
            val readSpreadsheetTool = tools.first { it.name.contains("readSpreadsheet") }

            val result = readSpreadsheetTool.executeWithJson("""{"filePath": "data.secret-xlsx"}""", ctx)
            result.output shouldContain "Access denied"
        }
    }
}
