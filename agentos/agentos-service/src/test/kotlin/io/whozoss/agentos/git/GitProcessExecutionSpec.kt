package io.whozoss.agentos.git

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.Duration
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/** Real subprocesses exercise pipe draining, timeouts and cancellation independently of Git. */
class GitProcessExecutionSpec :
    StringSpec({
        timeout = 15_000
        val fixtures = mutableListOf<Path>()

        fun fixture(command: String): Pair<Path, GitCommandRunner> {
            val directory = Files.createTempDirectory("agentos-process-spec-").also { fixtures.add(it) }
            val binary = directory.resolve("command.sh")
            binary.writeText("#!/bin/sh\n$command\n")
            Files.setPosixFilePermissions(binary, PosixFilePermissions.fromString("rwx------"))
            return directory to GitCommandRunner(
                GitExecutionProperties(
                    binary = binary.toString(),
                    defaultTimeout = Duration.ofSeconds(1),
                    maxOutputChars = 128,
                ),
            )
        }

        fun child(directory: Path): ProcessHandle {
            val deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos()
            val pidFile = directory.resolve("child.pid")
            while ((!pidFile.exists() || pidFile.readText().isBlank()) && System.nanoTime() < deadline) Thread.sleep(10)
            return ProcessHandle.of(pidFile.readText().trim().toLong()).orElseThrow()
        }

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

        "large stdout and stderr are drained without retaining more than the configured limit" {
            val (directory, runner) = fixture(
                "head -c 8388608 /dev/zero\n" +
                    "head -c 8388608 /dev/zero >&2",
            )
            val result = runner.run(GitInvocation(emptyList(), workingDirectory = directory, timeout = Duration.ofSeconds(5)))
            check(result is GitCommandResult.Completed) { "Expected completion, got $result" }
            result.exitCode shouldBe 0
            result.stdout.length shouldBe 128
            result.stderr.length shouldBe 128
            result.truncated shouldBe true
        }

        "git timeout terminates the process child as well as its parent" {
            val (directory, runner) = fixture("sleep 30 &\necho ${'$'}! > child.pid\nwait")
            val result = runner.run(GitInvocation(emptyList(), workingDirectory = directory))
            (result is GitCommandResult.TimedOut) shouldBe true
            val childPid = directory.resolve("child.pid").readText().trim().toLong()
            ProcessHandle.of(childPid).ifPresent { assertStopped(it) }
        }

        "git output collection shares the deadline when an exited parent leaves a child holding its pipes" {
            val (directory, runner) = fixture("sleep 30 &\necho ${'$'}! > child.pid\nsleep 0.3\nexit 0")
            val started = System.nanoTime()
            val result = runner.run(GitInvocation(emptyList(), workingDirectory = directory))
            (result is GitCommandResult.TimedOut) shouldBe true
            (Duration.ofNanos(System.nanoTime() - started) < Duration.ofSeconds(3)) shouldBe true
            val childPid = directory.resolve("child.pid").readText().trim().toLong()
            ProcessHandle.of(childPid).ifPresent { assertStopped(it) }
        }

        "interrupting a git invocation stops its descendants and preserves the caller interruption" {
            val (directory, runner) = fixture("sleep 30 &\necho ${'$'}! > child.pid\nwait")
            val outcome = AtomicReference<GitCommandResult>()
            val interrupted = AtomicBoolean(false)
            val caller = Thread.ofPlatform().start {
                outcome.set(runner.run(GitInvocation(emptyList(), workingDirectory = directory, timeout = Duration.ofSeconds(30))))
                interrupted.set(Thread.currentThread().isInterrupted)
            }
            val descendant = child(directory)
            caller.interrupt()
            caller.join(3000)
            caller.isAlive shouldBe false
            (outcome.get() is GitCommandResult.Failed) shouldBe true
            interrupted.get() shouldBe true
            assertStopped(descendant)
        }

        "setup drains large output and keeps its failure message bounded" {
            val (directory, _) = fixture("")
            val runner = WorktreeSetupRunner(GitExecutionProperties(setupTimeout = Duration.ofSeconds(5), maxOutputChars = 128))
            val error = shouldThrow<IllegalStateException> {
                runner.run(settings("yes log | head -c 16777216\nprintf failure >&2\nexit 1"), directory)
            }
            error.message!! shouldContain "Setup command failed (exit 1)"
            (error.message!!.length < 200) shouldBe true
        }

        "setup has one deadline even when its command abandons a child holding an output pipe" {
            val (directory, _) = fixture("")
            val runner = WorktreeSetupRunner(GitExecutionProperties(setupTimeout = Duration.ofSeconds(1)))
            val started = System.nanoTime()
            val error = shouldThrow<IllegalStateException> {
                runner.run(settings("trap - EXIT\nsleep 30 &\necho ${'$'}! > child.pid\nsleep 0.3"), directory)
            }
            error.message!! shouldContain "Setup command timed out"
            (Duration.ofNanos(System.nanoTime() - started) < Duration.ofSeconds(3)) shouldBe true
            val childPid = directory.resolve("child.pid").readText().trim().toLong()
            ProcessHandle.of(childPid).ifPresent { assertStopped(it) }
        }
    })
