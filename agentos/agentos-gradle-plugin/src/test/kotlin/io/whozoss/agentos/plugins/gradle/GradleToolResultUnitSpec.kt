package io.whozoss.agentos.plugins.gradle

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files

class GradleToolResultUnitSpec :
    StringSpec({

        "a Success maps to a successful ToolExecutionResult" {
            val result = GradleToolResult.Success("all good").toToolExecutionResult()

            result.success shouldBe true
            result.output shouldBe "all good"
        }

        "a Failure maps to a GRADLE_FAILURE with its text untouched" {
            val result = GradleToolResult.Failure("FAILURE (exit 1)\nboom").toToolExecutionResult()

            result.success shouldBe false
            result.errorType shouldBe "GRADLE_FAILURE"
            result.output shouldBe "FAILURE (exit 1)\nboom"
        }

        "an Error maps to a GRADLE_ERROR and the agent reads it prefixed with ERROR" {
            val result = GradleToolResult.Error("no gradlew").toToolExecutionResult()

            result.success shouldBe false
            result.errorType shouldBe "GRADLE_ERROR"
            result.output shouldBe "ERROR: no gradlew"
        }

        "ofExit is a Success for exit 0 and a Failure otherwise" {
            GradleToolResult.ofExit(0, "t") shouldBe GradleToolResult.Success("t")
            GradleToolResult.ofExit(1, "t") shouldBe GradleToolResult.Failure("t")
            GradleToolResult.ofExit(137, "t") shouldBe GradleToolResult.Failure("t")
        }

        "a successful report whose text starts with ERROR or FAILURE stays a success" {
            val root = Files.createTempDirectory("agentos-result-text").toFile()
            try {
                root.resolve("gradlew").apply {
                    writeText("#!/bin/sh\necho 'ERROR-handling:1.0 (selected by rule)'\necho 'FAILURE-detector:2.0'\nexit 0\n")
                    setExecutable(true)
                }

                val result = GradleDependencyInsightTool(GradleRunner(root)).dependencyInsight("x", null, null, 60)

                result.successText()
                result.toToolExecutionResult().success shouldBe true
            } finally {
                root.deleteRecursively()
            }
        }
    })
