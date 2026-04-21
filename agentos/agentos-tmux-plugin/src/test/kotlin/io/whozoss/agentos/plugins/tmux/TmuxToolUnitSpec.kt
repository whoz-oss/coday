package io.whozoss.agentos.plugins.tmux

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class TmuxToolUnitSpec :
    StringSpec({
        // ── Metadata ──────────────────────────────────────────────────────────────────

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
            listOf("list", "status", "start", "logs", "send", "stop").forEach { action ->
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

        // ── Input validation ──────────────────────────────────────────────────────────

        "execute should return error when input is null" {
            val tool = TmuxTool()
            val result = tool.execute(null)
            result shouldContain "\"success\":false"
            result shouldContain "Input is required"
        }

        "execute should return error for unknown action" {
            val tool = TmuxTool()
            val result = tool.execute(TmuxTool.Input(action = "explode"))
            result shouldContain "\"success\":false"
            result shouldContain "Unknown action"
        }

        "execute should return error for invalid session name with special characters" {
            val tool = TmuxTool()
            val result = tool.execute(TmuxTool.Input(action = "status", session = "bad session!"))
            result shouldContain "\"success\":false"
            result shouldContain "Invalid session name"
        }

        "execute should accept session names with hyphens and underscores" {
            val tool = TmuxTool()
            // This will try to run tmux; on CI without tmux it returns stopped/error — just check no validation error
            val result = tool.execute(TmuxTool.Input(action = "status", session = "my-service_01"))
            result shouldContain "\"success\""
            // Must NOT contain the validation error message
            result.contains("Invalid session name") shouldBe false
        }

        // ── Missing required parameters ───────────────────────────────────────────────

        "status without session should return error" {
            val tool = TmuxTool()
            val result = tool.execute(TmuxTool.Input(action = "status"))
            result shouldContain "\"success\":false"
            result shouldContain "session is required"
        }

        "start without session should return error" {
            val tool = TmuxTool()
            val result = tool.execute(TmuxTool.Input(action = "start", command = "./gradlew bootRun"))
            result shouldContain "\"success\":false"
            result shouldContain "session is required"
        }

        "start without command should return error" {
            val tool = TmuxTool()
            val result = tool.execute(TmuxTool.Input(action = "start", session = "backend"))
            result shouldContain "\"success\":false"
            result shouldContain "command is required"
        }

        "logs without session should return error" {
            val tool = TmuxTool()
            val result = tool.execute(TmuxTool.Input(action = "logs"))
            result shouldContain "\"success\":false"
            result shouldContain "session is required"
        }

        "send without session should return error" {
            val tool = TmuxTool()
            val result = tool.execute(TmuxTool.Input(action = "send", command = "echo hi"))
            result shouldContain "\"success\":false"
            result shouldContain "session is required"
        }

        "send without command should return error" {
            val tool = TmuxTool()
            val result = tool.execute(TmuxTool.Input(action = "send", session = "backend"))
            result shouldContain "\"success\":false"
            result shouldContain "command is required"
        }

        "stop without session should return error" {
            val tool = TmuxTool()
            val result = tool.execute(TmuxTool.Input(action = "stop"))
            result shouldContain "\"success\":false"
            result shouldContain "session is required"
        }

        // ── window parameter ──────────────────────────────────────────────────────────

        "send without window should report missing session, not window" {
            val tool = TmuxTool()
            val result = tool.execute(TmuxTool.Input(action = "send", command = "echo hi"))
            result shouldContain "session is required"
        }

        "logs without window should report missing session, not window" {
            val tool = TmuxTool()
            val result = tool.execute(TmuxTool.Input(action = "logs"))
            result shouldContain "session is required"
        }

        // ── list action (tmux may or may not be installed) ────────────────────────────

        "list should return a success response regardless of whether tmux is installed" {
            val tool = TmuxTool()
            val result = tool.execute(TmuxTool.Input(action = "list"))
            // Whether tmux is present or not, the tool handles it gracefully
            result shouldContain "\"success\":true"
        }
    })
