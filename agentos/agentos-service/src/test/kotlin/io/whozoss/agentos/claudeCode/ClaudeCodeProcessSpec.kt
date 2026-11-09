package io.whozoss.agentos.claudeCode

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.annotation.EnabledIf
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.whozoss.agentos.git.PosixOnly
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText

@EnabledIf(PosixOnly::class)
class ClaudeCodeProcessSpec :
    StringSpec({
        timeout = 30_000
        val directories = mutableListOf<Path>()
        fun directory(): Path = Files.createTempDirectory("agentos-claude-code-test-").also { directories.add(it) }
        afterTest {
            directories.forEach { it.toFile().deleteRecursively() }
            directories.clear()
        }

        fun launcher(
            cli: FakeClaudeCli,
            maxConcurrentProcesses: Int = 4,
            enabled: Boolean = true,
        ) = ClaudeCodeProcessLauncher(
            ClaudeCodeProperties(
                enabled = enabled,
                executable = cli.executable.toString(),
                configRoot = directory(),
                maxConcurrentProcesses = maxConcurrentProcesses,
            ),
        )

        fun request(mcpServer: ClaudeCodeMcpServer? = null) =
            ClaudeCodeLaunchRequest(
                userId = "user-1",
                caseId = "case-1",
                model = "haiku",
                systemPrompt = "You are dev.",
                session = ClaudeCodeSession.Start(UUID.fromString("6c7f4053-1f28-40cf-bc3b-dcb8c2f65454")),
                mcpServer = mcpServer,
            )

        /** Reads the user message, copies the run files the CLI was given, then replays a real turn. */
        fun replaying(fixture: String): FakeClaudeCli {
            val dir = directory()
            return FakeClaudeCli(
                dir,
                """
                read -r message
                printf '%s\n' "${'$'}message" > '$dir/stdin'
                prompt=${'$'}(awk 'prev == "--system-prompt-file" { print } { prev = ${'$'}0 }' '$dir/argv')
                cp "${'$'}prompt" '$dir/prompt'
                mcp=${'$'}(awk 'prev == "--mcp-config" { print } { prev = ${'$'}0 }' '$dir/argv')
                [ -n "${'$'}mcp" ] && cp "${'$'}mcp" '$dir/mcp'
                cat '${FakeClaudeCli.fixture(fixture)}'
                """.trimIndent(),
            )
        }

        "a turn sends the user message and reads the events until the CLI ends" {
            val cli = replaying("text-turn.jsonl")
            val launcher = launcher(cli)

            val events =
                launcher.launch(request()).use { process ->
                    process.sendUserMessage("Say \"OK\"")
                    process.events().toList().also { process.awaitExit(5_000) shouldBe true }
                }

            events.first().shouldBeInstanceOf<ClaudeCodeStreamEvent.Init>()
            events.last().shouldBeInstanceOf<ClaudeCodeStreamEvent.Result>().text shouldBe "OK"
            cli.directory.resolve("stdin").readText().trim() shouldBe
                """{"type":"user","message":{"role":"user","content":"Say \"OK\""}}"""
            cli.directory.resolve("prompt").readText() shouldBe "You are dev."
        }

        "the CLI runs in the case's working directory with nothing but the allowed environment" {
            val cli = replaying("text-turn.jsonl")
            val properties = ClaudeCodeProperties(enabled = true, executable = cli.executable.toString(), configRoot = directory())

            ClaudeCodeProcessLauncher(properties).launch(request()).use { process ->
                process.sendUserMessage("Say OK")
                process.events().toList()
            }

            val configDirectory = properties.configRoot.resolve("user-1").toRealPath()
            Path.of(cli.workingDirectory()).toRealPath() shouldBe configDirectory.resolve("workdirs/case-1")
            configDirectory.resolve("workdirs/case-1").listDirectoryEntries() shouldBe emptyList()
            val environment = cli.environment()
            setOf("PATH", "HOME", "USER", "LOGNAME", "LANG", "CLAUDE_CONFIG_DIR", "CLAUDE_CODE_DISABLE_GIT_INSTRUCTIONS", "DISABLE_AUTOUPDATER")
                .let { allowed -> environment.keys - allowed - PASSED_THROUGH - SHELL_DEFAULTS } shouldBe emptySet()
            Path.of(environment.getValue("CLAUDE_CONFIG_DIR")).toRealPath() shouldBe configDirectory
        }

        "the MCP configuration reaches the CLI as a file deleted with the run" {
            val cli = replaying("tool-turn.jsonl")
            val server = ClaudeCodeMcpServer("http://127.0.0.1:1/mcp/run-1", "t0k3n", Duration.ofMinutes(1))

            val runFiles =
                launcher(cli).launch(request(mcpServer = server)).use { process ->
                    process.sendUserMessage("What time is it?")
                    process.events().toList()
                    val arguments = cli.arguments()
                    listOf(arguments[arguments.indexOf("--system-prompt-file") + 1], arguments[arguments.indexOf("--mcp-config") + 1])
                        .map(Path::of)
                        .onEach { it.exists() shouldBe true }
                }

            cli.directory.resolve("mcp").readText() shouldContain "Bearer t0k3n"
            cli.arguments().joinToString(" ") shouldContain "--allowedTools mcp__agentos__*"
            runFiles.forEach { it.exists() shouldBe false }
            runFiles.first().parent.exists() shouldBe false
        }

        "closing the input ends a CLI that waits for further messages" {
            val cli = FakeClaudeCli(directory(), "cat > /dev/null")

            launcher(cli).launch(request()).use { process ->
                val started = System.nanoTime()
                process.stop()
                process.isAlive shouldBe false
                Duration.ofNanos(System.nanoTime() - started).toMillis() shouldBeLessThan ClaudeCodeProcess.STDIN_GRACE_MILLIS
            }
        }

        "a CLI that ignores its closed input and SIGTERM is killed" {
            val cli = FakeClaudeCli(directory(), "trap '' TERM\nwhile true; do sleep 0.1; done")

            launcher(cli).launch(request()).use { process ->
                val started = System.nanoTime()
                process.stop()
                process.isAlive shouldBe false
                Duration.ofNanos(System.nanoTime() - started).toMillis() shouldBeLessThan 3_000L
            }
        }

        "a turn beyond the concurrency limit waits for a slot" {
            val cli = FakeClaudeCli(directory(), "cat > /dev/null")
            val launcher = launcher(cli, maxConcurrentProcesses = 1)

            val first = launcher.launch(request())
            val second = async { launcher.launch(request()) }
            delay(300)
            second.isCompleted shouldBe false
            first.close()
            withTimeout(5_000) { second.await() }.close()
        }

        "nothing starts while the runtime is disabled" {
            val cli = FakeClaudeCli(directory(), "exit 0")

            shouldThrow<IllegalStateException> { launcher(cli, enabled = false).launch(request()) }
            cli.directory.resolve("argv").exists() shouldBe false
        }

        "the output of a process is read once" {
            val cli = FakeClaudeCli(directory(), "exit 0")

            launcher(cli).launch(request()).use { process ->
                process.events().toList()
                shouldThrow<IllegalStateException> { process.events().toList() }
            }
        }

        "error output is kept for diagnosis" {
            val cli = FakeClaudeCli(directory(), "echo 'No conversation found with session ID: x' >&2\nexit 1")

            launcher(cli).launch(request()).use { process ->
                process.events().toList()
                process.awaitExit(5_000) shouldBe true
                process.exitCode shouldBe 1
                // The error output is drained in the background.
                withTimeout(5_000) { while (process.stderrTail().isEmpty()) delay(20) }
                process.stderrTail().trim() shouldBe "No conversation found with session ID: x"
            }
        }

        "the user and case directories are private to the service user" {
            val cli = FakeClaudeCli(directory(), "exit 0")
            val properties = ClaudeCodeProperties(enabled = true, executable = cli.executable.toString(), configRoot = directory())

            ClaudeCodeProcessLauncher(properties).launch(request()).use { it.events().toList() }

            listOf("user-1", "user-1/workdirs", "user-1/workdirs/case-1").forEach {
                Files.getPosixFilePermissions(properties.configRoot.resolve(it)).map { permission -> permission.name }
                    .shouldContainAll("OWNER_READ", "OWNER_WRITE", "OWNER_EXECUTE")
                Files.getPosixFilePermissions(properties.configRoot.resolve(it)).size shouldBe 3
            }
        }
    }) {
    companion object {
        private val PASSED_THROUGH =
            setOf("TMPDIR", "HTTPS_PROXY", "https_proxy", "HTTP_PROXY", "http_proxy", "NO_PROXY", "no_proxy", "NODE_EXTRA_CA_CERTS")

        /** Variables `/bin/sh` defines itself and `env` then prints. */
        private val SHELL_DEFAULTS = setOf("PWD", "SHLVL", "_", "OLDPWD")
    }
}
