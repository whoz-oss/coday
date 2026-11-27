package io.whozoss.agentos.plugins.kotlin

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import io.mockk.every
import io.mockk.mockk
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolTimeout
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.MarkupContent
import org.eclipse.lsp4j.services.LanguageServer
import org.eclipse.lsp4j.services.TextDocumentService
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.writeText
import kotlin.system.measureTimeMillis

/** Tools answering a question about Kotlin code that a text search would answer badly. */
private val navigationTools =
    setOf(
        "kotlin_lsp_hover",
        "kotlin_lsp_definition",
        "kotlin_lsp_type_definition",
        "kotlin_lsp_implementation",
        "kotlin_lsp_references",
        "kotlin_lsp_call_hierarchy_incoming",
        "kotlin_lsp_call_hierarchy_outgoing",
        "kotlin_lsp_type_hierarchy",
        "kotlin_lsp_rename",
        "kotlin_lsp_workspace_symbols",
        "kotlin_lsp_document_symbols",
    )

/** Tools working on a position or on a whole file: no text search competes with them. */
private val positionalTools =
    setOf(
        "kotlin_lsp_completion",
        "kotlin_lsp_signature_help",
        "kotlin_lsp_diagnostics",
        "kotlin_lsp_code_actions",
        "kotlin_lsp_formatting",
        "kotlin_lsp_rename_file",
        "kotlin_lsp_inlay_hints",
    )

/** A language server stub that answers hovers, for one call; it counts how many times the pool started it. */
private class FakeServer {
    val starts = AtomicInteger()

    private val textDocument = mockk<TextDocumentService>()
    private val languageServer =
        mockk<LanguageServer>().also { server -> every { server.textDocumentService } returns textDocument }

    init {
        every { textDocument.hover(any()) } returns
            CompletableFuture.completedFuture(Hover().also { it.setContents(MarkupContent("markdown", "fun load(): List<Agent>")) })
    }

    val connection =
        object : LanguageServerConnection {
            override val server: LanguageServer = languageServer

            override fun isAlive(): Boolean = true

            override fun syncFile(filePath: String): String = Path.of(filePath).toUri().toString()

            override fun closeFile(filePath: String) = Unit

            override fun close() = Unit
        }

    fun pool(): LspConnectionPool =
        LspConnectionPool(
            resolver = KotlinLspBinaryResolver(env = { null }, userHome = "/home/test", isExecutable = { true }),
            userHome = "/home/test",
            connect = { _, _, _ ->
                starts.incrementAndGet()
                connection
            },
        )
}

private fun toolsOf(
    root: Path,
    pool: LspConnectionPool,
    configName: String? = null,
): Map<String, StandardTool<*>> =
    kotlinLspTools(configName = configName, run = KotlinLspRun(projectRoot = root, pool = pool)).associateBy { it.name }

