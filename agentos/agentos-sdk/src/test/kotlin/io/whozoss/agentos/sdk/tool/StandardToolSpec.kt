package io.whozoss.agentos.sdk.tool

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * Tests for [StandardTool.executeWithJson] argument-parsing contract.
 *
 * Key regression: `executeWithJson("{}")` must NOT return null-input execution.
 * Previously `{}` was short-circuited to `null`, which meant tools that have
 * Kotlin default values in their Input data class would silently use those
 * defaults even when the LLM explicitly sent an empty object.  After the fix,
 * `{}` is deserialized normally so Jackson applies data-class defaults.
 */
class StandardToolSpec : StringSpec() {
    // Minimal tool implementation for unit-testing the default executeWithJson behaviour.
    data class TimezoneInput(
        val timezone: String = "UTC",
    )

    val testTool =
        object : StandardTool<TimezoneInput> {
            override val name = "TestTool"
            override val description = "Test tool"
            override val version = "1.0.0"
            override val paramType: Class<TimezoneInput> = TimezoneInput::class.java
            override val inputSchema = "{}"

            override fun execute(input: TimezoneInput?): String = input?.timezone ?: "null-input"
        }

    init {

        // null/blank args → execute(null) → tool decides how to handle missing input.
        // The tool under test returns "null-input" to signal no args were received.
        // Real tools (e.g. GetCurrentDateTime) return an error to force the LLM to retry.
        "executeWithJson with null calls execute with null input" {
            testTool.executeWithJson(null) shouldBe "null-input"
        }

        "executeWithJson with blank string calls execute with null input" {
            testTool.executeWithJson("") shouldBe "null-input"
            testTool.executeWithJson("   ") shouldBe "null-input"
        }

        "executeWithJson with empty object uses data-class defaults" {
            // Previously '{}' was short-circuited to null; now it must be deserialized
            // so Kotlin data-class defaults (timezone = 'UTC') kick in.
            testTool.executeWithJson("{}") shouldBe "UTC"
        }

        "executeWithJson with explicit timezone uses that value" {
            testTool.executeWithJson(
                "\"{\\\"timezone\\\":\\\"America/New_York\\\"}\"".let {
                    // Use the unescaped JSON directly
                    "{\"timezone\":\"America/New_York\"}"
                },
            ) shouldBe "America/New_York"
        }
    }
}
