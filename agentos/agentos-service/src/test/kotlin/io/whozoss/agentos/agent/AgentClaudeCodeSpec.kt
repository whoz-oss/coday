package io.whozoss.agentos.agent

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.whozoss.agentos.chat.UsageAccumulator
import io.whozoss.agentos.claudeCode.ClaudeCodeProcessLauncher
import io.whozoss.agentos.claudeCode.ClaudeCodeProperties
import io.whozoss.agentos.claudeCode.FakeClaudeCli
import io.whozoss.agentos.git.PosixOnly
import io.whozoss.agentos.sdk.actor.Actor
import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.aiProvider.AiModel
import io.whozoss.agentos.sdk.aiProvider.ModelPricing
import io.whozoss.agentos.sdk.aiProvider.ReasoningEffort
import io.whozoss.agentos.sdk.caseEvent.AgentFinishedEvent
import io.whozoss.agentos.sdk.caseEvent.CaseEvent
import io.whozoss.agentos.sdk.caseEvent.ErrorEvent
import io.whozoss.agentos.sdk.caseEvent.MessageContent
import io.whozoss.agentos.sdk.caseEvent.MessageEvent
import io.whozoss.agentos.sdk.caseEvent.TextChunkEvent
import io.whozoss.agentos.sdk.caseEvent.ThinkingEvent
import io.whozoss.agentos.sdk.entity.EntityMetadata
import kotlinx.coroutines.flow.toList
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import kotlin.io.path.readLines
import kotlin.io.path.readText