class KotlinLspToolsUnitSpec :
    StringSpec({
        val context = ToolContext(UUID.randomUUID(), null, null, emptyList())

        fun call(
            tool: StandardTool<*>,
            json: String?,
        ) = runBlocking { tool.executeWithJson(json = json, context = context) }

        fun withProject(block: (root: Path) -> Unit) {
            val root = Files.createTempDirectory("agentos-kotlin-tools").toRealPath()
            try {
                block(root)
            } finally {
                root.toFile().deleteRecursively()
            }
        }

        "every tool is classified, so a new tool forces a decision about its description" {
            withProject { root ->
                // given
                val names = toolsOf(root = root, pool = FakeServer().pool()).keys

                // when / then
                (names - navigationTools - positionalTools).shouldBeEmpty()
                (navigationTools + positionalTools - names).shouldBeEmpty()
            }
        }

        "every navigation tool starts by telling to use it instead of a text search" {
            withProject { root ->
                // given
                val tools = toolsOf(root = root, pool = FakeServer().pool())

                // when
                val missing =
                    navigationTools.filterNot {
                        tools
                            .getValue(
                                it,
                            ).description
                            .startsWith(KotlinToolTexts.PREFER_OVER_TEXT_SEARCH)
                    }

                // then
                missing.shouldBeEmpty()
            }
        }

        "a name is prefixed with the config name when there is one" {
            withProject { root ->
                toolsOf(root = root, pool = FakeServer().pool(), configName = "APP").keys.all { it.startsWith("APP__kotlin_lsp_") } shouldBe
                    true
            }
        }

        "every tool declares a version and a schema requiring timeoutSeconds" {
            withProject { root ->
                toolsOf(root = root, pool = FakeServer().pool()).values.forEach { tool ->
                    // given
                    val schema = jacksonObjectMapper().readTree(tool.inputSchema)

                    // then
                    tool.version shouldBe "1.0.0"
                    schema.get("required").map { it.asText() } shouldContain "timeoutSeconds"
                    schema.get("additionalProperties").asBoolean() shouldBe false
                }
            }
        }

        "a missing timeoutSeconds answers a KOTLIN_ERROR naming it, and starts no server" {
            withProject { root ->
                // given
                val server = FakeServer()
                val hover = toolsOf(root = root, pool = server.pool()).getValue("kotlin_lsp_hover")

                // when
                val result = call(tool = hover, json = """{"symbol":"AgentLoader"}""")

                // then
                result.success shouldBe false
                result.errorType shouldBe "KOTLIN_ERROR"
                result.output shouldStartWith "ERROR"
                result.output shouldContain "timeoutSeconds"
                server.starts.get() shouldBe 0
            }
        }

        "a missing input answers a KOTLIN_ERROR naming timeoutSeconds" {
            withProject { root ->
                // when
                val result = call(tool = toolsOf(root = root, pool = FakeServer().pool()).getValue("kotlin_lsp_hover"), json = null)

                // then
                result.errorType shouldBe "KOTLIN_ERROR"
                result.output shouldContain "timeoutSeconds"
            }
        }

        listOf(0, -5, ToolTimeout.MAX_SECONDS + 1).forEach { invalid ->
            "timeoutSeconds=$invalid is refused before the server is reached" {
                withProject { root ->
                    // given
                    val server = FakeServer()
                    val hover = toolsOf(root = root, pool = server.pool()).getValue("kotlin_lsp_hover")

                    // when
                    val result = call(tool = hover, json = """{"symbol":"AgentLoader","timeoutSeconds":$invalid}""")

                    // then
                    result.errorType shouldBe "KOTLIN_ERROR"
                    result.output shouldContain "between 1 and ${ToolTimeout.MAX_SECONDS}"
                    server.starts.get() shouldBe 0
                }
            }
        }

        "a file outside the project is refused, and no server is started for it" {
            withProject { root ->
                // given
                val server = FakeServer()
                val diagnostics = toolsOf(root = root, pool = server.pool()).getValue("kotlin_lsp_diagnostics")

                // when
                val result = call(tool = diagnostics, json = """{"file":"../outside.kt","timeoutSeconds":5}""")

                // then
                result.errorType shouldBe "KOTLIN_ERROR"
                result.output shouldContain "outside the project"
                server.starts.get() shouldBe 0
            }
        }

        "a sensitive file is refused, and no server is started for it" {
            withProject { root ->
                // given
                val server = FakeServer()
                val diagnostics = toolsOf(root = root, pool = server.pool()).getValue("kotlin_lsp_diagnostics")

                // when
                val result = call(tool = diagnostics, json = """{"file":".env","timeoutSeconds":5}""")

                // then
                result.errorType shouldBe "KOTLIN_ERROR"
                result.output shouldContain "sensitive"
                server.starts.get() shouldBe 0
            }
        }

        "a line without a file is refused before the server is reached" {
            withProject { root ->
                // given
                val server = FakeServer()
                val hover = toolsOf(root = root, pool = server.pool()).getValue("kotlin_lsp_hover")

                // when
                val result = call(tool = hover, json = """{"symbol":"load","line":3,"timeoutSeconds":5}""")

                // then
                result.output shouldContain "`line` requires `file`"
                server.starts.get() shouldBe 0
            }
        }

        "a project directory removed after the tools were built is answered as an error" {
            withProject { root ->
                // given
                val missing = Files.createDirectory(root.resolve("gone"))
                val hover = toolsOf(root = missing, pool = FakeServer().pool()).getValue("kotlin_lsp_hover")
                Files.delete(missing)

                // when
                val result = call(tool = hover, json = """{"symbol":"AgentLoader","timeoutSeconds":5}""")

                // then
                result.errorType shouldBe "KOTLIN_ERROR"
                result.output shouldContain "does not exist"
            }
        }

        "the timeout bounds the server start, so a server that hangs on start does not hold the agent" {
            withProject { root ->
                // given
                val hangingPool =
                    LspConnectionPool(
                        resolver = KotlinLspBinaryResolver(env = { null }, userHome = "/home/test", isExecutable = { true }),
                        userHome = "/home/test",
                        connect = { _, _, _ ->
                            Thread.sleep(60_000)
                            error("never reached")
                        },
                    )
                val hover = toolsOf(root = root, pool = hangingPool).getValue("kotlin_lsp_hover")

                // when
                var result = call(tool = hover, json = null)
                val elapsed = measureTimeMillis { result = call(tool = hover, json = """{"symbol":"AgentLoader","timeoutSeconds":1}""") }

                // then
                result.errorType shouldBe "KOTLIN_ERROR"
                result.output shouldContain "did not answer within 1 seconds"
                elapsed shouldBeLessThan 5_000L
            }
        }

        "an unavailable binary reaches the agent as an error naming how to fix it" {
            withProject { root ->
                // given
                val noBinaryPool =
                    LspConnectionPool(resolver = KotlinLspBinaryResolver(env = { null }, userHome = "/home/test", isExecutable = { false }))
                val hover = toolsOf(root = root, pool = noBinaryPool).getValue("kotlin_lsp_hover")

                // when
                val result = call(tool = hover, json = """{"symbol":"AgentLoader","timeoutSeconds":5}""")

                // then
                result.errorType shouldBe "KOTLIN_ERROR"
                result.output shouldContain ENV_SERVER_BINARY
            }
        }

        "hover resolves the symbol on the given line and answers what the server says, starting the server once" {
            withProject { root ->
                // given
                root.resolve("A.kt").writeText("class A {\n    val agents = load()\n}\n")
                val server = FakeServer()
                val hover = toolsOf(root = root, pool = server.pool()).getValue("kotlin_lsp_hover")

                // when
                val first = call(tool = hover, json = """{"symbol":"load","file":"A.kt","line":2,"timeoutSeconds":5}""")
                val second = call(tool = hover, json = """{"symbol":"load","file":"A.kt","line":2,"timeoutSeconds":5}""")

                // then
                first.success shouldBe true
                first.output shouldContain "load — A.kt:2"
                first.output shouldContain "fun load(): List<Agent>"
                second.success shouldBe true
                server.starts.get() shouldBe 1
            }
        }

        "an unresolved symbol is answered as an error that says how to refine the call" {
            withProject { root ->
                // given
                root.resolve("A.kt").writeText("class A\n")
                val hover = toolsOf(root = root, pool = FakeServer().pool()).getValue("kotlin_lsp_hover")

                // when
                val result = call(tool = hover, json = """{"symbol":"load","file":"A.kt","line":1,"timeoutSeconds":5}""")

                // then
                result.errorType shouldBe "KOTLIN_ERROR"
                result.output shouldContain "'load' not found on line 1"
                result.output shouldNotContain "fun load"
            }
        }
    })
