package io.whozoss.agentos.git

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.whozoss.agentos.git.core.GitExecutionProperties
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import kotlin.io.path.exists
import kotlin.io.path.readText

/** Real subprocesses: setup output is bounded and one deadline covers abandoned children. */
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

        "setup drains large output and keeps its failure message bounded" {
            val directory = directory()
            val runner = WorktreeSetupRunner(GitExecutionProperties(setupTimeout = Duration.ofSeconds(5), maxOutputChars = 128))
            val error = shouldThrow<IllegalStateException> {
                runner.run(settings("yes log | head -c 16777216\nprintf failure >&2\nexit 1"), directory, Files.createTempDirectory("setup-home-"))
            }
            error.message!! shouldContain "Setup command failed (exit 1)"
            (error.message!!.length < 200) shouldBe true
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
