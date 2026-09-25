package io.whozoss.agentos.git

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.whozoss.agentos.exception.ConflictException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

class WorkspaceProcessGuardSpec : StringSpec({
    timeout = 15_000
    val directories = mutableListOf<Path>()
    fun directory(): Path = Files.createTempDirectory("agentos-process-guard-").also { directories.add(it) }
    fun inspector(directory: Path, command: String): WorkspaceProcessGuard {
        val script = directory.resolve("inspector.sh")
        script.writeText("#!/bin/sh\n$command\n")
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"))
        return WorkspaceProcessGuard(script.toString(), Duration.ofSeconds(1))
    }
    fun assertStopped(process: ProcessHandle) {
        val deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos()
        while (process.isAlive && System.nanoTime() < deadline) Thread.sleep(10)
        process.isAlive shouldBe false
    }
    afterTest {
        directories.forEach { directory ->
            directory.resolve("child.pid").takeIf { it.exists() }?.readText()?.trim()?.toLongOrNull()?.let { pid ->
                ProcessHandle.of(pid).ifPresent { if (it.isAlive) it.destroyForcibly() }
            }
            directory.toFile().deleteRecursively()
        }
        directories.clear()
    }

    "an independent process holding the workspace prevents deletion even without an in-memory registry" {
        val path = directory()
        val process = ProcessBuilder("sleep", "120").directory(path.toFile()).start()
        try { shouldThrow<ConflictException> { WorkspaceProcessGuard().assertIdle(path) } }
        finally { process.destroyForcibly(); process.waitFor(10, TimeUnit.SECONDS) }
        WorkspaceProcessGuard().assertIdle(path)
    }
    "an unavailable process inspector fails closed" {
        shouldThrow<ConflictException> { WorkspaceProcessGuard("/nonexistent/lsof").assertIdle(directory()) }
    }
    "only a complete empty no-match result permits cleanup" {
        val path = directory()
        inspector(path, "exit 1").assertIdle(path)
        for (command in listOf("exit 0", "echo 123; exit 1", "echo warning >&2; exit 1")) {
            shouldThrow<ConflictException> { inspector(path, command).assertIdle(path) }
        }
    }
    "truncated blank output fails closed on either pipe" {
        val path = directory()
        for (redirect in listOf("", " >&2")) {
            // Whitespace would pass isNotBlank; truncation must independently forbid cleanup.
            val guard = inspector(path, "head -c 1048576 /dev/zero | tr '\\000' ' '$redirect\nexit 1")
            shouldThrow<ConflictException> { guard.assertIdle(path) }.message!! shouldContain "incomplete"
        }
    }
    "inspection timeout stops descendants and refuses cleanup" {
        val path = directory()
        val guard = inspector(path, "sleep 30 &\necho ${'$'}! > '${path.resolve("child.pid")}'\nwait")
        shouldThrow<ConflictException> { guard.assertIdle(path) }.message!! shouldContain "timed out"
        val pid = path.resolve("child.pid").readText().trim().toLong()
        ProcessHandle.of(pid).ifPresent { assertStopped(it) }
    }
    "interruption stops inspection and preserves the caller interrupt flag" {
        val path = directory()
        val guard = inspector(path, "sleep 30 &\necho ${'$'}! > '${path.resolve("child.pid")}'\nwait")
        val interrupted = AtomicBoolean()
        val error = AtomicReference<Throwable>()
        val caller = Thread.ofPlatform().start {
            try { guard.assertIdle(path) }
            catch (e: Throwable) { error.set(e) }
            finally { interrupted.set(Thread.currentThread().isInterrupted) }
        }
        try {
            val pidFile = path.resolve("child.pid")
            val deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos()
            while ((!pidFile.exists() || pidFile.readText().isBlank()) && System.nanoTime() < deadline) Thread.sleep(10)
            val child = ProcessHandle.of(pidFile.readText().trim().toLong()).orElseThrow()
            caller.interrupt()
            caller.join(3000)
            caller.isAlive shouldBe false
            (error.get() is ConflictException) shouldBe true
            error.get().message!! shouldContain "interrupted"
            interrupted.get() shouldBe true
            assertStopped(child)
        } finally { caller.interrupt(); caller.join(3000) }
    }
})
