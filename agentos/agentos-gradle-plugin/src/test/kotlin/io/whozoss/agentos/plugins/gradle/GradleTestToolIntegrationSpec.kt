package io.whozoss.agentos.plugins.gradle

import io.kotest.core.annotation.EnabledCondition
import io.kotest.core.annotation.EnabledIf
import io.kotest.core.spec.Spec
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.io.File
import java.nio.file.Files
import kotlin.reflect.KClass

// ── opt-in ───────────────────────────────────────────────────────────────────────────────────────────

/** Enables [GradleTestToolIntegrationSpec] only when the environment variable `AGENTOS_GRADLE_IT` is `true`. */
class GradleIntegrationEnabled : EnabledCondition {
    override fun enabled(kclass: KClass<out Spec>): Boolean = System.getenv(ENV_VARIABLE) == "true"

    companion object {
        const val ENV_VARIABLE = "AGENTOS_GRADLE_IT"
    }
}

// ── helpers ───────────────────────────────────────────────────────────────────────────────────────────

/** A real Gradle run (first one downloads plugins) needs the most the tool allows. */
private const val MAX_TIMEOUT_SECONDS = ToolTimeout.MAX_SECONDS

/** Kotlin plugin version of the scaffolded projects: the one of the AgentOS version catalog. */
private const val KOTLIN_VERSION = "2.3.20"

/** Root of the AgentOS build: the first ancestor of the working directory holding a `gradlew`. Lazy: unused when the spec is disabled. */
private val projectRoot: File by lazy {
    generateSequence(File("").absoluteFile) { it.parentFile }
        .first { it.resolve("gradlew").isFile }
}

private fun scaffoldGradleProject(
    dir: File,
    testSource: String,
    testTaskConfig: String = "",
): File {
    // Copy gradlew as a real file — symlinks break APP_HOME detection in the wrapper script
    // (the wrapper uses $0 realpath to set APP_HOME, which would resolve to the AgentOS root).
    projectRoot.resolve("gradlew").copyTo(dir.resolve("gradlew")).also {
        it.setExecutable(true)
    }
    // Copy wrapper files — gradle-wrapper.jar is ~60 KB, acceptable for a test fixture.
    dir.resolve("gradle/wrapper").mkdirs()
    listOf("gradle-wrapper.jar", "gradle-wrapper.properties").forEach { name ->
        projectRoot
            .resolve("gradle/wrapper/$name")
            .copyTo(dir.resolve("gradle/wrapper/$name"))
    }

    // minimal settings — standalone project, no composite builds
    dir.resolve("settings.gradle.kts").writeText(
        """
        rootProject.name = "gradle-tool-it"
        dependencyResolutionManagement {
            repositories { mavenCentral() }
        }
        """.trimIndent(),
    )

    // build script: kotlin-jvm + JUnit 5 only, no custom plugins
    dir.resolve("build.gradle.kts").writeText(
        """
        plugins {
            kotlin("jvm") version "$KOTLIN_VERSION"
        }
        dependencies {
            testImplementation(kotlin("test"))
        }
        tasks.test {
            useJUnitPlatform()
            $testTaskConfig
        }
        """.trimIndent(),
    )

    // write the test source
    val srcDir = dir.resolve("src/test/kotlin/it").also { it.mkdirs() }
    srcDir.resolve("SampleTest.kt").writeText(testSource)

    return dir
}

private fun allPassSource() =
    """
    package it

    import kotlin.test.Test

    class SampleTest {
        @Test fun alpha() = Unit
        @Test fun beta()  = Unit
        @Test fun gamma() = Unit
    }
    """.trimIndent()

private fun oneFailSource() =
    """
    package it

    import kotlin.test.Test
    import kotlin.test.assertEquals

    class SampleTest {
        @Test fun passing() = Unit
        @Test fun failing() {
            assertEquals("foo", "bar", "expected foo but got bar")
        }
    }
    """.trimIndent()

// ── spec ────────────────────────────────────────────────────────────────────────────────────────────────

/**
 * Runs real Gradle builds (a scaffolded Kotlin project, the AgentOS Gradle wrapper): slow and it needs the network
 * (Gradle distribution, Kotlin plugin and test dependencies are downloaded).
 *
 * **Opt-in**: the whole spec is skipped unless the environment variable `AGENTOS_GRADLE_IT` is set to `true`, e.g.
 * `AGENTOS_GRADLE_IT=true nx test agentos-gradle-plugin`.
 */
@EnabledIf(GradleIntegrationEnabled::class)
class GradleTestToolIntegrationSpec :
    StringSpec({
        timeout = 900_000

        "runTests returns SUCCESS summary when all tests pass" {
            val dir = Files.createTempDirectory("agentos-gradle-it-pass").toFile()
            try {
                scaffoldGradleProject(dir, allPassSource())
                val result = GradleTestTool(GradleRunner(dir)).runTests(null, timeoutSeconds = MAX_TIMEOUT_SECONDS).successText()

                result shouldContain "SUCCESS"
                result shouldContain "Tests: 3"
                result shouldContain "Passed: 3"
                result shouldNotContain "Failures"
            } finally {
                dir.deleteRecursively()
            }
        }

        "runTests returns FAILURE summary with failure detail when a test fails" {
            val dir = Files.createTempDirectory("agentos-gradle-it-fail").toFile()
            try {
                scaffoldGradleProject(dir, oneFailSource())
                val result = GradleTestTool(GradleRunner(dir)).runTests(null, timeoutSeconds = MAX_TIMEOUT_SECONDS).failureText()

                result shouldContain "FAILURE"
                result shouldContain "Failed: 1"
                result shouldContain "SampleTest"
                result shouldContain "failing"
                result shouldContain "── Failures"
            } finally {
                dir.deleteRecursively()
            }
        }

        "runTests reads results even when the project disables its junit xml reports" {
            val dir = Files.createTempDirectory("agentos-gradle-it-no-xml").toFile()
            try {
                scaffoldGradleProject(dir, allPassSource(), testTaskConfig = "reports.junitXml.required.set(false)")

                val result = GradleTestTool(GradleRunner(dir)).runTests(null, timeoutSeconds = MAX_TIMEOUT_SECONDS).successText()

                result shouldContain "SUCCESS"
                result shouldContain "Tests: 3"
            } finally {
                dir.deleteRecursively()
            }
        }

        "runTests run twice without change shows the results both times" {
            val dir = Files.createTempDirectory("agentos-gradle-it-twice").toFile()
            try {
                scaffoldGradleProject(dir, allPassSource())
                val tool = GradleTestTool(GradleRunner(dir))
                tool.runTests(null, timeoutSeconds = MAX_TIMEOUT_SECONDS)

                val second = tool.runTests(null, timeoutSeconds = MAX_TIMEOUT_SECONDS).successText()

                second shouldContain "SUCCESS"
                second shouldContain "Tests: 3"
            } finally {
                dir.deleteRecursively()
            }
        }

        "runTests with testFilter runs only matching test" {
            val dir = Files.createTempDirectory("agentos-gradle-it-filter").toFile()
            try {
                scaffoldGradleProject(dir, oneFailSource())
                // filter to passing test only — should succeed even though failing() exists
                val result = GradleTestTool(GradleRunner(dir)).runTests(listOf("it.SampleTest.passing"), timeoutSeconds = MAX_TIMEOUT_SECONDS).successText()

                result shouldContain "SUCCESS"
                result shouldContain "Passed: 1"
            } finally {
                dir.deleteRecursively()
            }
        }
    })
