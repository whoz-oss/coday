package io.whozoss.agentos.plugins.ktlint

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class KtlintToolResultUnitSpec :
    StringSpec({

        "a Success maps to a successful ToolExecutionResult" {
            val result = KtlintToolResult.Success(text = "all good").toToolExecutionResult()

            result.success shouldBe true
            result.output shouldBe "all good"
        }

        "a Failure maps to a KTLINT_FAILURE with its text untouched" {
            val result = KtlintToolResult.Failure(text = "Checked 1 file(s): 1 violation(s)").toToolExecutionResult()

            result.success shouldBe false
            result.errorType shouldBe "KTLINT_FAILURE"
            result.output shouldBe "Checked 1 file(s): 1 violation(s)"
        }

        "an Error maps to a KTLINT_ERROR and the agent reads it prefixed with ERROR" {
            val result = KtlintToolResult.Error(text = "Path not found: A.kt").toToolExecutionResult()

            result.success shouldBe false
            result.errorType shouldBe "KTLINT_ERROR"
            result.output shouldBe "ERROR: Path not found: A.kt"
        }

        "ofViolations is a Success when nothing remains and a Failure otherwise" {
            KtlintToolResult.ofViolations(remaining = 0, text = "t") shouldBe KtlintToolResult.Success(text = "t")
            KtlintToolResult.ofViolations(remaining = 1, text = "t") shouldBe KtlintToolResult.Failure(text = "t")
        }

        "a successful report whose text starts with ERROR stays a success" {
            // the outcome is decided where it is known, never guessed back from the text
            KtlintToolResult.Success(text = "ERROR-prone.kt: clean").toToolExecutionResult().success shouldBe true
        }
    })
