package io.whozoss.agentos.plugins.tmux

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.whozoss.agentos.sdk.tool.ToolContext
import java.util.UUID

class TmuxToolUnitSpec :
    StringSpec({
        val ctx = ToolContext(UUID.randomUUID(), null, null, emptyList())

        // ── Metadata ────────────────────────────────────────────────────────────────────────────

        "name should default to Tmux when no configName" {
            val tool = TmuxTool()
            tool.name shouldBe "Tmux"
        }

        "name should be prefixed with configName when provided" {
            val tool = TmuxTool(configName = "DEVBOX")
            tool.name shouldBe "DEVBOX__Tmux"
        }

        "version should be 1.0.0" {
            val tool = TmuxTool()
            tool.version shouldBe "1.0.0"
        }

        "inputSchema should declare action as required" {
            val tool = TmuxTool()
            tool.inputSchema shouldContain "\"required\""
            tool.inputSchema shouldContain "\"action\""
        }

        "inputSchema should list all valid action enum values" {
            val tool = TmuxTool()
            listOf("list", "status", "start", "stop", "new-window", "close-window", "send", "logs").forEach { action ->
                tool.inputSchema shouldContain "\"$action\""
            }
        }

        "inputSchema should declare window as optional" {
            val tool = TmuxTool()
            tool.inputSchema shouldContain "\"window\""
            // window must NOT appear in the required array
            val requiredSection = tool.inputSchema.substringAfter("\"required\"").substringBefore("]")
            requiredSection.contains("\"window\"") shouldBe false
        }

        // ── Input validation ──────────────────────────────────────────────────────────────

        "execute should return error when input is null" {
            val tool = TmuxTool()
            val result = tool.execute(null, ctx)
            result shouldContain "\"success\":false"
            result shouldContain "Input is required"
        }

        "execute should return error for unknown action" {
            val tool = TmuxTool()
            val result = tool.execute(TmuxTool.Input(action = "explode"), ctx)
            result shouldContain "\"success\":false"
            result shouldContain "Unknown action"
        }

        "execute should return error for invalid session name with special characters" {
            val tool = TmuxTool()
            val result = tool.execute(TmuxTool.Input(action = "status", session = "bad session!"), ctx)
            result shouldContain "\"success\":false"
            result shouldContain "Invalid session name"
        }

        "execute should accept session names with hyphens and underscores without validation error" {
            val tool = TmuxTool()
            // Validation must pass — the session name is legal. Whether tmux is actually
            // installed on this machine is irrelevant: the response will always be a valid
            // JSON object with a 'success' key, never a validation error.
            val result = tool.execute(TmuxTool.Input(action = "status", session = "my-service_01"), ctx)
            result shouldContain "\"success\""
            result.contains("Invalid session name") shouldBe false
        }

        // ── Missing required parameters ──────────────────────────────────────────────────────

        "status without session should return error" {
            val tool = TmuxTool()
            val result = tool.execute(TmuxTool.Input(action = "status"), ctx)
            result shouldContain "\"success\":false"
            result shouldContain "session is required"
        }

        "start without session should return error" {
            val tool = TmuxTool()
            val result = tool.execute(TmuxTool.Input(action = "start", command = "./gradlew bootRun"), ctx)
            result shouldContain "\"success\":false"
            result shouldContain "session is required"
        }

        "start without command should return error" {
            val tool = TmuxTool()
            val result = tool.execute(TmuxTool.Input(action = "start", session = "backend"), ctx)
            result shouldContain "\"success\":false"
            result shouldContain "command is required"
        }

        "logs without session should return error" {
            val tool = TmuxTool()
            val result = tool.execute(TmuxTool.Input(action = "logs"), ctx)
            result shouldContain "\"success\":false"
            result shouldContain "session is required"
        }

        "send without session should return error" {
            val tool = TmuxTool()
            val result = tool.execute(TmuxTool.Input(action = "send", command = "echo hi"), ctx)
            result shouldContain "\"success\":false"
            result shouldContain "session is required"
        }

        "send without command should return error" {
            val tool = TmuxTool()
            val result = tool.execute(TmuxTool.Input(action = "send", session = "backend"), ctx)
            result shouldContain "\"success\":false"
            result shouldContain "command is required"
        }

        "stop without session should return error" {
            val tool = TmuxTool()
            val result = tool.execute(TmuxTool.Input(action = "stop"), ctx)
            result shouldContain "\"success\":false"
            result shouldContain "session is required"
        }

        // ── window parameter ─────────────────────────────────────────────────────────────────────────

        "send without window should report missing session, not window" {
            val tool = TmuxTool()
            val result = tool.execute(TmuxTool.Input(action = "send", command = "echo hi"), ctx)
            result shouldContain "session is required"
        }

        "logs without window should report missing session, not window" {
            val tool = TmuxTool()
            val result = tool.execute(TmuxTool.Input(action = "logs"), ctx)
            result shouldContain "session is required"
        }

        // ── window validation ─────────────────────────────────────────────────────────────────────────

        "execute should return error for invalid window name" {
            val tool = TmuxTool()
            val result = tool.execute(TmuxTool.Input(action = "logs", session = "backend", window = "bad window!"), ctx)
            result shouldContain "\"success\":false"
            result shouldContain "Invalid window name"
        }

        "execute should accept window names with dots and hyphens without validation error" {
            val tool = TmuxTool()
            // Window name is valid — validation must pass. The tool may fail to reach tmux
            // but must never emit a window-validation error for this input.
            val result = tool.execute(TmuxTool.Input(action = "logs", session = "backend", window = "my.window-1"), ctx)
            result.contains("Invalid window name") shouldBe false
        }

        // ── list action (tmux may or may not be installed) ──────────────────────────────────

        "list should return a success response regardless of whether tmux is installed" {
            val tool = TmuxTool()
            // executeList() catches all failures from runTmux (including IOException when
            // tmux is not installed) and maps them to a success response with a fallback
            // message. This test verifies that graceful fallback.
            val result = tool.execute(TmuxTool.Input(action = "list"), ctx)
            result shouldContain "\"success\":true"
        }
    })
