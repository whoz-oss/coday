package io.whozoss.agentos.git

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.annotation.EnabledIf
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.whozoss.agentos.git.core.GitExecutionProperties
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import kotlin.io.path.exists
import kotlin.io.path.readText

/** Real subprocesses: setup output is bounded and one deadline covers abandoned children. */
@EnabledIf(PosixOnly::class)
class WorktreeSetupRunnerSpec :
    StringSpec({
        timeout = 15_000
        val fixtures = mutableListOf<Path>()

        fun directory(): Path = Files.createTempDirectory("agentos-setup-spec-").also { fixtures.add(it) }

        fun assertStopped(process: ProcessHandle) {
            val deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos()
            while (process.isAlive && System.nanoTime() < deadline) Thread.sleep(10)
            process.isAlive shouldBe false
        }

        fun settings(command: String) = GitRepositorySettings(
            configId = UUID.randomUUID(),
            namespaceId = UUID.randomUUID(),
            repositoryUrl = "https://example.com/repository.git",
            mainBranch = "main",
            serviceAuthSettingId = UUID.randomUUID(),
            autoWorktreeForRootCases = true,
            setupCommand = command,
        )

        afterTest {
            fixtures.forEach { directory ->
                val pidFile = directory.resolve("child.pid")
                if (pidFile.exists()) {
                    pidFile.readText().trim().toLongOrNull()?.let { pid ->
                        ProcessHandle.of(pid).ifPresent { if (it.isAlive) it.destroyForcibly() }
                    }
                }
                directory.toFile().deleteRecursively()
            }
            fixtures.clear()
        }

        "setup pins the pnpm store inside its own support directory instead of deriving it from HOME" {
            val directory = directory()
            val home = directory()
            val runner = WorktreeSetupRunner(GitExecutionProperties(setupTimeout = Duration.ofSeconds(5)))
            runner.run(settings("printenv npm_config_store_dir > store.txt"), directory, home)
            directory.resolve("store.txt").readText().trim() shouldBe
                home.toAbsolutePath().normalize().resolve(WorktreeSetupRunner.PNPM_STORE_DIRECTORY).toString()
        }

        "setup failure keeps the exact message whatever the command prints" {
            val directory = directory()
            val runner = WorktreeSetupRunner(GitExecutionProperties(setupTimeout = Duration.ofSeconds(5)))
            val error = shouldThrow<IllegalStateException> {
                runner.run(settings("printf 'ERR_PNPM_X boom' >&2; exit 1"), directory, Files.createTempDirectory("setup-home-"))
            }
            error.message shouldBe "Setup command failed (exit 1)"
        }

        "a command that cannot start is reported without its cause leaking into the message" {
            val missing = directory().resolve("missing")
            val runner = WorktreeSetupRunner(GitExecutionProperties(setupTimeout = Duration.ofSeconds(5)))
            val error = shouldThrow<IllegalStateException> {
                runner.run(settings("true"), missing, Files.createTempDirectory("setup-home-"))
            }
            error.message shouldBe "Setup command could not start"
        }

        "redactSecrets masks userinfo in URLs" {
            WorktreeSetupRunner.redactSecrets("git clone https://bot:s3cret@example.com/org/repo.git") shouldBe
                "git clone https://***@example.com/org/repo.git"
        }

        "redactSecrets masks Authorization headers" {
            WorktreeSetupRunner.redactSecrets("Authorization: Bearer abc.def-123") shouldBe "Authorization: Bearer ***"
            WorktreeSetupRunner.redactSecrets("authorization: Basic dXNlcjpwYXNz") shouldBe "authorization: Basic ***"
        }

        "redactSecrets masks well-known token shapes" {
            val tokens = listOf(
                "ghp_" + "a".repeat(36),
                "gho_" + "B".repeat(30),
                "github_pat_" + "c1_".repeat(10),
                "npm_" + "d4".repeat(18),
                "glpat-" + "e-_5".repeat(6),
            )
            tokens.forEach { token ->
                WorktreeSetupRunner.redactSecrets("failed with $token here") shouldBe "failed with *** here"
            }
        }

        "redactSecrets masks key value credentials and keeps the key" {
            WorktreeSetupRunner.redactSecrets("//registry.npmjs.org/:_authToken=abc123") shouldBe
                "//registry.npmjs.org/:_authToken=***"
            WorktreeSetupRunner.redactSecrets("_auth=dXNlcjpwYXNz") shouldBe "_auth=***"
            WorktreeSetupRunner.redactSecrets("PASSWORD=hunter2 next") shouldBe "PASSWORD=***" + " next"
            WorktreeSetupRunner.redactSecrets("token = \"quoted value\"") shouldBe "token = ***"
        }

        "redactSecrets leaves ordinary text intact" {
            val text = "ERR_PNPM_NO_MATCHING_VERSION No matching version found for left-pad@^9 (exit 1)\nat https://registry.npmjs.org/left-pad"
            WorktreeSetupRunner.redactSecrets(text) shouldBe text
        }

        "setup logs the end of an output larger than the limit, and still keeps it out of the exception" {
            val directory = directory()
            val logger = org.slf4j.LoggerFactory.getLogger(WorktreeSetupRunner::class.java) as ch.qos.logback.classic.Logger
            val logs = ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>().also { it.start() }
            logger.addAppender(logs)
            try {
                val runner = WorktreeSetupRunner(GitExecutionProperties(setupTimeout = Duration.ofSeconds(5), maxOutputChars = 128))
                val error = shouldThrow<IllegalStateException> {
                    runner.run(
                        settings("seq 1 20000\nseq 1 20000 >&2\nprintf 'ERR_PNPM_FINAL boom token=hunter2' >&2\nexit 1"),
                        directory,
                        Files.createTempDirectory("setup-home-"),
                    )
                }
                error.message shouldBe "Setup command failed (exit 1)"
                val failure = logs.list.single { it.level == ch.qos.logback.classic.Level.ERROR }.formattedMessage
                failure shouldContain "exit 1"
                failure shouldContain "--- stderr (truncated: beginning dropped, last 128 chars kept) ---"
                failure shouldContain "ERR_PNPM_FINAL boom token=***"
                failure shouldNotContain "hunter2"
            } finally {
                logger.detachAppender(logs)
                logs.stop()
            }
        }

        "setup drains large output and keeps it out of its failure message" {
            val directory = directory()
            val runner = WorktreeSetupRunner(GitExecutionProperties(setupTimeout = Duration.ofSeconds(5), maxOutputChars = 128))
            val error = shouldThrow<IllegalStateException> {
                runner.run(settings("yes log | head -c 16777216\nprintf failure >&2\nexit 1"), directory, Files.createTempDirectory("setup-home-"))
            }
            error.message shouldBe "Setup command failed (exit 1)"
        }

        "setup has one deadline even when its command abandons a child holding an output pipe" {
            val directory = directory()
            val runner = WorktreeSetupRunner(GitExecutionProperties(setupTimeout = Duration.ofSeconds(1)))
            val started = System.nanoTime()
            val error = shouldThrow<IllegalStateException> {
                runner.run(settings("trap - EXIT\nsleep 30 &\necho ${'$'}! > child.pid\nsleep 0.3"), directory, Files.createTempDirectory("setup-home-"))
            }
            error.message!! shouldContain "Setup command timed out"
            (Duration.ofNanos(System.nanoTime() - started) < Duration.ofSeconds(3)) shouldBe true
            val childPid = directory.resolve("child.pid").readText().trim().toLong()
            ProcessHandle.of(childPid).ifPresent { assertStopped(it) }
        }
    })
