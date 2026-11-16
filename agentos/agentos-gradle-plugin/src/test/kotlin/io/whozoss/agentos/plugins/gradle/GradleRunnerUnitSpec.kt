package io.whozoss.agentos.plugins.gradle

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.File
import java.nio.file.Files
import kotlin.system.measureTimeMillis
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private inline fun <T> withTempDir(
    prefix: String,
    block: (File) -> T,
): T {
    val dir = Files.createTempDirectory(prefix).toFile()
    try {
        return block(dir)
    } finally {
        dir.deleteRecursively()
    }
}

private fun awaitCondition(
    timeoutMillis: Long = 5_000L,
    condition: () -> Boolean,
): Boolean {
    val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
    while (System.nanoTime() < deadline) {
        if (condition()) return true
        Thread.sleep(20)
    }
    return condition()
}

class GradleRunnerUnitSpec :
    StringSpec({

        val tmpDir = File(System.getProperty("java.io.tmpdir"))
        val runner = GradleRunner(tmpDir)

        // ── timeout ─────────────────────────────────────────────────────────────────────────

        "run returns timedOut=true and kills process before wall-clock exceeds timeout" {
            // sleep for 60 s — should be killed well before that
            val command = listOf("sleep", "60")
            val elapsed =
                measureTimeMillis {
                    val result = runner.run(command, timeout = 200.milliseconds)
                    result.timedOut shouldBe true
                    result.exitCode shouldBe -1
                }
            // Allow generous headroom (5 s) — on any sane machine this is < 2 s
            elapsed shouldBeLessThan 5_000L
        }

        "timeout result includes partial output emitted before kill" {
            // Write a line, then sleep so it gets killed mid-run
            val command =
                listOf(
                    "sh",
                    "-c",
                    "echo 'partial output'; sleep 60",
                )
            // 1 s leaves the shell ample time to echo before the kill, even on a loaded machine
            val result = runner.run(command, timeout = 1.seconds)
            result.timedOut shouldBe true
            // stdout was drained concurrently; the line was flushed before the sleep
            result.rawOutput shouldContain "partial output"
        }

        // ── interruption ────────────────────────────────────────────────────────────────────────

        "interrupting run kills the whole process tree and keeps the interrupt flag" {
            withTempDir("agentos-run-interrupt") { root ->
                val childPidFile = root.resolve("child.pid")
                var failure: Throwable? = null
                var interruptFlagKept = false
                val thread =
                    Thread.ofPlatform().start {
                        try {
                            // background child of the shell: only a tree kill reaches it
                            runner.run(listOf("sh", "-c", "sleep 60 & echo \$! > \"\$1\"; wait", "sh", childPidFile.absolutePath))
                        } catch (e: InterruptedException) {
                            failure = e
                        }
                        interruptFlagKept = Thread.currentThread().isInterrupted
                    }

                fun childPid(): Long? =
                    childPidFile
                        .takeIf { it.isFile }
                        ?.readText()
                        ?.trim()
                        ?.toLongOrNull()
                awaitCondition { childPid() != null } shouldBe true
                val pid = childPid()!!

                thread.interrupt()
                thread.join(5_000)

                thread.isAlive shouldBe false
                failure.shouldBeInstanceOf<InterruptedException>()
                interruptFlagKept shouldBe true
                awaitCondition { !ProcessHandle.of(pid).map { it.isAlive }.orElse(false) } shouldBe true
            }
        }

        // ── drain ───────────────────────────────────────────────────────────────────────────

        "run does not block on a background child that keeps the pipe open after the process exited" {
            val impatient = GradleRunner(tmpDir, drainGrace = 300.milliseconds)
            val elapsed =
                measureTimeMillis {
                    // the shell exits at once, the background sleep still holds stdout open
                    val result = impatient.run(listOf("sh", "-c", "echo done; sleep 3 &"))
                    result.timedOut shouldBe false
                    result.exitCode shouldBe 0
                    result.rawOutput shouldContain "done"
                }
            elapsed shouldBeLessThan 2_000L
        }

        // ── normal exit ──────────────────────────────────────────────────────────────────

        "run captures output and exit code on normal exit" {
            val command = listOf("sh", "-c", "echo hello; exit 0")
            val result = runner.run(command)
            result.timedOut shouldBe false
            result.exitCode shouldBe 0
            result.rawOutput shouldContain "hello"
        }

        "run reports non-zero exit code without marking as timedOut" {
            val command = listOf("sh", "-c", "exit 42")
            val result = runner.run(command)
            result.timedOut shouldBe false
            result.exitCode shouldBe 42
        }

        // ── concurrency ──────────────────────────────────────────────────────────────────

        "two concurrent runs do not block each other beyond their own duration" {
            // Each process sleeps 1 s and then exits. If they were sequential, total ≥ 2 s.
            // If concurrent, total should be close to 1 s.
            val command = listOf("sh", "-c", "sleep 1; echo done")

            val elapsed =
                measureTimeMillis {
                    val futures =
                        (1..2).map {
                            Thread.ofVirtual().start {
                                runner.run(command)
                            }
                        }
                    futures.forEach { it.join() }
                }

            // Both finish in ~1 s wall time, not ~2 s
            elapsed shouldBeLessThan 2_500L
        }

        // ── environment and output bound ─────────────────────────────────────────────────────

        "run injects JAVA_HOME of the running JVM" {
            val result = runner.run(listOf("sh", "-c", "echo \$JAVA_HOME"))

            result.rawOutput.trim() shouldBe System.getProperty("java.home")
        }

        "run lets extraEnv override JAVA_HOME" {
            val result = runner.run(listOf("sh", "-c", "echo \$JAVA_HOME"), extraEnv = mapOf("JAVA_HOME" to "/custom/jdk"))

            result.rawOutput.trim() shouldBe "/custom/jdk"
        }

        "run bounds the output and says so at its end" {
            val result = runner.run(listOf("sh", "-c", "head -c 300000 /dev/zero | tr '\\0' 'x'"))

            result.exitCode shouldBe 0
            result.rawOutput shouldContain "[Gradle output truncated at ${GradleRunner.MAX_OUTPUT_CHARS} chars]"
            (result.rawOutput.length < GradleRunner.MAX_OUTPUT_CHARS + 100) shouldBe true
        }

        // ── resolveGradlew ──────────────────────────────────────────────────────────────────────

        "resolveGradlew finds gradlew in a parent directory" {
            withTempDir("agentos-gradlew-parent") { root ->
                val gradlew = root.resolve("gradlew").also { it.createNewFile() }
                val sub = root.resolve("cli").also { it.mkdirs() }

                GradleRunner(sub).resolveGradlew() shouldBe gradlew.absolutePath
            }
        }

        "resolveGradlew throws and lists gradlew candidates found in direct subdirectories" {
            withTempDir("agentos-gradlew-subdir") { root ->
                val gradlew =
                    root.resolve("backend/gradlew").also {
                        it.parentFile.mkdirs()
                        it.createNewFile()
                    }

                val error = shouldThrow<GradlewNotFoundException> { GradleRunner(root).resolveGradlew() }

                error.message!! shouldContain gradlew.absolutePath
                error.message!! shouldContain "gradlewPath"
            }
        }

        "resolveGradlew throws with a search hint when no gradlew exists anywhere nearby" {
            withTempDir("agentos-gradlew-none") { root ->
                val error = shouldThrow<GradlewNotFoundException> { GradleRunner(root).resolveGradlew() }

                error.message!! shouldContain root.absolutePath
                error.message!! shouldContain "gradlewPath"
                error.message!! shouldContain "file search tool"
            }
        }

        "resolveGradlew returns the explicit path as-is when provided" {
            withTempDir("agentos-gradlew-explicit") { root ->
                GradleRunner(root).resolveGradlew("/some/where/gradlew") shouldBe "/some/where/gradlew"
            }
        }

        "resolveGradlew ignores a blank explicit path and auto-resolves" {
            withTempDir("agentos-gradlew-blank") { root ->
                shouldThrow<GradlewNotFoundException> { GradleRunner(root).resolveGradlew("  ") }
            }
        }

        // ── workDirFor ────────────────────────────────────────────────────────────────────────

        "workDirFor keeps projectDir when gradlew sits in projectDir or an ancestor" {
            withTempDir("agentos-workdir-ancestor") { root ->
                val sub = root.resolve("cli").also { it.mkdirs() }
                val runnerInSub = GradleRunner(sub)

                runnerInSub.workDirFor(root.resolve("gradlew").absolutePath) shouldBe sub
                runnerInSub.workDirFor(sub.resolve("gradlew").absolutePath) shouldBe sub
            }
        }

        "workDirFor uses the gradlew directory when gradlew sits in a subdirectory" {
            withTempDir("agentos-workdir-subdir") { root ->
                val backend = root.resolve("backend").also { it.mkdirs() }

                GradleRunner(root).workDirFor(backend.resolve("gradlew").absolutePath) shouldBe backend
            }
        }

        // ── run workDir ────────────────────────────────────────────────────────────────────

        "run executes the command inside the given workDir" {
            withTempDir("agentos-run-workdir") { root ->
                val backend = root.resolve("backend").also { it.mkdirs() }

                val result = GradleRunner(root).run(listOf("pwd"), workDir = backend)

                result.exitCode shouldBe 0
                result.rawOutput shouldContain "backend"
            }
        }
    })