@EnabledIf(PosixOnly::class)
class AgentClaudeCodeSpec :
    StringSpec({
        timeout = 30_000
        val namespaceId = UUID.randomUUID()
        val caseId = UUID.randomUUID()
        val agentId = UUID.randomUUID()
        val userId = UUID.randomUUID()
        val directories = mutableListOf<Path>()
        fun directory(): Path = Files.createTempDirectory("agentos-agent-claude-code-").also { directories.add(it) }
        afterTest {
            directories.forEach { it.toFile().deleteRecursively() }
            directories.clear()
        }

        val model =
            AiModel(
                aiProviderId = UUID.randomUUID(),
                apiModelName = "haiku",
                pricing = ModelPricing(inputMTokens = 0.0, outputMTokens = 0.0, cacheRead = 0.0, cacheWrite = 0.0),
            )

        fun agent(
            cli: FakeClaudeCli,
            usage: UsageAccumulator? = null,
            idleTimeout: Duration = Duration.ofMinutes(1),
            minimumVersion: String = "2.1.317",
        ): AgentClaudeCode {
            val properties =
                ClaudeCodeProperties(
                    enabled = true,
                    executable = cli.executable.toString(),
                    configRoot = directory(),
                    idleTimeout = idleTimeout,
                    minimumVersion = minimumVersion,
                )
            return AgentClaudeCode(
                metadata = EntityMetadata(id = agentId),
                name = "dev",
                launcher = ClaudeCodeProcessLauncher(properties),
                properties = properties,
                userId = userId,
                systemPrompt = "Namespace context.",
                instructions = "You are dev.",
                modelConfig = model,
                llmProvider = "claude-code",
                reasoningEffort = ReasoningEffort.HIGH,
                usageAccumulator = usage,
                maxTurns = 20,
            )
        }

        /** Records each call's arguments and message in `calls`, then runs [body]. */
        fun cli(body: String): FakeClaudeCli {
            val dir = directory()
            return FakeClaudeCli(
                dir,
                """
                read -r message
                { echo '---'; cat '$dir/argv'; printf '%s\n' "${'$'}message"; } >> '$dir/calls'
                $body
                """.trimIndent(),
            )
        }

        fun replay(fixture: String) = "cat '${FakeClaudeCli.fixture(fixture)}'"

        fun user(text: String) =
            MessageEvent(
                namespaceId = namespaceId,
                caseId = caseId,
                actor = Actor("user-1", "Selim", ActorRole.USER),
                content = listOf(MessageContent.Text(text)),
            )

        fun finished(runtimeSessionId: String?) =
            AgentFinishedEvent(namespaceId = namespaceId, caseId = caseId, agentId = agentId, agentName = "dev", runtimeSessionId = runtimeSessionId)

        fun FakeClaudeCli.calls(): List<List<String>> =
            directory
                .resolve("calls")
                .readText()
                .split("---\n")
                .filter { it.isNotBlank() }
                .map { it.trimEnd('\n').lines() }

        fun List<String>.valueOf(flag: String): String? = indexOf(flag).takeIf { it >= 0 }?.let { getOrNull(it + 1) }

        "a first run starts a session, streams the answer and hands the session on" {
            val cli = cli(replay("text-turn.jsonl"))
            val usage = UsageAccumulator()

            val events = agent(cli, usage).run(listOf(user("Say OK"))).toList()

            events.first().shouldBeInstanceOf<ThinkingEvent>()
            events.filterIsInstance<TextChunkEvent>().map { it.chunk } shouldBe listOf("OK")
            events.filterIsInstance<MessageEvent>().single().let { message ->
                message.content shouldBe listOf(MessageContent.Text("OK"))
                message.actor.id shouldBe agentId.toString()
                message.llmModel shouldBe "haiku"
                message.reasoningEffort shouldBe ReasoningEffort.HIGH
            }
            val call = cli.calls().single()
            val finished = events.last().shouldBeInstanceOf<AgentFinishedEvent>()
            finished.runtimeSessionId shouldBe call.valueOf("--session-id")
            call.valueOf("--model") shouldBe "haiku"
            call.valueOf("--effort") shouldBe "high"
            call.valueOf("--max-turns") shouldBe "20"
            call.last() shouldBe """{"type":"user","message":{"role":"user","content":"<user name=\"Selim\">Say OK</user>"}}"""
            usage.total.inputTokens shouldBe 2
            usage.total.outputTokens shouldBe 4
            usage.total.cacheWriteTokens shouldBe 662
            usage.total.estimatedCostUsd shouldBe 0.0
        }

        "the system prompt joins the namespace context and the instructions" {
            val dir = directory()
            val cli =
                FakeClaudeCli(
                    dir,
                    """
                    read -r message
                    prompt=${'$'}(awk 'prev == "--system-prompt-file" { print } { prev = ${'$'}0 }' '$dir/argv')
                    cp "${'$'}prompt" '$dir/prompt'
                    ${replay("text-turn.jsonl")}
                    """.trimIndent(),
                )

            agent(cli).run(listOf(user("Say OK"))).toList()

            dir.resolve("prompt").readText() shouldBe "Namespace context.\nYou are dev."
        }

        "the next run resumes the session with only the new message" {
            val cli = cli(replay("text-turn.jsonl"))
            val previous = UUID.randomUUID().toString()

            val events = agent(cli).run(listOf(user("Say OK"), finished(previous), user("Again"))).toList()

            val call = cli.calls().single()
            call.valueOf("--resume") shouldBe previous
            call shouldNotContain "--session-id"
            call.last() shouldBe """{"type":"user","message":{"role":"user","content":"<user name=\"Selim\">Again</user>"}}"""
            events.last().shouldBeInstanceOf<AgentFinishedEvent>().runtimeSessionId shouldBe previous
        }

        "a session the CLI no longer has is replaced by a new one replaying the case" {
            val cli =
                cli(
                    """
                    if grep -qx -- '--resume' "${'$'}(dirname "${'$'}0")/argv"; then
                      ${replay("unknown-session.jsonl")}
                      echo 'No conversation found with session ID: x' >&2
                      exit 1
                    fi
                    ${replay("text-turn.jsonl")}
                    """.trimIndent(),
                )
            val previous = UUID.randomUUID().toString()

            val events = agent(cli).run(listOf(user("Say OK"), finished(previous), user("Again"))).toList()

            val (resume, start) = cli.calls()
            resume.valueOf("--resume") shouldBe previous
            val newSession = start.valueOf("--session-id").shouldNotBeNull()
            start.last() shouldContain "[Conversation so far in this case"
            events.filterIsInstance<ErrorEvent>().shouldBeEmpty()
            events.filterIsInstance<MessageEvent>().single().content shouldBe listOf(MessageContent.Text("OK"))
            events.last().shouldBeInstanceOf<AgentFinishedEvent>().runtimeSessionId shouldBe newSession
        }

        "a CLI nobody signed in to gives the user the login command, and no session" {
            val cli = cli(replay("not-logged-in.jsonl") + "\nexit 1")

            val events = agent(cli).run(listOf(user("Say OK"))).toList()

            events.filterIsInstance<MessageEvent>().shouldBeEmpty()
            val error = events.filterIsInstance<ErrorEvent>().single().message
            error shouldContain "not signed in"
            error shouldContain "CLAUDE_CONFIG_DIR="
            error shouldContain "/$userId' claude auth login"
            events.last().shouldBeInstanceOf<AgentFinishedEvent>().runtimeSessionId.shouldBeNull()
        }

        "a rejected subscription window says when it resets" {
            val cli =
                cli(
                    """
                    echo '{"type":"system","subtype":"init","session_id":"s","model":"m","claude_code_version":"2.1.318","tools":[],"mcp_servers":[]}'
                    echo '{"type":"rate_limit_event","rate_limit_info":{"status":"rejected","resetsAt":1793793600,"rateLimitType":"five_hour"}}'
                    echo '{"type":"assistant","message":{"content":[{"type":"text","text":"Limit reached"}]},"error":"rate_limit"}'
                    echo '{"type":"result","subtype":"success","is_error":true,"result":"Limit reached","num_turns":1,"usage":{}}'
                    """.trimIndent(),
                )

            val events = agent(cli).run(listOf(user("Say OK"))).toList()

            events.filterIsInstance<ErrorEvent>().single().message shouldBe
                "Your Claude subscription limit is reached, it resets at 2026-11-04T12:00:00Z (UTC)."
            events.filterIsInstance<MessageEvent>().shouldBeEmpty()
        }

        "a CLI older than the minimum version is stopped before it answers" {
            val cli = cli(replay("text-turn.jsonl"))

            val events = agent(cli, minimumVersion = "2.1.400").run(listOf(user("Say OK"))).toList()

            events.filterIsInstance<ErrorEvent>().single().message shouldContain "version 2.1.317, older than 2.1.400"
            events.last().shouldBeInstanceOf<AgentFinishedEvent>().runtimeSessionId.shouldBeNull()
        }

        "a stop request ends a running CLI without an error and without a session" {
            val cli = cli("sleep 30")
            val calls = java.util.concurrent.atomic.AtomicInteger()

            // shouldContinue is polled by the watchdog: it turns false after a few polls.
            val events = agent(cli).run(listOf(user("Say OK"))) { calls.incrementAndGet() < 5 }.toList()

            events.filterIsInstance<ErrorEvent>().shouldBeEmpty()
            events.last().shouldBeInstanceOf<AgentFinishedEvent>().runtimeSessionId.shouldBeNull()
        }

        "a silent CLI is stopped after the idle timeout" {
            val cli = cli("sleep 30")

            val events = agent(cli, idleTimeout = Duration.ofMillis(500)).run(listOf(user("Say OK"))).toList()

            events.filterIsInstance<ErrorEvent>().single().message shouldContain "printed nothing"
            events.last().shouldBeInstanceOf<AgentFinishedEvent>().runtimeSessionId.shouldBeNull()
        }

        "a missing CLI is reported to the user" {
            val properties = ClaudeCodeProperties(enabled = true, executable = "/nonexistent/claude", configRoot = directory())
            val agent =
                AgentClaudeCode(
                    metadata = EntityMetadata(id = agentId),
                    name = "dev",
                    launcher = ClaudeCodeProcessLauncher(properties),
                    properties = properties,
                    userId = userId,
                    modelConfig = model,
                    llmProvider = "claude-code",
                )

            val events = agent.run(listOf(user("Say OK"))).toList()

            events.filterIsInstance<ErrorEvent>().single().message shouldContain "cannot be started on the AgentOS host"
            events.last().shouldBeInstanceOf<AgentFinishedEvent>()
        }

        "a run asked to stop before it starts launches nothing" {
            val cli = cli(replay("text-turn.jsonl"))

            val events: List<CaseEvent> = agent(cli).run(listOf(user("Say OK"))) { false }.toList()

            events.single().shouldBeInstanceOf<AgentFinishedEvent>()
            Files.exists(cli.directory.resolve("calls")) shouldBe false
        }

        "the CLI process runs the user's own configuration directory" {
            val cli = cli(replay("text-turn.jsonl"))

            agent(cli).run(listOf(user("Say OK"))).toList()

            cli.environment()["CLAUDE_CONFIG_DIR"].shouldNotBeNull() shouldContain "/$userId"
            cli.directory.resolve("argv").readLines() shouldContain "--permission-prompts"
        }
    })
