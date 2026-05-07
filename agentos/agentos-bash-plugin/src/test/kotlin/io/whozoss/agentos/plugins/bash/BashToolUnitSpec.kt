package io.whozoss.agentos.plugins.bash

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.whozoss.agentos.sdk.tool.ToolContext
import java.util.UUID

/**
 * Unit tests for [BashTool].
 *
 * Command execution is tested against real process invocations using safe, fast
 * commands (echo, ls, sh -c "exit 1"). This avoids the complexity of mocking
 * [BashCommandExecutor] while keeping tests deterministic and portable.
 */
class BashToolUnitSpec : StringSpec({

    val baseConfig = BashIntegrationConfig(
        workingDirectory = System.getProperty("java.io.tmpdir"),
        defaultTimeoutSeconds = 10L,
    )
    val ctx = ToolContext(UUID.randomUUID(), null, null, emptyList())

    // --- name ---

    "name without configName should be the tool name" {
        val tool = BashTool(
            toolConfig = BashToolConfig(name = "list", description = "Lists", command = "ls"),
            integrationConfig = baseConfig,
            configName = null,
        )
        tool.name shouldBe "list"
    }

    "name with configName should be prefixed" {
        val tool = BashTool(
            toolConfig = BashToolConfig(name = "list", description = "Lists", command = "ls"),
            integrationConfig = baseConfig,
            configName = "MY_PROJECT",
        )
        tool.name shouldBe "MY_PROJECT__list"
    }

    // --- inputSchema ---

    "fixed command tool should have empty properties schema" {
        val tool = BashTool(
            toolConfig = BashToolConfig(name = "t", description = "d", command = "echo hi"),
            integrationConfig = baseConfig,
        )
        tool.inputSchema shouldContain "\"properties\": {}"
        tool.inputSchema shouldNotContain "required"
    }

    "parameterised tool should expose parameters field as required" {
        val tool = BashTool(
            toolConfig = BashToolConfig(
                name = "search",
                description = "Searches",
                command = "grep -r PARAMETERS .",
                parametersDescription = "The pattern",
            ),
            integrationConfig = baseConfig,
        )
        tool.inputSchema shouldContain "\"parameters\""
        tool.inputSchema shouldContain "required"
        tool.inputSchema shouldContain "The pattern"
    }

    // --- description ---

    "fixed command description should not append parameters section" {
        val tool = BashTool(
            toolConfig = BashToolConfig(name = "t", description = "Run tests", command = "./gradlew test"),
            integrationConfig = baseConfig,
        )
        tool.description shouldBe "Run tests"
    }

    "parameterised tool description should append parametersDescription" {
        val tool = BashTool(
            toolConfig = BashToolConfig(
                name = "search",
                description = "Searches code",
                command = "grep -r PARAMETERS src",
                parametersDescription = "The regex pattern to search for",
            ),
            integrationConfig = baseConfig,
        )
        tool.description shouldContain "Searches code"
        tool.description shouldContain "The regex pattern to search for"
    }

    // --- execute: fixed command ---

    "fixed command echo should return output" {
        val tool = BashTool(
            toolConfig = BashToolConfig(name = "greet", description = "Greets", command = "echo hello"),
            integrationConfig = baseConfig,
        )
        val result = tool.execute(BashTool.Input(), ctx)
        result shouldContain "hello"
    }

    "fixed command with non-zero exit code should include exit code in output" {
        val tool = BashTool(
            toolConfig = BashToolConfig(name = "fail", description = "Fails", command = "sh -c 'exit 42'"),
            integrationConfig = baseConfig,
        )
        val result = tool.execute(BashTool.Input(), ctx)
        result shouldContain "42"
    }

    "fixed command producing no output should return (no output)" {
        val tool = BashTool(
            toolConfig = BashToolConfig(name = "silent", description = "Silent", command = "true"),
            integrationConfig = baseConfig,
        )
        val result = tool.execute(BashTool.Input(), ctx)
        result shouldBe "(no output)"
    }

    // --- execute: parameterised command ---

    "parameterised tool should substitute PARAMETERS in command" {
        val tool = BashTool(
            toolConfig = BashToolConfig(
                name = "echo_param",
                description = "Echoes",
                command = "echo PARAMETERS",
                parametersDescription = "Text to echo",
            ),
            integrationConfig = baseConfig,
        )
        val result = tool.execute(BashTool.Input(parameters = "world"), ctx)
        result shouldContain "world"
    }

    "parameterised tool with null parameters should return error" {
        val tool = BashTool(
            toolConfig = BashToolConfig(
                name = "search",
                description = "Searches",
                command = "grep -r PARAMETERS .",
                parametersDescription = "Pattern",
            ),
            integrationConfig = baseConfig,
        )
        val result = tool.execute(BashTool.Input(parameters = null), ctx)
        result shouldContain "Error"
        result shouldContain "parameters"
    }

    "parameterised tool with blank parameters should return error" {
        val tool = BashTool(
            toolConfig = BashToolConfig(
                name = "search",
                description = "Searches",
                command = "grep -r PARAMETERS .",
                parametersDescription = "Pattern",
            ),
            integrationConfig = baseConfig,
        )
        val result = tool.execute(BashTool.Input(parameters = "   "), ctx)
        result shouldContain "Error"
    }

    "raw bash tool (command IS PARAMETERS) should execute supplied command" {
        val tool = BashTool(
            toolConfig = BashToolConfig(
                name = "bash",
                description = "Raw bash",
                command = "PARAMETERS",
                parametersDescription = "Full command",
            ),
            integrationConfig = baseConfig,
        )
        val result = tool.execute(BashTool.Input(parameters = "echo raw_output"), ctx)
        result shouldContain "raw_output"
    }

    // --- timeout resolution ---

    "tool without per-tool timeout should use integration default" {
        // We verify indirectly: a command that completes quickly should succeed
        // when the default timeout is generous.
        val tool = BashTool(
            toolConfig = BashToolConfig(name = "fast", description = "Fast", command = "echo ok"),
            integrationConfig = baseConfig.copy(defaultTimeoutSeconds = 10L),
        )
        tool.execute(BashTool.Input(), ctx) shouldContain "ok"
    }

    "tool with per-tool timeout should use it instead of default" {
        val tool = BashTool(
            toolConfig = BashToolConfig(
                name = "fast",
                description = "Fast",
                command = "echo ok",
                timeoutSeconds = 5L,
            ),
            integrationConfig = baseConfig.copy(defaultTimeoutSeconds = 60L),
        )
        // The per-tool timeout is 5s, the command finishes instantly — should succeed.
        tool.execute(BashTool.Input(), ctx) shouldContain "ok"
    }

    "command that exceeds timeout should return timeout error" {
        val tool = BashTool(
            toolConfig = BashToolConfig(
                name = "slow",
                description = "Slow",
                command = "sleep 10",
                timeoutSeconds = 1L,
            ),
            integrationConfig = baseConfig,
        )
        val result = tool.execute(BashTool.Input(), ctx)
        result shouldContain "timed out"
        result shouldContain "1"
    }

    // --- working directory resolution ---

    "tool without path should run in workingDirectory" {
        val tool = BashTool(
            toolConfig = BashToolConfig(name = "pwd", description = "Print dir", command = "pwd"),
            integrationConfig = baseConfig,
        )
        val result = tool.execute(BashTool.Input(), ctx)
        // The output should be the resolved real path of java.io.tmpdir
        val expectedDir = java.io.File(System.getProperty("java.io.tmpdir")).canonicalPath
        result.trim() shouldBe expectedDir
    }

    "tool with relative path should run in workingDirectory/path" {
        // Use a known subdir that exists on any Unix system under /tmp
        val tmpDir = java.io.File(System.getProperty("java.io.tmpdir"))
        val subDir = java.io.File(tmpDir, "bash_plugin_test_subdir").also { it.mkdirs() }
        try {
            val tool = BashTool(
                toolConfig = BashToolConfig(
                    name = "pwd",
                    description = "Print dir",
                    command = "pwd",
                    path = subDir.name,
                ),
                integrationConfig = baseConfig,
            )
            val result = tool.execute(BashTool.Input(), ctx)
            result.trim() shouldContain subDir.name
        } finally {
            subDir.delete()
        }
    }

    // --- stderr handling ---

    "command writing to stderr should include stderr in output" {
        val tool = BashTool(
            toolConfig = BashToolConfig(
                name = "err",
                description = "Writes to stderr",
                command = "echo error_message >&2",
            ),
            integrationConfig = baseConfig,
        )
        val result = tool.execute(BashTool.Input(), ctx)
        result shouldContain "error_message"
        result shouldContain "Stderr"
    }

    "command writing to both stdout and stderr should include both" {
        val tool = BashTool(
            toolConfig = BashToolConfig(
                name = "both",
                description = "Both streams",
                command = "echo out_line; echo err_line >&2",
            ),
            integrationConfig = baseConfig,
        )
        val result = tool.execute(BashTool.Input(), ctx)
        result shouldContain "out_line"
        result shouldContain "err_line"
    }
})
