package io.whozoss.agentos.plugins.gradle

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import java.util.UUID

/**
 * Stopping a case cancels the coroutine running a tool. The Gradle tools block on the Gradle process, so
 * `execute` must run them interruptibly: the cancellation then interrupts the waiting thread, which kills the
 * whole process tree. Without it the call would only end when Gradle does, and Gradle would keep working.
 */
class GradleToolCancellationUnitSpec : StringSpec({
    val ctx = ToolContext(UUID.randomUUID(), null, null, emptyList())

    class Case(
        val label: String,
        val tool: (GradleRunner) -> StandardTool<*>,
        val json: String,
    )

    val cases =
        listOf(
            Case("gradle_compile", { GradleCompileTool(it) }, """{"timeoutSeconds":60}"""),
            Case("gradle_tests", { GradleTestTool(it) }, """{"timeoutSeconds":60}"""),
            Case("gradle_dependency_insight", { GradleDependencyInsightTool(it) }, """{"dependency":"x","timeoutSeconds":60}"""),
        )

    /** A fake gradlew that starts a background child, records its pid, then waits: only a tree kill ends it. */
    fun hangingGradlew(root: File): File {
        root.resolve("gradlew").apply {
            writeText("#!/bin/sh\nsleep 60 &\necho \$! > '${root.path}/child.pid'\nwait\n")
            setExecutable(true)
        }
        return root.resolve("child.pid")
    }

    fun readPid(file: File): Long? = file.takeIf { it.isFile }?.readText()?.trim()?.toLongOrNull()

    fun isAlive(pid: Long): Boolean = ProcessHandle.of(pid).map { it.isAlive }.orElse(false)

    suspend fun awaitPid(file: File): Long =
        withTimeout(5_000) {
            while (readPid(file) == null) delay(20)
            checkNotNull(readPid(file))
        }

    cases.forEach { case ->
        "${case.label}: cancelling the run ends the call and kills the Gradle process tree" {
            val root = Files.createTempDirectory("agentos-gradle-cancel").toFile()
            try {
                val pidFile = hangingGradlew(root)
                val call = launch(Dispatchers.Default) { case.tool(GradleRunner(root)).executeWithJson(case.json, ctx) }
                val childPid = awaitPid(pidFile)

                call.cancel()

                // Not interruptible: join would wait for the 60 s of the fake gradle and hit the timeout.
                withTimeout(5_000) { call.join() }
                call.isCancelled shouldBe true
                withTimeout(5_000) { while (isAlive(childPid)) delay(20) }
            } finally {
                root.deleteRecursively()
            }
        }
    }

    "execute still maps the answer of a normal run" {
        val root = Files.createTempDirectory("agentos-gradle-execute-ok").toFile()
        try {
            root.resolve("gradlew").apply {
                writeText("#!/bin/sh\nexit 0\n")
                setExecutable(true)
            }

            val result = GradleCompileTool(GradleRunner(root)).executeWithJson("""{"timeoutSeconds":60}""", ctx)

            result.success shouldBe true
            result.output shouldContain "SUCCESS"
        } finally {
            root.deleteRecursively()
        }
    }

    "execute maps a failing run to a GRADLE_FAILURE error" {
        val root = Files.createTempDirectory("agentos-gradle-execute-ko").toFile()
        try {
            root.resolve("gradlew").apply {
                writeText("#!/bin/sh\nexit 1\n")
                setExecutable(true)
            }

            val result = GradleCompileTool(GradleRunner(root)).executeWithJson("""{"timeoutSeconds":60}""", ctx)

            result.success shouldBe false
            result.errorType shouldBe "GRADLE_FAILURE"
        } finally {
            root.deleteRecursively()
        }
    }
})
