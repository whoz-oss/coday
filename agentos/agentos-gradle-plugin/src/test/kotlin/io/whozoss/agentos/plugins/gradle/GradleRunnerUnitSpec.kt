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

/** Variables a shell adds by itself to the environment it runs in. */
private val SHELL_OWN_VARIABLES = setOf("PWD", "OLDPWD", "SHLVL", "_")

class GradleRunnerUnitSpec :
    StringSpec({

        val tmpDir = File(System.getProperty("java.io.tmpdir"))
        val runner = GradleRunner(tmpDir)

        // ── timeout ───────────────────────────────────────────────────────────────

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

        // ── interruption ───────────────────────────────────────────────────────────────

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

        // ── drain ───────────────────────────────────────────────────────────────

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

        // ── normal exit ───────────────────────────────────────────────────────────────

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

        // ── concurrency ───────────────────────────────────────────────────────────────

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

        // ── environment and output bound ──────────────────────────────────────────────────────────

        "run sets JAVA_HOME to the configured javaHome" {
            val result = GradleRunner(tmpDir, javaHome = "/configured/jdk").run(listOf("sh", "-c", "echo \$JAVA_HOME"))

            result.rawOutput.trim() shouldBe "/configured/jdk"
        }

        "run leaves the JAVA_HOME of the environment alone without a javaHome" {
            val result = runner.run(listOf("sh", "-c", "echo \$JAVA_HOME"))

            result.rawOutput.trim() shouldBe (System.getenv("JAVA_HOME") ?: "")
        }

        "run lets extraEnv override JAVA_HOME" {
            val result = runner.run(listOf("sh", "-c", "echo \$JAVA_HOME"), extraEnv = mapOf("JAVA_HOME" to "/custom/jdk"))

            result.rawOutput.trim() shouldBe "/custom/jdk"
        }

        "run only passes the allowed variables of the service environment" {
            val names =
                runner
                    .run(listOf("env"))
                    .rawOutput
                    .lines()
                    .filter { it.contains('=') }
                    .map { it.substringBefore('=') }

            names.filterNot { it in SHELL_OWN_VARIABLES || GradleRunner.isInheritedVariable(it) } shouldBe emptyList()
            ("PATH" in names) shouldBe true
        }

        "run lets extraEnv add a variable the default set does not carry" {
            val result = runner.run(listOf("sh", "-c", "echo \$AGENTOS_GRADLE_PROBE"), extraEnv = mapOf("AGENTOS_GRADLE_PROBE" to "yes"))

            result.rawOutput.trim() shouldBe "yes"
        }

        "run passes a variable of the service environment only when the integration lists it" {
            // any variable of the test process that the default set leaves out, if there is one
            val secret = System.getenv().keys.firstOrNull { !GradleRunner.isInheritedVariable(it) }
            if (secret != null) {
                val hidden = GradleRunner(tmpDir).run(listOf("printenv", secret))
                val listed = GradleRunner(tmpDir, inheritedEnvironment = setOf(secret)).run(listOf("printenv", secret))

                hidden.exitCode shouldBe 1
                listed.exitCode shouldBe 0
            }
        }

        "the default environment carries the build basics and no secret-looking variable" {
            listOf("PATH", "HOME", "JAVA_HOME", "LANG", "LC_ALL", "GRADLE_USER_HOME", "HTTPS_PROXY").forEach {
                GradleRunner.isInheritedVariable(it) shouldBe true
            }
            listOf("ANTHROPIC_API_KEY", "OPENAI_API_KEY", "GITHUB_TOKEN", "AWS_SECRET_ACCESS_KEY", "SPRING_NEO4J_AUTHENTICATION_PASSWORD").forEach {
                GradleRunner.isInheritedVariable(it) shouldBe false
            }
        }

        "an integration can add a variable to the default environment" {
            GradleRunner.isInheritedVariable("ORG_GRADLE_PROJECT_token") shouldBe false
            GradleRunner.isInheritedVariable("ORG_GRADLE_PROJECT_token", setOf("ORG_GRADLE_PROJECT_token")) shouldBe true
        }

        "run bounds the output and says so at its end" {
            val result = runner.run(listOf("sh", "-c", "head -c 300000 /dev/zero | tr '\\0' 'x'"))

            result.exitCode shouldBe 0
            result.rawOutput shouldContain "[Gradle output truncated at ${GradleRunner.MAX_OUTPUT_CHARS} chars]"
            (result.rawOutput.length < GradleRunner.MAX_OUTPUT_CHARS + 100) shouldBe true
        }

        // ── resolveGradlew ──────────────────────────────────────────────────────────

        "resolveGradlew finds the gradlew of the project directory" {
            withTempDir("agentos-gradlew-project") { root ->
                val gradlew = root.resolve("gradlew").also { it.createNewFile() }

                GradleRunner(root).resolveGradlew() shouldBe gradlew.path
            }
        }

        "resolveGradlew does not look in the parent directories" {
            withTempDir("agentos-gradlew-no-parent") { root ->
                root.resolve("gradlew").createNewFile()
                val sub = root.resolve("cli").also { it.mkdirs() }

                shouldThrow<GradlewNotFoundException> { GradleRunner(sub).resolveGradlew() }
            }
        }

        "resolveGradlew does not look in the subdirectories, but lists them in the error" {
            withTempDir("agentos-gradlew-subdir") { root ->
                val gradlew =
                    root.resolve("backend/gradlew").also {
                        it.parentFile.mkdirs()
                        it.createNewFile()
                    }

                val error = shouldThrow<GradlewNotFoundException> { GradleRunner(root).resolveGradlew() }

                error.message!! shouldContain gradlew.parentFile.absolutePath
                error.message!! shouldContain "gradlewDirectory"
            }
        }

        "resolveGradlew says it is a configuration issue when there is no gradlew at all" {
            withTempDir("agentos-gradlew-none") { root ->
                val error = shouldThrow<GradlewNotFoundException> { GradleRunner(root).resolveGradlew() }

                error.message!! shouldContain root.absolutePath
                error.message!! shouldContain "gradlewDirectory"
                error.message!! shouldContain "configuration issue"
            }
        }

        "resolveGradlew refuses a gradlew that is a symbolic link leaving the project directory" {
            withTempDir("agentos-gradlew-link") { root ->
                val project = root.resolve("project").also { it.mkdirs() }
                val target = root.resolve("elsewhere").also { it.createNewFile() }
                Files.createSymbolicLink(project.resolve("gradlew").toPath(), target.toPath())

                val error = shouldThrow<GradlewNotFoundException> { GradleRunner(project).resolveGradlew() }

                error.message!! shouldContain "outside the project directory"
            }
        }

        "resolveGradlew accepts a gradlew that is a symbolic link staying inside the project directory" {
            withTempDir("agentos-gradlew-inner-link") { root ->
                val real = root.resolve("tools/gradlew-real").also {
                    it.parentFile.mkdirs()
                    it.createNewFile()
                }
                val link = root.resolve("gradlew")
                Files.createSymbolicLink(link.toPath(), real.toPath())

                GradleRunner(root).resolveGradlew() shouldBe link.path
            }
        }

        // ── includedBuilds / compositeBuildHint ───────────────────────────────────────────

        "resolveGradlew finds the gradlew of the gradlewDirectory, inside the project" {
            withTempDir("agentos-gradlew-dir") { root ->
                val gradlew =
                    root.resolve("backend/gradlew").also {
                        it.parentFile.mkdirs()
                        it.createNewFile()
                    }

                GradleRunner(root, gradlewDirectory = gradlew.parentFile).resolveGradlew() shouldBe gradlew.path
            }
        }

        "resolveGradlew prefers the gradlewDirectory over the gradlew of the project directory" {
            withTempDir("agentos-gradlew-prefer") { root ->
                root.resolve("gradlew").createNewFile()
                val nested =
                    root.resolve("backend/gradlew").also {
                        it.parentFile.mkdirs()
                        it.createNewFile()
                    }

                GradleRunner(root, gradlewDirectory = nested.parentFile).resolveGradlew() shouldBe nested.path
            }
        }

        "resolveGradlew does not fall back to the project directory when the gradlewDirectory has no gradlew" {
            withTempDir("agentos-gradlew-nofallback") { root ->
                root.resolve("gradlew").createNewFile()
                val backend = root.resolve("backend").also { it.mkdirs() }

                val error = shouldThrow<GradlewNotFoundException> { GradleRunner(root, gradlewDirectory = backend).resolveGradlew() }

                error.message!! shouldContain backend.absolutePath
                error.message!! shouldContain "gradlewDirectory"
            }
        }

        "resolveGradlew refuses a gradlewDirectory outside the project directory" {
            withTempDir("agentos-gradlew-dir-outside") { root ->
                val project = root.resolve("project").also { it.mkdirs() }
                val outside = root.resolve("outside").also { it.mkdirs() }
                outside.resolve("gradlew").createNewFile()

                val error = shouldThrow<GradlewNotFoundException> { GradleRunner(project, gradlewDirectory = outside).resolveGradlew() }

                error.message!! shouldContain "outside the project directory"
            }
        }

        "resolveGradlew refuses a gradlewDirectory that climbs out of the project with .." {
            withTempDir("agentos-gradlew-dir-climb") { root ->
                val project = root.resolve("project").also { it.mkdirs() }
                root.resolve("gradlew").createNewFile()

                val error = shouldThrow<GradlewNotFoundException> { GradleRunner(project, gradlewDirectory = File(project, "..")).resolveGradlew() }

                error.message!! shouldContain "outside the project directory"
            }
        }

        "resolveGradlew refuses a gradlewDirectory that is a symbolic link leaving the project directory" {
            withTempDir("agentos-gradlew-dir-link") { root ->
                val project = root.resolve("project").also { it.mkdirs() }
                val outside = root.resolve("outside").also { it.mkdirs() }
                outside.resolve("gradlew").createNewFile()
                val link = project.resolve("backend")
                Files.createSymbolicLink(link.toPath(), outside.toPath())

                val error = shouldThrow<GradlewNotFoundException> { GradleRunner(project, gradlewDirectory = link).resolveGradlew() }

                error.message!! shouldContain "outside the project directory"
            }
        }

        "resolveGradlew refuses a gradlew of the gradlewDirectory that is a symbolic link leaving the project" {
            withTempDir("agentos-gradlew-dir-filelink") { root ->
                val project = root.resolve("project").also { it.mkdirs() }
                val backend = project.resolve("backend").also { it.mkdirs() }
                val target = root.resolve("elsewhere").also { it.createNewFile() }
                Files.createSymbolicLink(backend.resolve("gradlew").toPath(), target.toPath())

                val error = shouldThrow<GradlewNotFoundException> { GradleRunner(project, gradlewDirectory = backend).resolveGradlew() }

                error.message!! shouldContain "outside the project directory"
            }
        }

        "the gradleDir is the gradlewDirectory, else the project directory" {
            withTempDir("agentos-gradledir") { root ->
                val backend = root.resolve("backend").also { it.mkdirs() }

                GradleRunner(root).gradleDir shouldBe root
                GradleRunner(root, gradlewDirectory = backend).gradleDir shouldBe backend
            }
        }

        "run starts the build in the gradlewDirectory by default" {
            withTempDir("agentos-run-default-dir") { root ->
                val backend = root.resolve("backend").also { it.mkdirs() }

                val result = GradleRunner(root, gradlewDirectory = backend).run(listOf("pwd"))

                result.rawOutput shouldContain "backend"
            }
        }

        "includedBuilds lists the builds of the settings file by directory name and skips commented ones" {
            withTempDir("agentos-included-builds") { root ->
                root.resolve("settings.gradle.kts").writeText(
                    """
                    rootProject.name = "x"
                    includeBuild("agentos-sdk")
                    includeBuild("../shared/agentos-git")
                    // includeBuild("commented")
                    """.trimIndent(),
                )

                GradleRunner(root).includedBuilds(root) shouldBe listOf("agentos-sdk", "agentos-git")
            }
        }

        "includedBuilds reads the Groovy DSL syntax too" {
            withTempDir("agentos-included-groovy") { root ->
                root.resolve("settings.gradle").writeText(
                    """
                    includeBuild 'one'
                    includeBuild('two')
                    """.trimIndent(),
                )

                GradleRunner(root).includedBuilds(root) shouldBe listOf("one", "two")
            }
        }

        "includedBuilds is empty without a settings file or without included builds" {
            withTempDir("agentos-included-none") { root ->
                GradleRunner(root).includedBuilds(root) shouldBe emptyList<String>()

                root.resolve("settings.gradle.kts").writeText("// nothing included")

                GradleRunner(root).includedBuilds(root) shouldBe emptyList<String>()
            }
        }

        "includedBuilds does not read the settings file of a parent directory" {
            withTempDir("agentos-included-parent") { root ->
                root.resolve("settings.gradle.kts").writeText("""includeBuild("agentos-sdk")""")
                val sub = root.resolve("backend").also { it.mkdirs() }

                GradleRunner(root).includedBuilds(sub) shouldBe emptyList<String>()
            }
        }

        "compositeBuildHint names the included builds when a run without module fails on a missing task" {
            withTempDir("agentos-composite-hint") { root ->
                root.resolve("settings.gradle.kts").writeText("""includeBuild("agentos-sdk")""")

                val hint =
                    GradleRunner(root).compositeBuildHint(
                        1,
                        false,
                        root.resolve("gradlew").absolutePath,
                        "* What went wrong:\nTask 'testClasses' not found in root project 'agentos'.",
                    )

                hint!! shouldContain "agentos-sdk"
                hint shouldContain "subproject"
            }
        }

        "compositeBuildHint also answers when Gradle cannot locate the tasks of a selector" {
            withTempDir("agentos-composite-locate") { root ->
                root.resolve("settings.gradle.kts").writeText("""includeBuild("agentos-sdk")""")

                GradleRunner(root).compositeBuildHint(
                    1,
                    false,
                    root.resolve("gradlew").absolutePath,
                    "Cannot locate tasks that match ':agentos-sdk:test' as project 'agentos-sdk' not found in root project 'agentos'.",
                ) shouldContain "agentos-sdk"
            }
        }

        "compositeBuildHint is silent on a plain compile or test failure, the module is not the problem" {
            withTempDir("agentos-composite-plain-failure") { root ->
                root.resolve("settings.gradle.kts").writeText("""includeBuild("agentos-sdk")""")

                GradleRunner(root).compositeBuildHint(
                    1,
                    false,
                    root.resolve("gradlew").absolutePath,
                    "e: file:///src/Foo.kt:12:5 Unresolved reference 'bar'\nExecution failed for task ':compileKotlin'.",
                ) shouldBe null
            }
        }

        "compositeBuildHint is silent on success, when a module was targeted, and without included builds" {
            withTempDir("agentos-composite-silent") { root ->
                val gradlew = root.resolve("gradlew").absolutePath
                val runner = GradleRunner(root)
                val notFound = "Task 'testClasses' not found in root project 'agentos'."
                root.resolve("settings.gradle.kts").writeText("""includeBuild("agentos-sdk")""")

                runner.compositeBuildHint(0, false, gradlew, notFound) shouldBe null
                runner.compositeBuildHint(1, true, gradlew, notFound) shouldBe null

                root.resolve("settings.gradle.kts").writeText("// nothing included")

                runner.compositeBuildHint(1, false, gradlew, notFound) shouldBe null
            }
        }

        // ── workDirFor / run workDir ─────────────────────────────────────────────────────

        "workDirFor is the directory that contains gradlew" {
            withTempDir("agentos-workdir") { root ->
                GradleRunner(root).workDirFor(root.resolve("gradlew").absolutePath) shouldBe root
            }
        }

        "run executes the command inside the given workDir" {
            withTempDir("agentos-run-workdir") { root ->
                val backend = root.resolve("backend").also { it.mkdirs() }

                val result = GradleRunner(root).run(listOf("pwd"), workDir = backend)

                result.exitCode shouldBe 0
                result.rawOutput shouldContain "backend"
            }
        }
    })
