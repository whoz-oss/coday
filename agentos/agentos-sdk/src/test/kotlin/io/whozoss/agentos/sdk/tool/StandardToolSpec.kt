package io.whozoss.agentos.sdk.tool

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

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

    private val dummyContext = ToolContext(
        namespaceId = UUID.randomUUID(),
        userId = null,
        userExternalId = null,
        caseEvents = emptyList(),
    )

    val testTool =
        object : StandardTool<TimezoneInput> {
            override val name = "TestTool"
            override val description = "Test tool"
            override val version = "1.0.0"
            override val paramType: Class<TimezoneInput> = TimezoneInput::class.java
            override val inputSchema = "{}"

            override suspend fun execute(input: TimezoneInput?, context: ToolContext): ToolExecutionResult =
                ToolExecutionResult.success(input?.timezone ?: "null-input")
        }

    init {

        // null/blank args → execute(null) → tool decides how to handle missing input.
        // The tool under test returns "null-input" to signal no args were received.
        // Real tools (e.g. GetCurrentDateTime) return an error to force the LLM to retry.
        "executeWithJson with null calls execute with null input" {
            testTool.executeWithJson(null, dummyContext).output shouldBe "null-input"
        }

        "executeWithJson with blank string calls execute with null input" {
            testTool.executeWithJson("", dummyContext).output shouldBe "null-input"
            testTool.executeWithJson("   ", dummyContext).output shouldBe "null-input"
        }

        "executeWithJson with empty object uses data-class defaults" {
            // Previously '{}' was short-circuited to null; now it must be deserialized
            // so Kotlin data-class defaults (timezone = 'UTC') kick in.
            testTool.executeWithJson("{}", dummyContext).output shouldBe "UTC"
        }

        "executeWithJson with explicit timezone uses that value" {
            testTool.executeWithJson(
                "\"{\\\"timezone\\\":\\\"America/New_York\\\"}\"".let {
                    // Use the unescaped JSON directly
                    "{\"timezone\":\"America/New_York\"}"
                },
                dummyContext,
            ).output shouldBe "America/New_York"
        }

        // getConfirmationMode(args, ctx) defaults to the static confirmationMode val.
        // Ensures backward compatibility: an existing plugin that only overrides the val
        // keeps working unchanged after this extension.
        "getConfirmationMode default delegates to static confirmationMode" {
            val noneTool =
                object : StandardTool<Unit> {
                    override val name = "None"
                    override val description = ""
                    override val version = "1.0"
                    override val paramType: Class<Unit>? = null
                    override val inputSchema = "{}"

                    override suspend fun execute(input: Unit?, context: ToolContext) =
                        ToolExecutionResult.success("")
                    // confirmationMode = NONE (default)
                }
            val everyTimeTool =
                object : StandardTool<Unit> {
                    override val name = "EveryTime"
                    override val description = ""
                    override val version = "1.0"
                    override val paramType: Class<Unit>? = null
                    override val inputSchema = "{}"
                    override val confirmationMode = ConfirmationMode.EVERY_TIME

                    override suspend fun execute(input: Unit?, context: ToolContext) =
                        ToolExecutionResult.success("")
                }
            noneTool.getConfirmationMode() shouldBe ConfirmationMode.NONE
            noneTool.getConfirmationMode("{}", dummyContext) shouldBe ConfirmationMode.NONE
            everyTimeTool.getConfirmationMode() shouldBe ConfirmationMode.EVERY_TIME
            everyTimeTool.getConfirmationMode("{}", dummyContext) shouldBe ConfirmationMode.EVERY_TIME
        }

        // Dynamic override: a plugin can decide the mode based on args/events without
        // touching the static val. This is the core mechanism of the PR — used by
        // CopilotStandardTool to bypass confirmation when an in-session CreateProfile
        // makes a subsequent UpdateProfile implicit.
        "getConfirmationMode dynamic override is honored" {
            val dynamicTool =
                object : StandardTool<Unit> {
                    override val name = "Dynamic"
                    override val description = ""
                    override val version = "1.0"
                    override val paramType: Class<Unit>? = null
                    override val inputSchema = "{}"
                    override val confirmationMode = ConfirmationMode.EVERY_TIME // static fallback

                    override suspend fun getConfirmationMode(
                        argsJson: String?,
                        context: ToolContext?,
                    ): ConfirmationMode =
                        if (argsJson?.contains("bypass") == true) ConfirmationMode.NONE
                        else confirmationMode

                    override suspend fun execute(input: Unit?, context: ToolContext) =
                        ToolExecutionResult.success("")
                }
            dynamicTool.getConfirmationMode("{}", dummyContext) shouldBe ConfirmationMode.EVERY_TIME
            dynamicTool.getConfirmationMode("""{"bypass":true}""", dummyContext) shouldBe ConfirmationMode.NONE
        }
    }
}
