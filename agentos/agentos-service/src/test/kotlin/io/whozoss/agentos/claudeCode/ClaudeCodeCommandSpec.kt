package io.whozoss.agentos.claudeCode

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainInOrder
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.whozoss.agentos.sdk.aiProvider.ReasoningEffort
import java.nio.file.Path
import java.time.Duration
import java.util.UUID

class ClaudeCodeCommandSpec :
    StringSpec({
        val root = Path.of("/srv/claude-code")
        val command = ClaudeCodeCommand(ClaudeCodeProperties(enabled = true, configRoot = root))
        val sessionId = UUID.fromString("6c7f4053-1f28-40cf-bc3b-dcb8c2f65454")
        val promptFile = Path.of("/tmp/run/system-prompt.md")
        val mcpFile = Path.of("/tmp/run/mcp.json")
        fun request(
            session: ClaudeCodeSession = ClaudeCodeSession.Start(sessionId),
            effort: ReasoningEffort? = null,
            maxTurns: Int? = null,
        ) = ClaudeCodeLaunchRequest(
            userId = "user-1",
            caseId = "case-1",
            model = "sonnet",
            systemPrompt = "You are dev.",
            session = session,
            effort = effort,
            maxTurns = maxTurns,
        )

        fun List<String>.valueOf(flag: String): String? = indexOf(flag).takeIf { it >= 0 }?.let { getOrNull(it + 1) }

        "a turn runs headless on stream-json, with no built-in tool and no user or project setting" {
            val arguments = command.arguments(request(), promptFile, mcpFile)

            arguments.shouldContainInOrder("-p", "--input-format", "stream-json", "--output-format", "stream-json")
            arguments.valueOf("--model") shouldBe "sonnet"
            arguments.valueOf("--system-prompt-file") shouldBe promptFile.toString()
            arguments.valueOf("--system-prompt-snapshot") shouldBe "off"
            arguments.valueOf("--tools") shouldBe ""
            arguments.valueOf("--setting-sources") shouldBe ""
            arguments.valueOf("--permission-prompts") shouldBe "none"
            arguments.valueOf("--mcp-config") shouldBe mcpFile.toString()
            arguments.valueOf("--allowedTools") shouldBe "mcp__agentos__*"
            arguments.valueOf("--session-id") shouldBe sessionId.toString()
            arguments shouldNotContain "--resume"
            arguments shouldNotContain "--effort"
            arguments shouldNotContain "--max-turns"
            // --bare never reads the subscription login.
            arguments shouldNotContain "--bare"
            jacksonObjectMapper().readTree(arguments.valueOf("--settings")).let { settings ->
                settings["disableAllHooks"].asBoolean() shouldBe true
                settings["autoMemoryEnabled"].asBoolean() shouldBe false
                settings["claudeMdExcludes"].map { it.asText() }.shouldContainInOrder("**/CLAUDE.md", "**/CLAUDE.local.md")
            }
        }

        "a later turn resumes the conversation instead of starting one" {
            val arguments = command.arguments(request(session = ClaudeCodeSession.Resume(sessionId)), promptFile, mcpFile)

            arguments.valueOf("--resume") shouldBe sessionId.toString()
            arguments shouldNotContain "--session-id"
        }

        "without an MCP server the model gets no tool at all" {
            val arguments = command.arguments(request(), promptFile, mcpConfigFile = null)

            arguments shouldNotContain "--mcp-config"
            arguments shouldNotContain "--allowedTools"
            arguments.valueOf("--tools") shouldBe ""
        }

        "effort and the tool round limit are passed when set" {
            val arguments = command.arguments(request(effort = ReasoningEffort.XHIGH, maxTurns = 20), promptFile, null)

            arguments.valueOf("--effort") shouldBe "xhigh"
            arguments.valueOf("--max-turns") shouldBe "20"
        }

        "every effort level maps to a CLI level, none to the lowest" {
            ReasoningEffort.entries.associateWith { ClaudeCodeCommand.cliEffort(it) } shouldContainExactly
                mapOf(
                    ReasoningEffort.NONE to "low",
                    ReasoningEffort.LOW to "low",
                    ReasoningEffort.MEDIUM to "medium",
                    ReasoningEffort.HIGH to "high",
                    ReasoningEffort.XHIGH to "xhigh",
                    ReasoningEffort.MAX to "max",
                )
        }

        "the environment is an allow-list: service secrets and Anthropic credentials stay out" {
            val inherited =
                mapOf(
                    "PATH" to "/opt/bin:/usr/bin",
                    "HOME" to "/home/user",
                    "USER" to "user",
                    "LANG" to "fr_FR.UTF-8",
                    "TMPDIR" to "/tmp/user",
                    "HTTPS_PROXY" to "http://proxy:3128",
                    "AGENTOS_ENCRYPTION_KEY" to "secret",
                    "ANTHROPIC_API_KEY" to "sk-ant-secret",
                    "ANTHROPIC_BASE_URL" to "https://elsewhere",
                    "CLAUDE_CODE_OAUTH_TOKEN" to "token",
                    "CLAUDE_CODE_USE_BEDROCK" to "1",
                    "CLAUDECODE" to "1",
                    "NODE_OPTIONS" to "--require /tmp/hook.js",
                    "SPRING_NEO4J_AUTHENTICATION_PASSWORD" to "secret",
                )

            command.environment("user-1", inherited) shouldContainExactly
                mapOf(
                    "PATH" to "/opt/bin:/usr/bin",
                    "HOME" to "/home/user",
                    "USER" to "user",
                    // The macOS keychain needs LOGNAME as well as USER.
                    "LOGNAME" to "user",
                    "LANG" to "fr_FR.UTF-8",
                    "TMPDIR" to "/tmp/user",
                    "HTTPS_PROXY" to "http://proxy:3128",
                    "CLAUDE_CONFIG_DIR" to "/srv/claude-code/user-1",
                    "CLAUDE_CODE_DISABLE_GIT_INSTRUCTIONS" to "1",
                    "DISABLE_AUTOUPDATER" to "1",
                )
        }

        "each user has a configuration directory and each case an empty working directory under it" {
            command.configDirectory("user-1") shouldBe Path.of("/srv/claude-code/user-1")
            command.workingDirectory("user-1", "case-1") shouldBe Path.of("/srv/claude-code/user-1/workdirs/case-1")
        }

        "an id that could leave the configuration root is refused" {
            for (id in listOf("..", "../other", "a/b", "", "user 1")) {
                shouldThrow<IllegalArgumentException> { command.configDirectory(id) }
                shouldThrow<IllegalArgumentException> { command.workingDirectory("user-1", id) }
            }
        }

        "the login command points the CLI at the user's configuration directory" {
            val quoting = ClaudeCodeCommand(ClaudeCodeProperties(configRoot = Path.of("/srv/it's here")))

            command.loginCommand("user-1") shouldBe "CLAUDE_CONFIG_DIR='/srv/claude-code/user-1' claude auth login"
            quoting.loginCommand("user-1") shouldBe "CLAUDE_CONFIG_DIR='/srv/it'\\''s here/user-1' claude auth login"
        }

        "the MCP configuration declares the AgentOS server with its bearer token, never printed" {
            val server = ClaudeCodeMcpServer("http://127.0.0.1:8080/internal/claude-code/mcp/run-1", "t0k3n", Duration.ofMinutes(15))
            val json = jacksonObjectMapper().readTree(command.mcpConfiguration(server))["mcpServers"]["agentos"]

            json["type"].asText() shouldBe "http"
            json["url"].asText() shouldBe server.url
            json["headers"]["Authorization"].asText() shouldBe "Bearer t0k3n"
            json["alwaysLoad"].asBoolean() shouldBe true
            json["timeout"].asLong() shouldBe 900_000L
            server.toString() shouldNotContain "t0k3n"
        }
    })
