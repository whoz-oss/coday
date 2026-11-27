package io.whozoss.agentos.plugins.gradle

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainInOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import io.whozoss.agentos.sdk.tool.ToolTimeout
import java.io.File
import java.nio.file.Files

private val SAMPLE_OUTPUT =
    """
    org.thymeleaf:thymeleaf:3.1.2.RELEASE
      Variant runtimeElements:
        | Attribute Name                 | Provided | Requested    |
        |--------------------------------|----------|--------------|
        | org.gradle.status              | release  |              |
        | org.gradle.usage               | java-runtime | java-runtime |

       Selection reasons:
          - By constraint : dependency management

    org.thymeleaf:thymeleaf:3.1.2.RELEASE
    \--- org.thymeleaf:thymeleaf-spring6:3.1.2.RELEASE
         \--- project :cloud-utils
              \--- runtimeClasspath

    A web-based, searchable dependency report is available by adding the --scan option.
    """.trimIndent()

private fun fakeGradlew(
    root: File,
    body: String,
) {
    root.resolve("gradlew").apply {
        writeText("#!/bin/sh\n$body\n")
        setExecutable(true)
    }
}

class GradleDependencyInsightToolUnitSpec :
    StringSpec({

        // ── buildCommand ───────────────────────────────────────────────────────────────────────

        "buildCommand without subproject targets the root dependencyInsight task" {
            val tool = GradleDependencyInsightTool(GradleRunner(File("/fake")))
            val cmd = tool.buildCommand("/fake/gradlew", "thymeleaf", subproject = null, configuration = "runtimeClasspath")

            cmd shouldContainInOrder listOf("/fake/gradlew", ":dependencyInsight")
        }

        "buildCommand with subproject strips and re-adds the leading colon" {
            val tool = GradleDependencyInsightTool(GradleRunner(File("/fake")))

            listOf("cloud-utils", ":cloud-utils").forEach {
                val cmd = tool.buildCommand("/fake/gradlew", "thymeleaf", subproject = it, configuration = "runtimeClasspath")
                cmd shouldContainInOrder listOf("/fake/gradlew", ":cloud-utils:dependencyInsight")
            }
        }

        "buildCommand glues options to their value so a value cannot inject an option" {
            val tool = GradleDependencyInsightTool(GradleRunner(File("/fake")))
            val cmd = tool.buildCommand("/fake/gradlew", "--scan", subproject = null, configuration = "testRuntimeClasspath")

            cmd shouldContain "--dependency=--scan"
            cmd shouldContain "--configuration=testRuntimeClasspath"
            cmd shouldContain "--single-path"
            cmd shouldContain "--quiet"
            cmd shouldContain "--console=plain"
        }

        // ── condense ─────────────────────────────────────────────────────────────────────────

        "condense keeps coordinates, selection reasons and the reverse tree" {
            val out = GradleDependencyInsightTool(GradleRunner(File("/fake"))).condense(SAMPLE_OUTPUT)

            out shouldContain "org.thymeleaf:thymeleaf:3.1.2.RELEASE"
            out shouldContain "By constraint : dependency management"
            out shouldContain "org.thymeleaf:thymeleaf-spring6:3.1.2.RELEASE"
            out shouldContain "project :cloud-utils"
            out shouldContain "runtimeClasspath"
        }

        "condense drops variants, attribute tables, blank lines and the scan advert" {
            val out = GradleDependencyInsightTool(GradleRunner(File("/fake"))).condense(SAMPLE_OUTPUT)

            out shouldNotContain "Variant"
            out shouldNotContain "Attribute Name"
            out shouldNotContain "org.gradle.status"
            out shouldNotContain "web-based"
            out.lines().none { it.isBlank() } shouldBe true
        }

        "condense collapses consecutive duplicate lines" {
            val out = GradleDependencyInsightTool(GradleRunner(File("/fake"))).condense("a:b:1\n\na:b:1\n\\--- c")

            out.lines() shouldBe listOf("a:b:1", "\\--- c")
        }

        "condense caps the output and says it was cut" {
            val raw = (1..200).joinToString("\n") { "line $it" }
            val out = GradleDependencyInsightTool(GradleRunner(File("/fake"))).condense(raw)

            out shouldContain "line 60"
            out shouldNotContain "line 61"
            out shouldContain "truncated"
        }

        "condense keeps the no-match message" {
            val raw = "No dependencies matching given input were found in configuration ':cloud-utils:runtimeClasspath'"

            GradleDependencyInsightTool(GradleRunner(File("/fake"))).condense(raw) shouldBe raw
        }

        // ── dependencyInsight (full flow) ─────────────────────────────────────────────────────

        "dependencyInsight runs gradlew with the expected arguments and returns the condensed output" {
            val root = Files.createTempDirectory("agentos-insight-flow").toFile()
            try {
                root.resolve("sample.txt").writeText(SAMPLE_OUTPUT)
                fakeGradlew(root, "echo \"\$@\" > args.txt\ncat sample.txt")

                val result =
                    GradleDependencyInsightTool(GradleRunner(root))
                        .dependencyInsight("thymeleaf", "cloud-utils", null, 60)
                        .successText()

                result shouldContain "org.thymeleaf:thymeleaf-spring6:3.1.2.RELEASE"
                result shouldNotContain "Variant"
                root.resolve("args.txt").readText().trim() shouldBe
                    ":cloud-utils:dependencyInsight --dependency=thymeleaf --configuration=runtimeClasspath " +
                    "--single-path --quiet --console=plain"
            } finally {
                root.deleteRecursively()
            }
        }

        "dependencyInsight reports FAILURE with exit code and condensed output when gradle fails" {
            val root = Files.createTempDirectory("agentos-insight-fail").toFile()
            try {
                fakeGradlew(root, "echo 'Configuration not found'\nexit 1")

                val result =
                    GradleDependencyInsightTool(GradleRunner(root))
                        .dependencyInsight("thymeleaf", null, "nope", 60)
                        .failureText()

                result shouldStartWith "FAILURE (exit 1)"
                result shouldContain "Configuration not found"
            } finally {
                root.deleteRecursively()
            }
        }

        "dependencyInsight times out after timeoutSeconds" {
            val root = Files.createTempDirectory("agentos-insight-timeout").toFile()
            try {
                fakeGradlew(root, "sleep 60")

                val result = GradleDependencyInsightTool(GradleRunner(root)).dependencyInsight("x", null, null, 1).errorText()

                result shouldContain "timed out after 1 seconds"
            } finally {
                root.deleteRecursively()
            }
        }

        "dependencyInsight returns an actionable ERROR when no gradlew can be found" {
            val root = Files.createTempDirectory("agentos-insight-no-gradlew").toFile()
            try {
                val result = GradleDependencyInsightTool(GradleRunner(root)).dependencyInsight("x", null, null, 60).errorText()

                result shouldContain "gradlewDirectory"
            } finally {
                root.deleteRecursively()
            }
        }

        "dependencyInsight rejects a blank dependency" {
            val result = GradleDependencyInsightTool(GradleRunner(File("/fake"))).dependencyInsight("  ", null, null, 60).errorText()

            result shouldContain "dependency"
        }

        "dependencyInsight rejects a timeoutSeconds outside the allowed range" {
            val tool = GradleDependencyInsightTool(GradleRunner(File("/fake")))
            listOf(0, -5, ToolTimeout.MAX_SECONDS + 1).forEach {
                val result = tool.dependencyInsight("x", null, null, it).errorText()

                result shouldContain "timeoutSeconds"
            }
        }

        "tool schema marks dependency and timeoutSeconds as required" {
            val tool = GradleDependencyInsightTool(GradleRunner(File("/fake")))
            val schema = tool.inputSchema

            tool.name shouldBe "gradle_dependency_insight"
            schema shouldMatch Regex("""(?s).*"required"\s*:\s*\[[^\]]*"dependency".*""")
            schema shouldMatch Regex("""(?s).*"required"\s*:\s*\[[^\]]*"timeoutSeconds".*""")
        }
    })
