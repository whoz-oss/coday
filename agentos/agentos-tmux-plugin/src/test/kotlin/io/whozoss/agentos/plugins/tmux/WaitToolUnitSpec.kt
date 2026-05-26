package io.whozoss.agentos.plugins.tmux

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.whozoss.agentos.sdk.tool.ToolContext
import java.util.UUID
import kotlin.time.measureTime

class WaitToolUnitSpec :
    StringSpec({
        val ctx = ToolContext(UUID.randomUUID(), null, null, emptyList())

        // ── Metadata ──────────────────────────────────────────────────────────────────

        "name should default to Wait when no configName" {
            WaitTool().name shouldBe "Wait"
        }

        "name should be prefixed with configName when provided" {
            WaitTool(configName = "DEVBOX").name shouldBe "DEVBOX__Wait"
        }

        "version should be 1.0.0" {
            WaitTool().version shouldBe "1.0.0"
        }

        "inputSchema should declare seconds as required" {
            val tool = WaitTool()
            tool.inputSchema shouldContain "\"required\""
            tool.inputSchema shouldContain "\"seconds\""
        }

        "inputSchema should declare minimum 1 and maximum 30" {
            val tool = WaitTool()
            tool.inputSchema shouldContain "\"minimum\": 1"
            tool.inputSchema shouldContain "\"maximum\": 30"
        }

        // ── Input validation ──────────────────────────────────────────────────────────

        "execute should return error when input is null" {
            val result = WaitTool().execute(null, ctx)
            result.success shouldBe false
            result.output shouldContain "Input is required"
            result.errorType shouldBe "INVALID_INPUT"
        }

        "execute should return error when seconds is 0" {
            val result = WaitTool().execute(WaitTool.Input(seconds = 0), ctx)
            result.success shouldBe false
            result.output shouldContain "seconds must be between"
            result.errorType shouldBe "INVALID_INPUT"
        }

        "execute should return error when seconds exceeds maximum" {
            val result = WaitTool().execute(WaitTool.Input(seconds = 31), ctx)
            result.success shouldBe false
            result.output shouldContain "seconds must be between"
            result.output shouldContain "31"
            result.errorType shouldBe "INVALID_INPUT"
        }

        "execute should return error for negative seconds" {
            val result = WaitTool().execute(WaitTool.Input(seconds = -5), ctx)
            result.success shouldBe false
            result.output shouldContain "seconds must be between"
            result.errorType shouldBe "INVALID_INPUT"
        }

        // ── Successful wait ───────────────────────────────────────────────────────────

        "execute should return success and report elapsed seconds" {
            val result = WaitTool().execute(WaitTool.Input(seconds = 1), ctx)
            result.success shouldBe true
            result.output shouldContain "1 second"
        }

        "execute should use singular form for one second" {
            val result = WaitTool().execute(WaitTool.Input(seconds = 1), ctx)
            result.success shouldBe true
            result.output shouldContain "1 second"
        }

        "execute should actually sleep for approximately the requested duration" {
            val elapsed = measureTime {
                WaitTool().execute(WaitTool.Input(seconds = 1), ctx)
            }
            // Allow generous tolerance: CI machines can be slow
            (elapsed.inWholeMilliseconds >= 900) shouldBe true
        }
    })
