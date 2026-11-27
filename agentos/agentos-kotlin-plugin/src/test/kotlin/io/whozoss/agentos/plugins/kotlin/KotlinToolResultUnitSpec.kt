package io.whozoss.agentos.plugins.kotlin

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class KotlinToolResultUnitSpec :
    StringSpec({
        "a Success maps to a successful ToolExecutionResult" {
            // when
            val result = KotlinToolResult.Success(text = "type info").toToolExecutionResult()

            // then
            result.success shouldBe true
            result.output shouldBe "type info"
        }

        "a Failure maps to a KOTLIN_FAILURE with its text untouched" {
            // when
            val result = KotlinToolResult.Failure(text = "[Error] 1:1 — unresolved reference").toToolExecutionResult()

            // then
            result.success shouldBe false
            result.errorType shouldBe "KOTLIN_FAILURE"
            result.output shouldBe "[Error] 1:1 — unresolved reference"
        }

        "an Error maps to a KOTLIN_ERROR and the agent reads it prefixed with ERROR" {
            // when
            val result = KotlinToolResult.Error(text = "'../a.kt' is outside the project").toToolExecutionResult()

            // then
            result.success shouldBe false
            result.errorType shouldBe "KOTLIN_ERROR"
            result.output shouldBe "ERROR: '../a.kt' is outside the project"
        }
    })
