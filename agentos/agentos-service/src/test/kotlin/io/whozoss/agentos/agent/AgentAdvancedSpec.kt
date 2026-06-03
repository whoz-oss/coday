package io.whozoss.agentos.agent

import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.whozoss.agentos.redirect.RedirectTool
import io.whozoss.agentos.sdk.actor.Actor
import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.caseEvent.AgentFinishedEvent
import io.whozoss.agentos.sdk.caseEvent.AgentSelectedEvent
import io.whozoss.agentos.sdk.caseEvent.ConfirmationResolvedEvent
import io.whozoss.agentos.sdk.caseEvent.ErrorEvent
import io.whozoss.agentos.sdk.caseEvent.IntentionGeneratedEvent
import io.whozoss.agentos.sdk.caseEvent.MessageContent
import io.whozoss.agentos.sdk.caseEvent.MessageEvent
import io.whozoss.agentos.sdk.caseEvent.PendingConfirmationEvent
import io.whozoss.agentos.sdk.caseEvent.TextChunkEvent
import io.whozoss.agentos.sdk.caseEvent.ThinkingEvent
import io.whozoss.agentos.sdk.caseEvent.ToolRequestEvent
import io.whozoss.agentos.sdk.caseEvent.ToolResponseEvent
import io.whozoss.agentos.sdk.caseEvent.WarnEvent
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.sdk.tool.ConfirmationMode
import io.whozoss.agentos.sdk.tool.EnrichmentResult
import io.whozoss.agentos.sdk.tool.IntermediatePhaseDescriptor
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolExecutionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.retry.NonTransientAiException
import reactor.core.publisher.Flux
import java.nio.file.Files
import java.util.UUID
import kotlin.io.path.exists
import kotlin.io.path.writeText

/**
 * Test-only confirmation-aware tool that mimics the shape of RemoveFileTool. Deletes a
 * file path under [rootDir] when invoked. The orchestrator calls `executeWithJson`
 * (default impl parses then delegates to `execute`) for both standard and post-
 * confirmation paths.
 */
internal class TestRemoveTool(
    private val rootDir: java.nio.file.Path,
    override val name: String = "FILES__remove",
    override val confirmationMode: ConfirmationMode = ConfirmationMode.EVERY_TIME,
) : StandardTool<TestRemoveTool.Input> {
    data class Input(
        val path: String? = null,
    )

    override val description: String = "Remove a file"
    override val inputSchema: String = """{"type":"object","properties":{"path":{"type":"string"}}}"""
    override val version: String = "1.0.0"
    override val paramType: Class<Input> = Input::class.java

    override suspend fun execute(
        input: Input?,
        context: ToolContext,
    ): ToolExecutionResult {
        val path = input?.path?.takeIf { it.isNotBlank() } ?: return ToolExecutionResult.error("Error: path required")
        val resolved = rootDir.resolve(path)
        if (Files.isDirectory(resolved)) return ToolExecutionResult.error("Error: Cannot remove directories: $path")
        withContext(Dispatchers.IO) {
            Files.deleteIfExists(resolved)
        }
        return ToolExecutionResult.success("File $path deleted successfully")
    }

    override fun getConfirmationInstructions(): String = "Be strict: explicit confirmation only after the assistant's question."

    // The orchestrator invokes executeWithJson (parses JSON then calls execute) for the
    // post-confirmation path. onRejected: default returns "Action cancelled.".
}

class AgentAdvancedSpec :
    StringSpec({
        timeout = 5000

        val testObjectMapper = ObjectMapper()

        fun makeParserAgent(): AgentAdvanced {
            val mockChatClient = mockk<ChatClient>(relaxed = true)
            val agentId = UUID.randomUUID()
            val context =
                AgentAdvancedContext(
                    chatClient = mockChatClient,
                    tools = emptyList(),
                    instructions = null,
                    agentId = agentId,
                    confirmationManager = mockk(relaxed = true),
                )
            return AgentAdvanced(
                name = "ParserAgent",
                context = context,
                intentionGenerator = mockk(),
                objectMapper = testObjectMapper,
            )
        }

        fun makeInitialEvents(
            namespaceId: UUID,
            caseId: UUID,
        ) = listOf(
            MessageEvent(
                namespaceId = namespaceId,
                caseId = caseId,
                actor = Actor("user1", "User One", ActorRole.USER),
                content = listOf(MessageContent.Text("Hello, can you help me?")),
            ),
        )

        // -------------------------------------------------------------------------
        // Redirect contract
        // -------------------------------------------------------------------------

        "on redirect, AgentFinishedEvent is emitted before AgentSelectedEvent" {
            // AgentAdvanced.executeTool() must re-throw AgentInterrupt so run()'s catch block
            // handles it. The catch block must emit AgentFinishedEvent then AgentSelectedEvent
            // in that order — CaseRuntime.processNextStep scans newest-first, so
            // AgentSelectedEvent must appear after AgentFinishedEvent.
            val namespaceId = UUID.randomUUID()
            val caseId = UUID.randomUUID()
            val agentId = UUID.randomUUID()

            val redirectTool =
                RedirectTool(
                    configName = "REDIRECT",
                    eligibleAgents = listOf(RedirectTool.EligibleAgent("TargetAgent", "does stuff")),
                )

            val mockChatClient = mockk<ChatClient>(relaxed = true)
            val mockStreamSpec = mockk<ChatClient.StreamResponseSpec>(relaxed = true)
            every {
                mockChatClient.prompt(any<Prompt>()).call().content()
            } returns """{"agentName":"TargetAgent"}"""
            every { mockChatClient.prompt(any<Prompt>()).stream() } returns mockStreamSpec
            every { mockStreamSpec.content() } returns Flux.empty()

            val mockGenerator = mockk<AgentIntentionGenerator>()
            every {
                mockGenerator.generate(any(), any(), any(), any(), any())
            } returns
                IntentionGeneratedEvent(
                    namespaceId = namespaceId,
                    caseId = caseId,
                    agentId = agentId,
                    intention = "Redirect to TargetAgent.",
                    toolName = "REDIRECT__redirect",
                )

            val context =
                AgentAdvancedContext(
                    chatClient = mockChatClient,
                    tools = listOf(redirectTool),
                    instructions = null,
                    agentId = agentId,
                    confirmationManager = mockk(relaxed = true),
                )
            val agent =
                AgentAdvanced(
                    metadata = EntityMetadata(id = agentId),
                    name = "TestAgent",
                    context = context,
                    intentionGenerator = mockGenerator,
                    objectMapper = testObjectMapper,
                    maxIterations = 5,
                )

            val events =
                agent
                    .run(
                        listOf(
                            MessageEvent(
                                namespaceId = namespaceId,
                                caseId = caseId,
                                actor = Actor("user1", "User One", ActorRole.USER),
                                content = listOf(MessageContent.Text("do the thing")),
                            ),
                        ),
                    ).toList()

            val finishedIndex = events.indexOfFirst { it is AgentFinishedEvent }
            val selectedIndex = events.indexOfFirst { it is AgentSelectedEvent }

            (finishedIndex >= 0) shouldBe true
            (selectedIndex >= 0) shouldBe true
            // AgentFinishedEvent must come before AgentSelectedEvent
            (finishedIndex < selectedIndex) shouldBe true
            (events[selectedIndex] as AgentSelectedEvent).agentName shouldBe "TargetAgent"
        }

        "on redirect, no WarnEvent is emitted" {
            // AgentInterrupt is not an error — the catch block must not emit WarnEvent.
            val namespaceId = UUID.randomUUID()
            val caseId = UUID.randomUUID()
            val agentId = UUID.randomUUID()

            val redirectTool =
                RedirectTool(
                    configName = "REDIRECT",
                    eligibleAgents = listOf(RedirectTool.EligibleAgent("TargetAgent", "does stuff")),
                )

            val mockChatClient = mockk<ChatClient>(relaxed = true)
            val mockStreamSpec = mockk<ChatClient.StreamResponseSpec>(relaxed = true)
            every {
                mockChatClient.prompt(any<Prompt>()).call().content()
            } returns """{"agentName":"TargetAgent"}"""
            every { mockChatClient.prompt(any<Prompt>()).stream() } returns mockStreamSpec
            every { mockStreamSpec.content() } returns Flux.empty()

            val mockGenerator = mockk<AgentIntentionGenerator>()
            every {
                mockGenerator.generate(any(), any(), any(), any(), any())
            } returns
                IntentionGeneratedEvent(
                    namespaceId = namespaceId,
                    caseId = caseId,
                    agentId = agentId,
                    intention = "Redirect to TargetAgent.",
                    toolName = "REDIRECT__redirect",
                )

            val context =
                AgentAdvancedContext(
                    chatClient = mockChatClient,
                    tools = listOf(redirectTool),
                    instructions = null,
                    agentId = agentId,
                    confirmationManager = mockk(relaxed = true),
                )
            val agent =
                AgentAdvanced(
                    metadata = EntityMetadata(id = agentId),
                    name = "TestAgent",
                    context = context,
                    intentionGenerator = mockGenerator,
                    objectMapper = testObjectMapper,
                    maxIterations = 5,
                )

            val events =
                agent
                    .run(
                        listOf(
                            MessageEvent(
                                namespaceId = namespaceId,
                                caseId = caseId,
                                actor = Actor("user1", "User One", ActorRole.USER),
                                content = listOf(MessageContent.Text("do the thing")),
                            ),
                        ),
                    ).toList()

            events.filterIsInstance<WarnEvent>() shouldHaveSize 0
        }

        "on redirect, a ToolResponseEvent is emitted before AgentFinishedEvent" {
            // The redirect tool throws AgentInterrupt.Redirect. handleToolExecution()
            // must catch it, create and emit a ToolResponseEvent, add it to
            // accumulatedEvents, then re-throw. This ensures the event history is
            // well-formed: every ToolRequestEvent has a matching ToolResponseEvent.
            val namespaceId = UUID.randomUUID()
            val caseId = UUID.randomUUID()
            val agentId = UUID.randomUUID()

            val redirectTool =
                RedirectTool(
                    configName = "REDIRECT",
                    eligibleAgents = listOf(RedirectTool.EligibleAgent("TargetAgent", "does stuff")),
                )

            val mockChatClient = mockk<ChatClient>(relaxed = true)
            val mockStreamSpec = mockk<ChatClient.StreamResponseSpec>(relaxed = true)
            every {
                mockChatClient.prompt(any<Prompt>()).call().content()
            } returns """{"agentName":"TargetAgent"}"""
            every { mockChatClient.prompt(any<Prompt>()).stream() } returns mockStreamSpec
            every { mockStreamSpec.content() } returns Flux.empty()

            val mockGenerator = mockk<AgentIntentionGenerator>()
            every {
                mockGenerator.generate(any(), any(), any(), any(), any())
            } returns
                IntentionGeneratedEvent(
                    namespaceId = namespaceId,
                    caseId = caseId,
                    agentId = agentId,
                    intention = "Redirect to TargetAgent.",
                    toolName = "REDIRECT__redirect",
                )

            val context =
                AgentAdvancedContext(
                    chatClient = mockChatClient,
                    tools = listOf(redirectTool),
                    instructions = null,
                    agentId = agentId,
                    confirmationManager = mockk(relaxed = true),
                )
            val agent =
                AgentAdvanced(
                    metadata = EntityMetadata(id = agentId),
                    name = "TestAgent",
                    context = context,
                    intentionGenerator = mockGenerator,
                    objectMapper = testObjectMapper,
                    maxIterations = 5,
                )

            val events =
                agent
                    .run(
                        listOf(
                            MessageEvent(
                                namespaceId = namespaceId,
                                caseId = caseId,
                                actor = Actor("user1", "User One", ActorRole.USER),
                                content = listOf(MessageContent.Text("do the thing")),
                            ),
                        ),
                    ).toList()

            // A ToolRequestEvent must exist for the redirect tool
            val toolRequests = events.filterIsInstance<ToolRequestEvent>()
            toolRequests shouldHaveSize 1
            toolRequests[0].toolName shouldBe "REDIRECT__redirect"

            // A matching ToolResponseEvent must exist
            val toolResponses = events.filterIsInstance<ToolResponseEvent>()
            toolResponses shouldHaveSize 1
            toolResponses[0].toolRequestId shouldBe toolRequests[0].toolRequestId
            toolResponses[0].success shouldBe true
            (toolResponses[0].output as MessageContent.Text).content shouldContain "TargetAgent"

            // ToolResponseEvent must appear before AgentFinishedEvent
            val responseIndex = events.indexOf(toolResponses[0])
            val finishedIndex = events.indexOfFirst { it is AgentFinishedEvent }
            (responseIndex < finishedIndex) shouldBe true
        }

        // -------------------------------------------------------------------------
        // Orchestration integration tests
        // -------------------------------------------------------------------------

        "should complete full orchestration loop when LLM returns Answer tool in XML" {
            val namespaceId = UUID.randomUUID()
            val caseId = UUID.randomUUID()
            val agentId = UUID.randomUUID()

            val mockGenerator = mockk<AgentIntentionGenerator>()
            every {
                mockGenerator.generate(any(), any(), any(), any(), any())
            } returns
                IntentionGeneratedEvent(
                    namespaceId = namespaceId,
                    caseId = caseId,
                    agentId = agentId,
                    intention = "No further tool calls needed.",
                    toolName = "Answer",
                )

            val mockChatClient = mockk<ChatClient>(relaxed = true)
            val mockStreamSpec = mockk<ChatClient.StreamResponseSpec>(relaxed = true)
            every { mockChatClient.prompt(any<Prompt>()).stream() } returns mockStreamSpec
            every { mockStreamSpec.content() } returns Flux.just("Here is ", "my answer.")

            val context =
                AgentAdvancedContext(
                    chatClient = mockChatClient,
                    tools = emptyList(),
                    instructions = null,
                    agentId = agentId,
                    confirmationManager = mockk(relaxed = true),
                )
            val agent =
                AgentAdvanced(
                    metadata = EntityMetadata(id = agentId),
                    name = "TestAgent",
                    context = context,
                    intentionGenerator = mockGenerator,
                    objectMapper = testObjectMapper,
                    maxIterations = 5,
                )

            val events = agent.run(makeInitialEvents(namespaceId, caseId)).toList()

            // ThinkingEvent + IntentionGeneratedEvent
            // + TextChunkEvent(x2) + MessageEvent + AgentFinishedEvent
            events shouldHaveSize 6

            val thinkingEvent = events[0] as? ThinkingEvent
            thinkingEvent shouldNotBe null

            val intentionEvent = events[1] as? IntentionGeneratedEvent
            intentionEvent shouldNotBe null
            intentionEvent!!.agentId shouldBe agentId
            intentionEvent.intention shouldContain "No further tool calls needed"
            intentionEvent.toolName shouldBe "Answer"

            val chunks = events.filterIsInstance<TextChunkEvent>()
            chunks shouldHaveSize 2
            chunks[0].chunk shouldBe "Here is "
            chunks[1].chunk shouldBe "my answer."

            val messageEvent = events.filterIsInstance<MessageEvent>().firstOrNull()
            messageEvent shouldNotBe null
            messageEvent!!.actor.id shouldBe agentId.toString()
            messageEvent.actor.role shouldBe ActorRole.AGENT
            (messageEvent.content.first() as MessageContent.Text).content shouldBe "Here is my answer."

            val finishedEvent = events.last() as? AgentFinishedEvent
            finishedEvent shouldNotBe null
            finishedEvent!!.agentId shouldBe agentId
            finishedEvent.agentName shouldBe "TestAgent"
        }

        "emits WarnEvent when repetition loop is detected and agent eventually selects Answer" {
            val namespaceId = UUID.randomUUID()
            val caseId = UUID.randomUUID()

            val mockChatClient = mockk<ChatClient>(relaxed = true)
            val mockStreamSpec = mockk<ChatClient.StreamResponseSpec>(relaxed = true)
            every { mockChatClient.prompt(any<Prompt>()).stream() } returns mockStreamSpec
            every { mockStreamSpec.content() } returns Flux.just("Loop stopped.")

            val mockTool = mockk<StandardTool<String>>(relaxed = true)
            every { mockTool.name } returns "FILES__ReadFile"
            every { mockTool.description } returns "Read a file"
            every { mockTool.inputSchema } returns "{}"
            every { mockTool.paramType } returns String::class.java
            coEvery { mockTool.executeWithJson(any(), any()) } returns ToolExecutionResult.success("file content")

            // The generator returns FILES__ReadFile 5 times (filling the window with 3+ identical
            // calls), then Answer once the repetition warning is passed to the LLM
            val mockGenerator = mockk<AgentIntentionGenerator>()
            every {
                mockGenerator.generate(any(), any(), any(), any(), isNull())
            } returnsMany
                (1..5).map {
                    IntentionGeneratedEvent(
                        namespaceId = namespaceId,
                        caseId = caseId,
                        agentId = UUID.randomUUID(),
                        intention = "Reading the file (iteration $it).",
                        toolName = "FILES__ReadFile",
                    )
                }
            every {
                mockGenerator.generate(any(), any(), any(), any(), not(isNull()))
            } returns
                IntentionGeneratedEvent(
                    namespaceId = namespaceId,
                    caseId = caseId,
                    agentId = UUID.randomUUID(),
                    intention = "Stopping due to loop.",
                    toolName = "Answer",
                )

            every {
                mockChatClient.prompt(any<Prompt>()).call().content()
            } returns "{}"

            val agentId = UUID.randomUUID()
            val context =
                AgentAdvancedContext(
                    chatClient = mockChatClient,
                    tools = listOf(mockTool),
                    instructions = null,
                    agentId = agentId,
                    confirmationManager = mockk(relaxed = true),
                )
            val agent =
                AgentAdvanced(
                    metadata = EntityMetadata(id = agentId),
                    name = "LoopAgent",
                    context = context,
                    intentionGenerator = mockGenerator,
                    objectMapper = testObjectMapper,
                    maxIterations = 10,
                )

            val events = agent.run(makeInitialEvents(namespaceId, caseId)).toList()

            val warnEvents = events.filterIsInstance<WarnEvent>()
            warnEvents.any { it.message.contains("consecutively") || it.message.contains("FILES__ReadFile") } shouldBe true

            val intentionEvents = events.filterIsInstance<IntentionGeneratedEvent>()
            intentionEvents.last().toolName shouldBe "Answer"

            events.filterIsInstance<AgentFinishedEvent>() shouldHaveSize 1
        }

        // -------------------------------------------------------------------------
        // detectRepetitionLoop unit tests
        // -------------------------------------------------------------------------

        // -------------------------------------------------------------------------
        // buildLanguageHint unit tests
        // -------------------------------------------------------------------------

        "buildLanguageHint returns null when no user messages" {
            val agent = makeParserAgent()
            val namespaceId = UUID.randomUUID()
            val caseId = UUID.randomUUID()
            val events = listOf(
                MessageEvent(
                    namespaceId = namespaceId,
                    caseId = caseId,
                    actor = Actor("agent1", "Astra", ActorRole.AGENT),
                    content = listOf(MessageContent.Text("Bonjour, comment puis-je vous aider ?")),
                ),
            )
            agent.buildLanguageHint(events) shouldBe null
        }

        "buildLanguageHint returns hint with single message when it meets minChars" {
            val agent = makeParserAgent()
            val namespaceId = UUID.randomUUID()
            val caseId = UUID.randomUUID()
            val longMessage = "a".repeat(250)
            val events = listOf(
                MessageEvent(
                    namespaceId = namespaceId,
                    caseId = caseId,
                    actor = Actor("user1", "User One", ActorRole.USER),
                    content = listOf(MessageContent.Text(longMessage)),
                ),
            )
            val hint = agent.buildLanguageHint(events)
            hint shouldNotBe null
            hint!! shouldContain longMessage
            hint shouldContain "Respond in the same language"
        }

        "buildLanguageHint accumulates multiple short messages until minChars is reached" {
            val agent = makeParserAgent()
            val namespaceId = UUID.randomUUID()
            val caseId = UUID.randomUUID()
            // Each message is 50 chars, minChars=200 → needs at least 4
            val messages = (1..6).map { i ->
                MessageEvent(
                    namespaceId = namespaceId,
                    caseId = caseId,
                    actor = Actor("user1", "User One", ActorRole.USER),
                    content = listOf(MessageContent.Text("message-$i-" + "x".repeat(45))),
                )
            }
            val hint = agent.buildLanguageHint(messages, targetChars = 200)
            hint shouldNotBe null
            // Should contain the last few messages (collected newest-first, displayed oldest-first)
            hint!! shouldContain "message-6"
            hint shouldContain "message-5"
            // Should not need to go all the way back to message-1
            hint shouldContain "Respond in the same language"
        }

        "buildLanguageHint returns a hint even when all messages combined are below targetChars" {
            val agent = makeParserAgent()
            val namespaceId = UUID.randomUUID()
            val caseId = UUID.randomUUID()
            val events = listOf(
                MessageEvent(
                    namespaceId = namespaceId,
                    caseId = caseId,
                    actor = Actor("user1", "User One", ActorRole.USER),
                    content = listOf(MessageContent.Text("oui")),
                ),
                MessageEvent(
                    namespaceId = namespaceId,
                    caseId = caseId,
                    actor = Actor("user1", "User One", ActorRole.USER),
                    content = listOf(MessageContent.Text("ok")),
                ),
            )
            // Total = 5 chars, well below targetChars=200 — hint must still be produced
            val hint = agent.buildLanguageHint(events)
            hint shouldNotBe null
            hint!! shouldContain "oui"
            hint shouldContain "ok"
            hint shouldContain "Respond in the same language"
        }

        "buildLanguageHint uses newest messages first when accumulating" {
            val agent = makeParserAgent()
            val namespaceId = UUID.randomUUID()
            val caseId = UUID.randomUUID()
            val events = listOf(
                MessageEvent(
                    namespaceId = namespaceId,
                    caseId = caseId,
                    actor = Actor("user1", "User One", ActorRole.USER),
                    content = listOf(MessageContent.Text("old english message from the beginning")),
                ),
                MessageEvent(
                    namespaceId = namespaceId,
                    caseId = caseId,
                    actor = Actor("user1", "User One", ActorRole.USER),
                    content = listOf(MessageContent.Text("cherche-moi des développeurs Angular à Paris avec 5 ans d'expérience")),
                ),
            )
            val hint = agent.buildLanguageHint(events, targetChars = 50)
            hint shouldNotBe null
            // The latest message alone exceeds minChars=50, so only it should appear
            hint!! shouldContain "Angular"
            hint shouldContain "Respond in the same language"
        }

        "detectRepetitionLoop returns null when fewer than REPETITION_WINDOW tool responses" {
            val agent = makeParserAgent()
            val namespaceId = UUID.randomUUID()
            val caseId = UUID.randomUUID()
            // Only 4 responses — window requires 5
            val events =
                (1..4).map { i ->
                    ToolResponseEvent(
                        namespaceId = namespaceId,
                        caseId = caseId,
                        toolRequestId = "req-$i",
                        toolName = "FILES__ReadFile",
                        output = MessageContent.Text("content"),
                    )
                }

            agent.detectRepetitionLoop(events) shouldBe null
        }

        "detectRepetitionLoop returns tool name when THRESHOLD identical calls within WINDOW" {
            val agent = makeParserAgent()
            val namespaceId = UUID.randomUUID()
            val caseId = UUID.randomUUID()
            val sameArgs = """{"path":"foo.txt"}"""
            // Window of 5 with all 5 identical — clearly a repetition
            val events =
                (1..AgentAdvanced.REPETITION_WINDOW).flatMap { i ->
                    listOf(
                        ToolRequestEvent(
                            namespaceId = namespaceId,
                            caseId = caseId,
                            toolRequestId = "req-$i",
                            toolName = "FILES__ReadFile",
                            args = sameArgs,
                        ),
                        ToolResponseEvent(
                            namespaceId = namespaceId,
                            caseId = caseId,
                            toolRequestId = "req-$i",
                            toolName = "FILES__ReadFile",
                            output = MessageContent.Text("content"),
                        ),
                    )
                }

            agent.detectRepetitionLoop(events) shouldBe "FILES__ReadFile"
        }

        "detectRepetitionLoop returns tool name on two-tool alternation loop (A,B,A,B,A)" {
            val agent = makeParserAgent()
            val namespaceId = UUID.randomUUID()
            val caseId = UUID.randomUUID()
            val argsA = """{"q":"B2"}"""
            val argsB = """{"q":"E2"}"""
            // A appears 3 times in the window of 5 — threshold reached
            val calls =
                listOf(
                    "toolA" to argsA,
                    "toolB" to argsB,
                    "toolA" to argsA,
                    "toolB" to argsB,
                    "toolA" to argsA,
                )
            val events =
                calls
                    .mapIndexed { i, (tool, args) ->
                        listOf(
                            ToolRequestEvent(
                                namespaceId = namespaceId,
                                caseId = caseId,
                                toolRequestId = "req-$i",
                                toolName = tool,
                                args = args,
                            ),
                            ToolResponseEvent(
                                namespaceId = namespaceId,
                                caseId = caseId,
                                toolRequestId = "req-$i",
                                toolName = tool,
                                output = MessageContent.Text("result"),
                            ),
                        )
                    }.flatten()

            agent.detectRepetitionLoop(events) shouldBe "toolA"
        }

        "detectRepetitionLoop returns null when WINDOW consecutive same-tool responses have different args (WZ-32262)" {
            val agent = makeParserAgent()
            val namespaceId = UUID.randomUUID()
            val caseId = UUID.randomUUID()
            val argsList =
                listOf(
                    """{"qualificationNameInput":"B2","rootCategory":"GRADE"}""",
                    """{"qualificationNameInput":"E2","rootCategory":"GRADE"}""",
                    """{"qualificationNameInput":"E4","rootCategory":"GRADE"}""",
                )
            val events =
                argsList
                    .mapIndexed { i, args ->
                        listOf(
                            ToolRequestEvent(
                                namespaceId = namespaceId,
                                caseId = caseId,
                                toolRequestId = "req-$i",
                                toolName = "SearchQualifications",
                                args = args,
                            ),
                            ToolResponseEvent(
                                namespaceId = namespaceId,
                                caseId = caseId,
                                toolRequestId = "req-$i",
                                toolName = "SearchQualifications",
                                output = MessageContent.Text("result $i"),
                            ),
                        )
                    }.flatten()

            agent.detectRepetitionLoop(events) shouldBe null
        }

        "detectRepetitionLoop returns tool name when THRESHOLD failures with same args within WINDOW" {
            val agent = makeParserAgent()
            val namespaceId = UUID.randomUUID()
            val caseId = UUID.randomUUID()
            val sameArgs = """{"path":"missing.txt"}"""
            val events =
                (1..AgentAdvanced.REPETITION_WINDOW).flatMap { i ->
                    listOf(
                        ToolRequestEvent(
                            namespaceId = namespaceId,
                            caseId = caseId,
                            toolRequestId = "req-$i",
                            toolName = "FILES__ReadFile",
                            args = sameArgs,
                        ),
                        ToolResponseEvent(
                            namespaceId = namespaceId,
                            caseId = caseId,
                            toolRequestId = "req-$i",
                            toolName = "FILES__ReadFile",
                            output = MessageContent.Text("Error: file not found"),
                            success = false,
                        ),
                    )
                }

            agent.detectRepetitionLoop(events) shouldBe "FILES__ReadFile"
        }

        "detectRepetitionLoop returns null when no (toolName, args) pair reaches THRESHOLD in the window" {
            val agent = makeParserAgent()
            val namespaceId = UUID.randomUUID()
            val caseId = UUID.randomUUID()
            // 5 calls: FILES__ReadFile appears twice, JIRA__GetIssue three times but with different args each time
            val calls =
                listOf(
                    "FILES__ReadFile" to """{"path":"a.txt"}""",
                    "JIRA__GetIssue" to """{"id":"WZ-1"}""",
                    "FILES__ReadFile" to """{"path":"a.txt"}""",
                    "JIRA__GetIssue" to """{"id":"WZ-2"}""",
                    "JIRA__GetIssue" to """{"id":"WZ-3"}""",
                )
            val events =
                calls
                    .mapIndexed { i, (tool, args) ->
                        listOf(
                            ToolRequestEvent(
                                namespaceId = namespaceId,
                                caseId = caseId,
                                toolRequestId = "req-$i",
                                toolName = tool,
                                args = args,
                            ),
                            ToolResponseEvent(
                                namespaceId = namespaceId,
                                caseId = caseId,
                                toolRequestId = "req-$i",
                                toolName = tool,
                                output = MessageContent.Text("content"),
                            ),
                        )
                    }.flatten()

            agent.detectRepetitionLoop(events) shouldBe null
        }

        // -------------------------------------------------------------------------
        // WZ-31596 Confirmation Flow — AC1..AC12
        // -------------------------------------------------------------------------

        fun confirmationContext(
            tools: List<StandardTool<*>>,
            agentId: UUID,
            confirmationManager: ConfirmationManager,
            chatClient: ChatClient = mockk(relaxed = true),
        ): Pair<AgentAdvancedContext, ChatClient> {
            val mockStreamSpec = mockk<ChatClient.StreamResponseSpec>(relaxed = true)
            every { chatClient.prompt(any<Prompt>()).stream() } returns mockStreamSpec
            every { mockStreamSpec.content() } returns Flux.empty()
            val ctx =
                AgentAdvancedContext(
                    chatClient = chatClient,
                    tools = tools,
                    instructions = null,
                    agentId = agentId,
                    confirmationManager = confirmationManager,
                )
            return ctx to chatClient
        }

        fun mockGeneratorReturning(
            namespaceId: UUID,
            caseId: UUID,
            agentId: UUID,
            toolName: String,
            intentionText: String = "Calling $toolName.",
        ): AgentIntentionGenerator {
            val gen = mockk<AgentIntentionGenerator>()
            every {
                gen.generate(any(), any(), any(), any(), any())
            } returns
                IntentionGeneratedEvent(
                    namespaceId = namespaceId,
                    caseId = caseId,
                    agentId = agentId,
                    intention = intentionText,
                    toolName = toolName,
                )
            return gen
        }

        "AC1: destructive tool triggers PendingConfirmation IN-CHANNEL — no QuestionEvent, conversational only" {
            val tempDir = Files.createTempDirectory("agentadvanced-ac1-").also { it.toFile().deleteOnExit() }
            val target = tempDir.resolve("old.txt").also { it.writeText("data") }
            val namespaceId = UUID.randomUUID()
            val caseId = UUID.randomUUID()
            val agentId = UUID.randomUUID()
            val tool = TestRemoveTool(tempDir)
            val confirmationManager = mockk<ConfirmationManager>()
            every { confirmationManager.formulateQuestion(any(), any(), any(), any()) } returns "Voulez-vous supprimer old.txt?"
            val (ctx, chatClient) = confirmationContext(listOf(tool), agentId, confirmationManager)
            every { chatClient.prompt(any<Prompt>()).call().content() } returns """{"path":"old.txt"}"""

            val agent =
                AgentAdvanced(
                    metadata = EntityMetadata(id = agentId),
                    name = "TestAgent",
                    context = ctx,
                    intentionGenerator = mockGeneratorReturning(namespaceId, caseId, agentId, "FILES__remove"),
                    objectMapper = testObjectMapper,
                    maxIterations = 3,
                )
            val events = agent.run(makeInitialEvents(namespaceId, caseId)).toList()

            val toolReqIdx = events.indexOfFirst { it is ToolRequestEvent }
            val pendingIdx = events.indexOfFirst { it is PendingConfirmationEvent }
            val agentMsgIdx = events.indexOfFirst { it is MessageEvent && it.actor.role == ActorRole.AGENT }
            val finishedIdx = events.indexOfFirst { it is AgentFinishedEvent }

            (toolReqIdx >= 0) shouldBe true
            (pendingIdx > toolReqIdx) shouldBe true
            (agentMsgIdx > pendingIdx) shouldBe true
            (finishedIdx > agentMsgIdx) shouldBe true
            events.filterIsInstance<ToolResponseEvent>() shouldHaveSize 0
            target.exists() shouldBe true
            val agentMsg = events[agentMsgIdx] as MessageEvent
            (agentMsg.content.first() as MessageContent.Text).content shouldBe "Voulez-vous supprimer old.txt?"
            val pending = events[pendingIdx] as PendingConfirmationEvent
            pending.inputJson shouldContain "old.txt"
        }

        "AC2: free-form 'yes' triggers analyzeConfirmation, executes the tool with success=true synthetic response" {
            val tempDir = Files.createTempDirectory("agentadvanced-ac2-").also { it.toFile().deleteOnExit() }
            val target = tempDir.resolve("old.txt").also { it.writeText("data") }
            val namespaceId = UUID.randomUUID()
            val caseId = UUID.randomUUID()
            val agentId = UUID.randomUUID()
            val tool = TestRemoveTool(tempDir)
            val pendingId = UUID.randomUUID()
            val pending =
                PendingConfirmationEvent(
                    metadata = EntityMetadata(id = pendingId),
                    namespaceId = namespaceId,
                    caseId = caseId,
                    toolRequestId = "orig-req-1",
                    toolName = "FILES__remove",
                    inputJson = """{"path":"old.txt"}""",
                )
            val userReply =
                MessageEvent(
                    namespaceId = namespaceId,
                    caseId = caseId,
                    actor = Actor("user1", "User One", ActorRole.USER),
                    content = listOf(MessageContent.Text("oui")),
                )
            val confirmationManager = mockk<ConfirmationManager>(relaxed = true)
            every { confirmationManager.analyzeConfirmation(any(), any(), any(), any()) } returns ConfirmationDecision.CONFIRMED
            val (ctx, _) = confirmationContext(listOf(tool), agentId, confirmationManager)
            val agent =
                AgentAdvanced(
                    metadata = EntityMetadata(id = agentId),
                    name = "TestAgent",
                    context = ctx,
                    intentionGenerator = mockGeneratorReturning(namespaceId, caseId, agentId, "Answer"),
                    objectMapper = testObjectMapper,
                    maxIterations = 1,
                )
            val events = agent.run(makeInitialEvents(namespaceId, caseId) + pending + userReply).toList()

            val resolved = events.filterIsInstance<ConfirmationResolvedEvent>().single()
            resolved.confirmed shouldBe true
            resolved.pendingEventId shouldBe pendingId
            resolved.resultText shouldContain "deleted successfully"

            val resolutionMsg =
                events
                    .filterIsInstance<MessageEvent>()
                    .filter { it.actor.role == ActorRole.AGENT }
                    .first { (it.content.first() as MessageContent.Text).content.startsWith("User confirmed.") }
            (resolutionMsg.content.first() as MessageContent.Text).content shouldContain "deleted successfully"

            val synthetic =
                events.filterIsInstance<ToolResponseEvent>().single { it.toolRequestId == "orig-req-1" }
            synthetic.success shouldBe true
            (synthetic.output as MessageContent.Text).content shouldContain "deleted successfully"

            target.exists() shouldBe false
            verify(exactly = 1) { confirmationManager.analyzeConfirmation(any(), any(), any(), any()) }
        }

        "AC3: free-form 'no' emits rejection, falls through to the intention loop, and never retries the destructive tool" {
            val tempDir = Files.createTempDirectory("agentadvanced-ac3-").also { it.toFile().deleteOnExit() }
            val target = tempDir.resolve("old.txt").also { it.writeText("data") }
            val namespaceId = UUID.randomUUID()
            val caseId = UUID.randomUUID()
            val agentId = UUID.randomUUID()
            val tool = TestRemoveTool(tempDir)
            val pending =
                PendingConfirmationEvent(
                    namespaceId = namespaceId,
                    caseId = caseId,
                    toolRequestId = "orig-req-2",
                    toolName = "FILES__remove",
                    inputJson = """{"path":"old.txt"}""",
                )
            val userReply =
                MessageEvent(
                    namespaceId = namespaceId,
                    caseId = caseId,
                    actor = Actor("user1", "User One", ActorRole.USER),
                    content = listOf(MessageContent.Text("non")),
                )
            val confirmationManager = mockk<ConfirmationManager>(relaxed = true)
            every { confirmationManager.analyzeConfirmation(any(), any(), any(), any()) } returns ConfirmationDecision.REJECTED
            val (ctx, _) = confirmationContext(listOf(tool), agentId, confirmationManager)
            val intentionGenerator = mockGeneratorReturning(namespaceId, caseId, agentId, "Answer")
            val agent =
                AgentAdvanced(
                    metadata = EntityMetadata(id = agentId),
                    name = "TestAgent",
                    context = ctx,
                    intentionGenerator = intentionGenerator,
                    objectMapper = testObjectMapper,
                    maxIterations = 5,
                )
            val events = agent.run(makeInitialEvents(namespaceId, caseId) + pending + userReply).toList()

            val resolved = events.filterIsInstance<ConfirmationResolvedEvent>().single()
            resolved.confirmed shouldBe false
            resolved.resultText shouldBe "Action cancelled."

            val resolutionMsg =
                events
                    .filterIsInstance<MessageEvent>()
                    .filter { it.actor.role == ActorRole.AGENT }
                    .first { (it.content.first() as MessageContent.Text).content.startsWith("User declined.") }
            (resolutionMsg.content.first() as MessageContent.Text).content shouldContain "Action cancelled."

            val synthetic =
                events.filterIsInstance<ToolResponseEvent>().single { it.toolRequestId == "orig-req-2" }
            synthetic.success shouldBe false
            target.exists() shouldBe true

            verify(atLeast = 1) { intentionGenerator.generate(any(), any(), any(), any(), any()) }
            events.filterIsInstance<AgentFinishedEvent>() shouldHaveSize 1

            events.filterIsInstance<ToolRequestEvent>().filter { it.toolName == "FILES__remove" } shouldHaveSize 0
            events.filterIsInstance<ToolResponseEvent>().filter {
                it.toolName == "FILES__remove" && it.toolRequestId != "orig-req-2"
            } shouldHaveSize 0
        }

        "AC5: ambiguous free-form reply emits an LLM-generated IN-CHANNEL re-ask and keeps pending alive" {
            val tempDir = Files.createTempDirectory("agentadvanced-ac5-").also { it.toFile().deleteOnExit() }
            val target = tempDir.resolve("old.txt").also { it.writeText("data") }
            val namespaceId = UUID.randomUUID()
            val caseId = UUID.randomUUID()
            val agentId = UUID.randomUUID()
            val tool = TestRemoveTool(tempDir)
            val pending =
                PendingConfirmationEvent(
                    namespaceId = namespaceId,
                    caseId = caseId,
                    toolRequestId = "orig-req-5",
                    toolName = "FILES__remove",
                    inputJson = """{"path":"old.txt"}""",
                )
            val userReply =
                MessageEvent(
                    namespaceId = namespaceId,
                    caseId = caseId,
                    actor = Actor("user1", "User One", ActorRole.USER),
                    content = listOf(MessageContent.Text("hmm peut-\u00eatre")),
                )
            val confirmationManager = mockk<ConfirmationManager>()
            every { confirmationManager.analyzeConfirmation(any(), any(), any(), any()) } returns
                ConfirmationDecision.AMBIGUOUS
            val clarificationText = "Pour \u00eatre s\u00fbr \u2014 veux-tu vraiment supprimer old.txt ? Oui ou non."
            every { confirmationManager.formulateQuestion(any(), any(), any(), any()) } returns clarificationText
            val (ctx, _) = confirmationContext(listOf(tool), agentId, confirmationManager)
            val agent =
                AgentAdvanced(
                    metadata = EntityMetadata(id = agentId),
                    name = "TestAgent",
                    context = ctx,
                    intentionGenerator = mockGeneratorReturning(namespaceId, caseId, agentId, "Answer"),
                    objectMapper = testObjectMapper,
                    maxIterations = 1,
                )
            val events = agent.run(makeInitialEvents(namespaceId, caseId) + pending + userReply).toList()

            events.filterIsInstance<ConfirmationResolvedEvent>() shouldHaveSize 0
            events.filterIsInstance<ToolResponseEvent>() shouldHaveSize 0
            events.filterIsInstance<WarnEvent>() shouldHaveSize 0
            val agentMessages =
                events.filterIsInstance<MessageEvent>().filter { it.actor.role == ActorRole.AGENT }
            val reAsk = agentMessages.single()
            (reAsk.content.first() as MessageContent.Text).content shouldBe clarificationText
            events.filterIsInstance<AgentFinishedEvent>() shouldHaveSize 1
            target.exists() shouldBe true
        }

        "AC6bis: tool with confirmationMode=INFER + shouldConfirm=false executes directly without PendingConfirmation" {
            val namespaceId = UUID.randomUUID()
            val caseId = UUID.randomUUID()
            val agentId = UUID.randomUUID()
            val tempDir = Files.createTempDirectory("agentadvanced-ac6bis-").also { it.toFile().deleteOnExit() }
            val target = tempDir.resolve("safe.txt").also { it.writeText("data") }
            val tool = TestRemoveTool(tempDir, name = "TEST__safe", confirmationMode = ConfirmationMode.INFER)
            val confirmationManager = mockk<ConfirmationManager>()
            every { confirmationManager.shouldConfirm(any(), any(), any(), any()) } returns false
            val (ctx, chatClient) = confirmationContext(listOf(tool), agentId, confirmationManager)
            every { chatClient.prompt(any<Prompt>()).call().content() } returns """{"path":"safe.txt"}"""

            val mockGenerator = mockk<AgentIntentionGenerator>()
            every {
                mockGenerator.generate(any(), any(), any(), any(), any())
            } returnsMany
                listOf(
                    IntentionGeneratedEvent(
                        namespaceId = namespaceId,
                        caseId = caseId,
                        agentId = agentId,
                        intention = "delete safe.txt",
                        toolName = "TEST__safe",
                    ),
                    IntentionGeneratedEvent(
                        namespaceId = namespaceId,
                        caseId = caseId,
                        agentId = agentId,
                        intention = "done",
                        toolName = "Answer",
                    ),
                )

            val agent =
                AgentAdvanced(
                    metadata = EntityMetadata(id = agentId),
                    name = "TestAgent",
                    context = ctx,
                    intentionGenerator = mockGenerator,
                    objectMapper = testObjectMapper,
                    maxIterations = 5,
                )
            val events = agent.run(makeInitialEvents(namespaceId, caseId)).toList()

            events.filterIsInstance<PendingConfirmationEvent>() shouldHaveSize 0
            val response = events.filterIsInstance<ToolResponseEvent>().single()
            response.success shouldBe true
            (response.output as MessageContent.Text).content shouldContain "deleted successfully"
            target.exists() shouldBe false
        }

        "AC6bis-fail: implicit-consent path emits success=false when tool throws" {
            val namespaceId = UUID.randomUUID()
            val caseId = UUID.randomUUID()
            val agentId = UUID.randomUUID()
            val tool =
                object : StandardTool<Map<String, Any>> {
                    override val name = "TEST__failing"
                    override val description = "throws on execute"
                    override val inputSchema = "{}"
                    override val version = "1.0.0"
                    override val paramType = null
                    override val confirmationMode = ConfirmationMode.INFER

                    override suspend fun execute(
                        input: Map<String, Any>?,
                        context: ToolContext,
                    ): ToolExecutionResult = throw RuntimeException("boom")
                }
            val confirmationManager = mockk<ConfirmationManager>()
            every { confirmationManager.shouldConfirm(any(), any(), any(), any()) } returns false
            val (ctx, chatClient) = confirmationContext(listOf(tool), agentId, confirmationManager)
            every { chatClient.prompt(any<Prompt>()).call().content() } returns "{}"

            val mockGenerator = mockk<AgentIntentionGenerator>()
            every {
                mockGenerator.generate(any(), any(), any(), any(), any())
            } returnsMany
                listOf(
                    IntentionGeneratedEvent(
                        namespaceId = namespaceId,
                        caseId = caseId,
                        agentId = agentId,
                        intention = "call failing tool",
                        toolName = "TEST__failing",
                    ),
                    IntentionGeneratedEvent(
                        namespaceId = namespaceId,
                        caseId = caseId,
                        agentId = agentId,
                        intention = "done",
                        toolName = "Answer",
                    ),
                )

            val agent =
                AgentAdvanced(
                    metadata = EntityMetadata(id = agentId),
                    name = "TestAgent",
                    context = ctx,
                    intentionGenerator = mockGenerator,
                    objectMapper = testObjectMapper,
                    maxIterations = 5,
                )
            val events = agent.run(makeInitialEvents(namespaceId, caseId)).toList()

            events.filterIsInstance<PendingConfirmationEvent>() shouldHaveSize 0
            val response =
                events.filterIsInstance<ToolResponseEvent>().single { it.toolName == "TEST__failing" }
            response.success shouldBe false
            (response.output as MessageContent.Text).content shouldContain "boom"
        }

        "AC7: reload session without user reply emits AgentFinished, no ConfirmationResolved, file untouched" {
            val tempDir = Files.createTempDirectory("agentadvanced-ac7-").also { it.toFile().deleteOnExit() }
            val target = tempDir.resolve("old.txt").also { it.writeText("data") }
            val namespaceId = UUID.randomUUID()
            val caseId = UUID.randomUUID()
            val agentId = UUID.randomUUID()
            val tool = TestRemoveTool(tempDir)
            val initialUserMsg = makeInitialEvents(namespaceId, caseId)
            val pending =
                PendingConfirmationEvent(
                    namespaceId = namespaceId,
                    caseId = caseId,
                    toolRequestId = "orig-req-7",
                    toolName = "FILES__remove",
                    inputJson = """{"path":"old.txt"}""",
                )
            val (ctx, _) = confirmationContext(listOf(tool), agentId, mockk(relaxed = true))
            val agent =
                AgentAdvanced(
                    metadata = EntityMetadata(id = agentId),
                    name = "TestAgent",
                    context = ctx,
                    intentionGenerator = mockk(),
                    objectMapper = testObjectMapper,
                    maxIterations = 1,
                )
            val events = agent.run(initialUserMsg + pending).toList()

            events.filterIsInstance<ConfirmationResolvedEvent>() shouldHaveSize 0
            events.filterIsInstance<WarnEvent>() shouldHaveSize 0
            events.filterIsInstance<AgentFinishedEvent>() shouldHaveSize 1
            target.exists() shouldBe true
        }

        "executeWithJson throws after user confirms: success=false, MessageEvent says declined" {
            val namespaceId = UUID.randomUUID()
            val caseId = UUID.randomUUID()
            val agentId = UUID.randomUUID()
            val tool =
                object : StandardTool<Map<String, Any>> {
                    override val name = "FAILING__remove"
                    override val description = "always throws on executeWithJson"
                    override val inputSchema = "{}"
                    override val version = "1.0.0"
                    override val paramType = null
                    override val confirmationMode = ConfirmationMode.EVERY_TIME

                    override suspend fun execute(
                        input: Map<String, Any>?,
                        context: ToolContext,
                    ): ToolExecutionResult = ToolExecutionResult.success("ok")

                    override suspend fun executeWithJson(
                        json: String?,
                        context: ToolContext,
                    ): ToolExecutionResult = throw RuntimeException("disk full")
                }
            val pending =
                PendingConfirmationEvent(
                    namespaceId = namespaceId,
                    caseId = caseId,
                    toolRequestId = "throw-req",
                    toolName = "FAILING__remove",
                    inputJson = "{}",
                )
            val userYes =
                MessageEvent(
                    namespaceId = namespaceId,
                    caseId = caseId,
                    actor = Actor("user1", "User One", ActorRole.USER),
                    content = listOf(MessageContent.Text("oui")),
                )
            val confirmationManager = mockk<ConfirmationManager>(relaxed = true)
            every { confirmationManager.analyzeConfirmation(any(), any(), any(), any()) } returns ConfirmationDecision.CONFIRMED
            val (ctx, _) = confirmationContext(listOf(tool), agentId, confirmationManager)
            val agent =
                AgentAdvanced(
                    metadata = EntityMetadata(id = agentId),
                    name = "TestAgent",
                    context = ctx,
                    intentionGenerator = mockGeneratorReturning(namespaceId, caseId, agentId, "Answer"),
                    objectMapper = testObjectMapper,
                    maxIterations = 1,
                )
            val events = agent.run(makeInitialEvents(namespaceId, caseId) + pending + userYes).toList()

            val resolved = events.filterIsInstance<ConfirmationResolvedEvent>().single()
            resolved.confirmed shouldBe false
            resolved.resultText shouldContain "disk full"

            val synthetic =
                events.filterIsInstance<ToolResponseEvent>().single { it.toolRequestId == "throw-req" }
            synthetic.success shouldBe false
            (synthetic.output as MessageContent.Text).content shouldContain "disk full"

            val agentMessages =
                events.filterIsInstance<MessageEvent>().filter { it.actor.role == ActorRole.AGENT }
            val resolutionMsg =
                agentMessages.first {
                    (it.content.first() as MessageContent.Text).content.contains("disk full")
                }
            (resolutionMsg.content.first() as MessageContent.Text).content shouldContain "User declined."
        }

        // AC10 removed: confirmationManager is now non-nullable on AgentAdvancedContext.
        // A missing ConfirmationManager is a compile-time error, not a runtime one.
        // The ConfirmationConfigurationException / WarnEvent path is no longer reachable
        // through normal usage — DI wiring failures surface at Spring context startup.

        "AC12: orphan pending (missing tool) closes durably with ConfirmationResolved + MessageEvent + WarnEvent" {
            val namespaceId = UUID.randomUUID()
            val caseId = UUID.randomUUID()
            val agentId = UUID.randomUUID()
            val pendingId = UUID.randomUUID()
            val pending =
                PendingConfirmationEvent(
                    metadata = EntityMetadata(id = pendingId),
                    namespaceId = namespaceId,
                    caseId = caseId,
                    toolRequestId = "orphan-req",
                    toolName = "SOMETHING__obsolete",
                    inputJson = """{"a":"b"}""",
                )
            val (ctx, _) = confirmationContext(emptyList(), agentId, mockk(relaxed = true))
            val agent =
                AgentAdvanced(
                    metadata = EntityMetadata(id = agentId),
                    name = "TestAgent",
                    context = ctx,
                    intentionGenerator = mockGeneratorReturning(namespaceId, caseId, agentId, "Answer"),
                    objectMapper = testObjectMapper,
                    maxIterations = 1,
                )
            val events = agent.run(makeInitialEvents(namespaceId, caseId) + pending).toList()

            val resolved = events.filterIsInstance<ConfirmationResolvedEvent>().single()
            resolved.confirmed shouldBe false
            resolved.resultText shouldContain "tool 'SOMETHING__obsolete' not available"
            val agentMsg =
                events
                    .filterIsInstance<MessageEvent>()
                    .filter { it.actor.role == ActorRole.AGENT }
                    .single { (it.content.first() as MessageContent.Text).content.contains("Cannot resolve confirmation") }
            (agentMsg.content.first() as MessageContent.Text).content shouldContain "SOMETHING__obsolete"
            events.filterIsInstance<WarnEvent>().any { it.message.contains("Cannot resolve pending confirmation") } shouldBe true
        }

        "NonTransientAiException from LLM during generateParameters surfaces as ErrorEvent + AgentFinishedEvent, no loop" {
            // Regression guard for WZ-32274: a 400 from the LLM provider on the
            // generateParameters call must terminate the run immediately instead of
            // looping until maxIterations is exhausted.
            val namespaceId = UUID.randomUUID()
            val caseId = UUID.randomUUID()
            val agentId = UUID.randomUUID()

            val mockChatClient = mockk<ChatClient>(relaxed = true)
            // The first call() is generateParameters — provider rejects with 400.
            every {
                mockChatClient.prompt(any<Prompt>()).call().content()
            } throws NonTransientAiException("400 - bad_request_error: messages: ...")

            val mockTool = mockk<StandardTool<String>>(relaxed = true)
            every { mockTool.name } returns "FILES__ReadFile"
            every { mockTool.description } returns "Read a file"
            every { mockTool.inputSchema } returns "{}"
            every { mockTool.paramType } returns String::class.java

            val mockGenerator = mockk<AgentIntentionGenerator>()
            every {
                mockGenerator.generate(any(), any(), any(), any(), any())
            } returns
                IntentionGeneratedEvent(
                    namespaceId = namespaceId,
                    caseId = caseId,
                    agentId = agentId,
                    intention = "Read the file.",
                    toolName = "FILES__ReadFile",
                )

            val context =
                AgentAdvancedContext(
                    chatClient = mockChatClient,
                    tools = listOf(mockTool),
                    instructions = null,
                    agentId = agentId,
                    confirmationManager = mockk(relaxed = true),
                )
            val agent =
                AgentAdvanced(
                    metadata = EntityMetadata(id = agentId),
                    name = "TestAgent",
                    context = context,
                    intentionGenerator = mockGenerator,
                    objectMapper = testObjectMapper,
                    maxIterations = 10,
                )

            val events = agent.run(makeInitialEvents(namespaceId, caseId)).toList()

            // Must emit exactly one ErrorEvent mentioning the provider error
            val errorEvents = events.filterIsInstance<ErrorEvent>()
            errorEvents shouldHaveSize 1
            errorEvents[0].message shouldContain "AI provider rejected"

            // Must terminate cleanly with AgentFinishedEvent
            events.filterIsInstance<AgentFinishedEvent>() shouldHaveSize 1

            // The intention generator must have been called only once — no loop
            verify(exactly = 1) { mockGenerator.generate(any(), any(), any(), any(), any()) }
        }

        // -------------------------------------------------------------------------
        // generateParameters retry logic
        // -------------------------------------------------------------------------

        "generateParameters: succeeds on first attempt when LLM returns valid JSON" {
            val namespaceId = UUID.randomUUID()
            val caseId = UUID.randomUUID()
            val agentId = UUID.randomUUID()

            val mockTool = mockk<StandardTool<String>>(relaxed = true)
            every { mockTool.name } returns "TEST__tool"
            every { mockTool.description } returns "A test tool"
            every { mockTool.inputSchema } returns """{"type":"object","properties":{"value":{"type":"string"}}}"""
            every { mockTool.paramType } returns String::class.java
            every { mockTool.confirmationMode } returns ConfirmationMode.NONE
            coEvery { mockTool.executeWithJson(any(), any()) } returns ToolExecutionResult.success("done")

            val mockChatClient = mockk<ChatClient>(relaxed = true)
            val mockStreamSpec = mockk<ChatClient.StreamResponseSpec>(relaxed = true)
            every { mockChatClient.prompt(any<Prompt>()).stream() } returns mockStreamSpec
            every { mockStreamSpec.content() } returns Flux.just("ok")

            // First call returns valid JSON; second call (generateFinalResponse) returns streaming
            every { mockChatClient.prompt(any<Prompt>()).call().content() } returns """{"value":"hello"}"""

            val mockGenerator = mockk<AgentIntentionGenerator>()
            every { mockGenerator.generate(any(), any(), any(), any(), any()) } returnsMany
                listOf(
                    IntentionGeneratedEvent(
                        namespaceId = namespaceId,
                        caseId = caseId,
                        agentId = agentId,
                        intention = "Call the tool",
                        toolName = "TEST__tool",
                    ),
                    IntentionGeneratedEvent(
                        namespaceId = namespaceId,
                        caseId = caseId,
                        agentId = agentId,
                        intention = "Done",
                        toolName = "Answer",
                    ),
                )

            val context =
                AgentAdvancedContext(
                    chatClient = mockChatClient,
                    tools = listOf(mockTool),
                    instructions = null,
                    agentId = agentId,
                    confirmationManager = mockk(relaxed = true),
                )
            val agent =
                AgentAdvanced(
                    metadata = EntityMetadata(id = agentId),
                    name = "RetryAgent",
                    context = context,
                    intentionGenerator = mockGenerator,
                    objectMapper = testObjectMapper,
                    maxIterations = 5,
                )

            val events = agent.run(makeInitialEvents(namespaceId, caseId)).toList()

            // Tool was called exactly once (no retry needed)
            val toolRequests = events.filterIsInstance<ToolRequestEvent>()
            toolRequests shouldHaveSize 1
            toolRequests[0].args shouldBe """{"value":"hello"}"""
            events.filterIsInstance<WarnEvent>().filter { it.message.contains("invalid JSON") } shouldHaveSize 0
        }

        "generateParameters: succeeds on retry when first attempt returns invalid JSON" {
            val namespaceId = UUID.randomUUID()
            val caseId = UUID.randomUUID()
            val agentId = UUID.randomUUID()

            val mockTool = mockk<StandardTool<String>>(relaxed = true)
            every { mockTool.name } returns "TEST__tool"
            every { mockTool.description } returns "A test tool"
            every { mockTool.inputSchema } returns """{"type":"object","properties":{"value":{"type":"string"}}}"""
            every { mockTool.paramType } returns String::class.java
            every { mockTool.confirmationMode } returns ConfirmationMode.NONE
            coEvery { mockTool.executeWithJson(any(), any()) } returns ToolExecutionResult.success("done")

            val mockChatClient = mockk<ChatClient>(relaxed = true)
            val mockStreamSpec = mockk<ChatClient.StreamResponseSpec>(relaxed = true)
            every { mockChatClient.prompt(any<Prompt>()).stream() } returns mockStreamSpec
            every { mockStreamSpec.content() } returns Flux.just("ok")

            // First call returns invalid JSON, second returns valid JSON
            every { mockChatClient.prompt(any<Prompt>()).call().content() } returnsMany
                listOf(
                    "not valid json at all",
                    """{"value":"hello"}""",
                )

            val mockGenerator = mockk<AgentIntentionGenerator>()
            every { mockGenerator.generate(any(), any(), any(), any(), any()) } returnsMany
                listOf(
                    IntentionGeneratedEvent(
                        namespaceId = namespaceId,
                        caseId = caseId,
                        agentId = agentId,
                        intention = "Call the tool",
                        toolName = "TEST__tool",
                    ),
                    IntentionGeneratedEvent(
                        namespaceId = namespaceId,
                        caseId = caseId,
                        agentId = agentId,
                        intention = "Done",
                        toolName = "Answer",
                    ),
                )

            val context =
                AgentAdvancedContext(
                    chatClient = mockChatClient,
                    tools = listOf(mockTool),
                    instructions = null,
                    agentId = agentId,
                    confirmationManager = mockk(relaxed = true),
                )
            val agent =
                AgentAdvanced(
                    metadata = EntityMetadata(id = agentId),
                    name = "RetryAgent",
                    context = context,
                    intentionGenerator = mockGenerator,
                    objectMapper = testObjectMapper,
                    maxIterations = 5,
                )

            val events = agent.run(makeInitialEvents(namespaceId, caseId)).toList()

            // Tool was executed with the valid JSON from the second attempt
            val toolRequests = events.filterIsInstance<ToolRequestEvent>()
            toolRequests shouldHaveSize 1
            toolRequests[0].args shouldBe """{"value":"hello"}"""
            // No error event: retry succeeded before exhausting all attempts
            events.filterIsInstance<WarnEvent>().filter { it.message.contains("all") && it.message.contains("attempts") } shouldHaveSize 0
        }

        "generateParameters: extracts JSON from canonical <parameter> tags on first attempt" {
            // The prompt now asks the LLM to wrap its output in <parameter>...</parameter>.
            // stripJsonFence must extract the content on the first attempt with no retry.
            val namespaceId = UUID.randomUUID()
            val caseId = UUID.randomUUID()
            val agentId = UUID.randomUUID()

            val mockTool = mockk<StandardTool<String>>(relaxed = true)
            every { mockTool.name } returns "ReadEntities"
            every { mockTool.description } returns "Read entities by id"
            every { mockTool.inputSchema } returns """{"type":"object","properties":{"entitiesId":{"type":"array","items":{"type":"string"}}}}"""
            every { mockTool.paramType } returns String::class.java
            every { mockTool.confirmationMode } returns ConfirmationMode.NONE
            coEvery { mockTool.executeWithJson(any(), any()) } returns ToolExecutionResult.success("entity data")

            val mockChatClient = mockk<ChatClient>(relaxed = true)
            val mockStreamSpec = mockk<ChatClient.StreamResponseSpec>(relaxed = true)
            every { mockChatClient.prompt(any<Prompt>()).stream() } returns mockStreamSpec
            every { mockStreamSpec.content() } returns Flux.just("done")

            // LLM correctly wraps JSON in <parameter> tags as instructed
            val response = """<parameter>{"entitiesId":["6790ca2213906f27c141a80b","698c66db04182c7fb1dbd119"]}</parameter>"""
            every { mockChatClient.prompt(any<Prompt>()).call().content() } returns response

            val mockGenerator = mockk<AgentIntentionGenerator>()
            every { mockGenerator.generate(any(), any(), any(), any(), any()) } returnsMany
                listOf(
                    IntentionGeneratedEvent(
                        namespaceId = namespaceId,
                        caseId = caseId,
                        agentId = agentId,
                        intention = "Read the entity profiles.",
                        toolName = "ReadEntities",
                    ),
                    IntentionGeneratedEvent(
                        namespaceId = namespaceId,
                        caseId = caseId,
                        agentId = agentId,
                        intention = "Done.",
                        toolName = "Answer",
                    ),
                )

            val context =
                AgentAdvancedContext(
                    chatClient = mockChatClient,
                    tools = listOf(mockTool),
                    instructions = null,
                    agentId = agentId,
                    confirmationManager = mockk(relaxed = true),
                )
            val agent =
                AgentAdvanced(
                    metadata = EntityMetadata(id = agentId),
                    name = "TestAgent",
                    context = context,
                    intentionGenerator = mockGenerator,
                    objectMapper = testObjectMapper,
                    maxIterations = 5,
                )

            val events = agent.run(makeInitialEvents(namespaceId, caseId)).toList()

            // JSON was extracted from the <parameter> tags and passed to the tool
            val toolRequests = events.filterIsInstance<ToolRequestEvent>()
            toolRequests shouldHaveSize 1
            toolRequests[0].args shouldContain "6790ca2213906f27c141a80b"
            toolRequests[0].args shouldContain "698c66db04182c7fb1dbd119"
            // No retry: extraction succeeded on the first attempt
            events.filterIsInstance<WarnEvent>().filter { it.message.contains("invalid JSON") } shouldHaveSize 0
            events.filterIsInstance<AgentFinishedEvent>() shouldHaveSize 1
        }

        "generateParameters: falls back to null args when all retries produce invalid JSON" {
            val namespaceId = UUID.randomUUID()
            val caseId = UUID.randomUUID()
            val agentId = UUID.randomUUID()

            val mockTool = mockk<StandardTool<String>>(relaxed = true)
            every { mockTool.name } returns "TEST__tool"
            every { mockTool.description } returns "A test tool"
            every { mockTool.inputSchema } returns """{"type":"object","properties":{"value":{"type":"string"}}}"""
            every { mockTool.paramType } returns String::class.java
            every { mockTool.confirmationMode } returns ConfirmationMode.NONE
            coEvery { mockTool.executeWithJson(any(), any()) } returns ToolExecutionResult.success("done")

            val mockChatClient = mockk<ChatClient>(relaxed = true)
            val mockStreamSpec = mockk<ChatClient.StreamResponseSpec>(relaxed = true)
            every { mockChatClient.prompt(any<Prompt>()).stream() } returns mockStreamSpec
            every { mockStreamSpec.content() } returns Flux.just("ok")

            // All MAX_PARAMETER_RETRIES + 1 attempts return invalid JSON
            val invalidResponses = (1..AgentAdvanced.MAX_PARAMETER_ATTEMPTS).map { "not json attempt $it" }
            every { mockChatClient.prompt(any<Prompt>()).call().content() } returnsMany invalidResponses

            val mockGenerator = mockk<AgentIntentionGenerator>()
            every { mockGenerator.generate(any(), any(), any(), any(), any()) } returnsMany
                listOf(
                    IntentionGeneratedEvent(
                        namespaceId = namespaceId,
                        caseId = caseId,
                        agentId = agentId,
                        intention = "Call the tool",
                        toolName = "TEST__tool",
                    ),
                    IntentionGeneratedEvent(
                        namespaceId = namespaceId,
                        caseId = caseId,
                        agentId = agentId,
                        intention = "Done",
                        toolName = "Answer",
                    ),
                )

            val context =
                AgentAdvancedContext(
                    chatClient = mockChatClient,
                    tools = listOf(mockTool),
                    instructions = null,
                    agentId = agentId,
                    confirmationManager = mockk(relaxed = true),
                )
            val agent =
                AgentAdvanced(
                    metadata = EntityMetadata(id = agentId),
                    name = "RetryAgent",
                    context = context,
                    intentionGenerator = mockGenerator,
                    objectMapper = testObjectMapper,
                    maxIterations = 5,
                )

            val events = agent.run(makeInitialEvents(namespaceId, caseId)).toList()

            // args must be null — passing invalid output to the tool would cause a server-side
            // error (HTTP 500) instead of a clean failure the LLM can reason about.
            val toolRequests = events.filterIsInstance<ToolRequestEvent>()
            toolRequests shouldHaveSize 1
            toolRequests[0].args shouldBe null
            // A WarnEvent must be emitted so the failure is visible in the case event stream
            val warns = events.filterIsInstance<WarnEvent>().filter { it.message.contains("TEST__tool") }
            warns shouldHaveSize 1
            warns[0].message shouldContain "Failed to generate valid JSON parameters"
            // The loop continues — the LLM sees the failure and can adapt
            events.filterIsInstance<AgentFinishedEvent>() shouldHaveSize 1
        }

        "ToolNotFoundException: unknown tool name in intention surfaces as WarnEvent, no tool execution" {
            val namespaceId = UUID.randomUUID()
            val caseId = UUID.randomUUID()
            val agentId = UUID.randomUUID()
            val (ctx, _) = confirmationContext(emptyList(), agentId, mockk(relaxed = true))
            val agent =
                AgentAdvanced(
                    metadata = EntityMetadata(id = agentId),
                    name = "TestAgent",
                    context = ctx,
                    intentionGenerator = mockGeneratorReturning(namespaceId, caseId, agentId, "DOES_NOT_EXIST"),
                    objectMapper = testObjectMapper,
                    maxIterations = 1,
                )

            val events = agent.run(makeInitialEvents(namespaceId, caseId)).toList()

            val warn = events.filterIsInstance<WarnEvent>().single()
            warn.message shouldContain "Unknown tool referenced"
            warn.message shouldContain "DOES_NOT_EXIST"
            events.filterIsInstance<ToolResponseEvent>() shouldHaveSize 0
        }

        // -------------------------------------------------------------------------
        // Enrichment (multi-phase parameter preparation) tests
        // -------------------------------------------------------------------------

        "enrichment: tool with intermediatePhaseCount=1 triggers enrich() and injects content into final prompt" {
            val namespaceId = UUID.randomUUID()
            val caseId = UUID.randomUUID()
            val agentId = UUID.randomUUID()

            var enrichCalledWith: String? = null

            // A tool that declares one enrichment phase
            val enrichableTool =
                object : StandardTool<Map<String, Any>> {
                    override val name = "TEST__enrichable"
                    override val description = "Tool with enrichment"
                    override val inputSchema = """{"type":"object","properties":{"data":{"type":"string"}}}"""
                    override val version = "1.0.0"
                    override val paramType = null

                    override suspend fun getIntermediatePhaseCount(): Int = 1

                    override suspend fun getIntermediatePhaseDescriptor(
                        phaseIndex: Int,
                        previousContent: String?,
                    ) = IntermediatePhaseDescriptor(
                        inputSchema = """{"type":"object","properties":{"id":{"type":"string"}}}""",
                        prompt = "Identify the entity.",
                    )

                    override suspend fun enrich(
                        phaseIndex: Int,
                        phaseParametersJson: String,
                        context: ToolContext,
                    ): EnrichmentResult {
                        enrichCalledWith = phaseParametersJson
                        return EnrichmentResult(success = true, content = "Entity details: name=Foo, status=active")
                    }

                    override suspend fun execute(
                        input: Map<String, Any>?,
                        context: ToolContext,
                    ) = ToolExecutionResult.success("done")
                }

            val mockChatClient = mockk<ChatClient>(relaxed = true)
            val mockStreamSpec = mockk<ChatClient.StreamResponseSpec>(relaxed = true)
            every { mockChatClient.prompt(any<Prompt>()).stream() } returns mockStreamSpec
            every { mockStreamSpec.content() } returns Flux.just("OK.")

            // LLM calls: 1) enrichment phase JSON, 2) final params JSON
            every {
                mockChatClient.prompt(any<Prompt>()).call().content()
            } returnsMany
                listOf(
                    """{"id":"123"}""",
                    """{"data":"enriched"}""",
                )

            val mockGenerator = mockk<AgentIntentionGenerator>()
            every {
                mockGenerator.generate(any(), any(), any(), any(), any())
            } returnsMany
                listOf(
                    IntentionGeneratedEvent(
                        namespaceId = namespaceId,
                        caseId = caseId,
                        agentId = agentId,
                        intention = "Use the enrichable tool.",
                        toolName = "TEST__enrichable",
                    ),
                    IntentionGeneratedEvent(
                        namespaceId = namespaceId,
                        caseId = caseId,
                        agentId = agentId,
                        intention = "Done.",
                        toolName = "Answer",
                    ),
                )

            val context =
                AgentAdvancedContext(
                    chatClient = mockChatClient,
                    tools = listOf(enrichableTool),
                    instructions = null,
                    agentId = agentId,
                    confirmationManager = mockk(relaxed = true),
                )
            val agent =
                AgentAdvanced(
                    metadata = EntityMetadata(id = agentId),
                    name = "EnrichAgent",
                    context = context,
                    intentionGenerator = mockGenerator,
                    objectMapper = testObjectMapper,
                    maxIterations = 5,
                )

            val events = agent.run(makeInitialEvents(namespaceId, caseId)).toList()

            // Tool was executed successfully
            val toolResponses = events.filterIsInstance<ToolResponseEvent>()
            toolResponses shouldHaveSize 1
            toolResponses[0].success shouldBe true
            (toolResponses[0].output as MessageContent.Text).content shouldBe "done"

            // enrich() was called with the phase-0 LLM output
            enrichCalledWith shouldNotBe null
            enrichCalledWith!! shouldContain "123"

            // The generated parameters (ToolRequestEvent.args) should reflect the
            // second LLM call — the one that received the enrichment context.
            val toolRequests = events.filterIsInstance<ToolRequestEvent>()
            toolRequests shouldHaveSize 1
            toolRequests[0].args shouldNotBe null
            toolRequests[0].args!! shouldContain "enriched"

            events.filterIsInstance<AgentFinishedEvent>() shouldHaveSize 1
        }

        "enrichment: tool with intermediatePhaseCount=0 skips enrichment entirely" {
            val namespaceId = UUID.randomUUID()
            val caseId = UUID.randomUUID()
            val agentId = UUID.randomUUID()

            val simpleTool = mockk<StandardTool<String>>(relaxed = true)
            every { simpleTool.name } returns "TEST__simple"
            every { simpleTool.description } returns "Simple tool"
            every { simpleTool.inputSchema } returns """{"type":"object"}"""
            every { simpleTool.paramType } returns String::class.java
            coEvery { simpleTool.getIntermediatePhaseCount() } returns 0
            coEvery { simpleTool.executeWithJson(any(), any()) } returns ToolExecutionResult.success("ok")

            val mockChatClient = mockk<ChatClient>(relaxed = true)
            val mockStreamSpec = mockk<ChatClient.StreamResponseSpec>(relaxed = true)
            every { mockChatClient.prompt(any<Prompt>()).stream() } returns mockStreamSpec
            every { mockStreamSpec.content() } returns Flux.just("Done.")
            // Only ONE LLM call for params (no enrichment phase)
            every { mockChatClient.prompt(any<Prompt>()).call().content() } returns "{}"

            val mockGenerator = mockk<AgentIntentionGenerator>()
            every {
                mockGenerator.generate(any(), any(), any(), any(), any())
            } returnsMany
                listOf(
                    IntentionGeneratedEvent(
                        namespaceId = namespaceId,
                        caseId = caseId,
                        agentId = agentId,
                        intention = "Use simple tool.",
                        toolName = "TEST__simple",
                    ),
                    IntentionGeneratedEvent(
                        namespaceId = namespaceId,
                        caseId = caseId,
                        agentId = agentId,
                        intention = "Done.",
                        toolName = "Answer",
                    ),
                )

            val context =
                AgentAdvancedContext(
                    chatClient = mockChatClient,
                    tools = listOf(simpleTool),
                    instructions = null,
                    agentId = agentId,
                    confirmationManager = mockk(relaxed = true),
                )
            val agent =
                AgentAdvanced(
                    metadata = EntityMetadata(id = agentId),
                    name = "SimpleAgent",
                    context = context,
                    intentionGenerator = mockGenerator,
                    objectMapper = testObjectMapper,
                    maxIterations = 5,
                )

            val events = agent.run(makeInitialEvents(namespaceId, caseId)).toList()

            // Tool executed
            val toolResponses = events.filterIsInstance<ToolResponseEvent>()
            toolResponses shouldHaveSize 1
            toolResponses[0].success shouldBe true

            // getIntermediatePhaseDescriptor should never be called for a 0-phase tool
            coVerify(exactly = 0) { simpleTool.getIntermediatePhaseDescriptor(any(), any()) }

            events.filterIsInstance<AgentFinishedEvent>() shouldHaveSize 1
        }

        "enrichment: failed enrich() falls back to single-phase generation without crashing" {
            val namespaceId = UUID.randomUUID()
            val caseId = UUID.randomUUID()
            val agentId = UUID.randomUUID()

            val failingEnrichTool =
                object : StandardTool<Map<String, Any>> {
                    override val name = "TEST__failenrich"
                    override val description = "Tool with failing enrichment"
                    override val inputSchema = """{"type":"object","properties":{"x":{"type":"string"}}}"""
                    override val version = "1.0.0"
                    override val paramType = null

                    override suspend fun getIntermediatePhaseCount(): Int = 1

                    override suspend fun getIntermediatePhaseDescriptor(
                        phaseIndex: Int,
                        previousContent: String?,
                    ) = IntermediatePhaseDescriptor(
                        inputSchema = """{"type":"object","properties":{"id":{"type":"string"}}}""",
                        prompt = "Identify.",
                    )

                    override suspend fun enrich(
                        phaseIndex: Int,
                        phaseParametersJson: String,
                        context: ToolContext,
                    ) = EnrichmentResult(success = false, errorMessage = "Service unavailable")

                    override suspend fun execute(
                        input: Map<String, Any>?,
                        context: ToolContext,
                    ) = ToolExecutionResult.success("executed anyway")
                }

            val mockChatClient = mockk<ChatClient>(relaxed = true)
            val mockStreamSpec = mockk<ChatClient.StreamResponseSpec>(relaxed = true)
            every { mockChatClient.prompt(any<Prompt>()).stream() } returns mockStreamSpec
            every { mockStreamSpec.content() } returns Flux.just("OK.")
            // LLM calls: 1) enrichment phase JSON (before enrich fails), 2) final params (fallback)
            every {
                mockChatClient.prompt(any<Prompt>()).call().content()
            } returnsMany
                listOf(
                    """{"id":"456"}""",
                    """{"x":"fallback"}""",
                )

            val mockGenerator = mockk<AgentIntentionGenerator>()
            every {
                mockGenerator.generate(any(), any(), any(), any(), any())
            } returnsMany
                listOf(
                    IntentionGeneratedEvent(
                        namespaceId = namespaceId,
                        caseId = caseId,
                        agentId = agentId,
                        intention = "Use failenrich tool.",
                        toolName = "TEST__failenrich",
                    ),
                    IntentionGeneratedEvent(
                        namespaceId = namespaceId,
                        caseId = caseId,
                        agentId = agentId,
                        intention = "Done.",
                        toolName = "Answer",
                    ),
                )

            val context =
                AgentAdvancedContext(
                    chatClient = mockChatClient,
                    tools = listOf(failingEnrichTool),
                    instructions = null,
                    agentId = agentId,
                    confirmationManager = mockk(relaxed = true),
                )
            val agent =
                AgentAdvanced(
                    metadata = EntityMetadata(id = agentId),
                    name = "FallbackAgent",
                    context = context,
                    intentionGenerator = mockGenerator,
                    objectMapper = testObjectMapper,
                    maxIterations = 5,
                )

            val events = agent.run(makeInitialEvents(namespaceId, caseId)).toList()

            // Tool still executed — enrichment failure is not fatal
            val toolResponses = events.filterIsInstance<ToolResponseEvent>()
            toolResponses shouldHaveSize 1
            toolResponses[0].success shouldBe true
            (toolResponses[0].output as MessageContent.Text).content shouldBe "executed anyway"

            // No WarnEvent for enrichment failure (it's a graceful fallback, not a user-facing warning)
            events.filterIsInstance<AgentFinishedEvent>() shouldHaveSize 1
        }

        "enrichment: multi-phase tool chains previousContent across phases" {
            val namespaceId = UUID.randomUUID()
            val caseId = UUID.randomUUID()
            val agentId = UUID.randomUUID()

            val receivedPreviousContents = mutableListOf<String?>()

            val multiPhaseTool =
                object : StandardTool<Map<String, Any>> {
                    override val name = "TEST__multiphase"
                    override val description = "Tool with 2 enrichment phases"
                    override val inputSchema = """{"type":"object","properties":{"final":{"type":"string"}}}"""
                    override val version = "1.0.0"
                    override val paramType = null

                    override suspend fun getIntermediatePhaseCount(): Int = 2

                    override suspend fun getIntermediatePhaseDescriptor(
                        phaseIndex: Int,
                        previousContent: String?,
                    ): IntermediatePhaseDescriptor {
                        receivedPreviousContents.add(previousContent)
                        return IntermediatePhaseDescriptor(
                            inputSchema = """{"type":"object","properties":{"p":{"type":"string"}}}""",
                            prompt = "Phase $phaseIndex prompt.",
                        )
                    }

                    override suspend fun enrich(
                        phaseIndex: Int,
                        phaseParametersJson: String,
                        context: ToolContext,
                    ): EnrichmentResult =
                        when (phaseIndex) {
                            0 -> EnrichmentResult(success = true, content = "phase-0-data")
                            1 -> EnrichmentResult(success = true, content = "phase-1-data")
                            else -> EnrichmentResult(success = false, errorMessage = "unexpected phase")
                        }

                    override suspend fun execute(
                        input: Map<String, Any>?,
                        context: ToolContext,
                    ) = ToolExecutionResult.success("multi-phase done")
                }

            val mockChatClient = mockk<ChatClient>(relaxed = true)
            val mockStreamSpec = mockk<ChatClient.StreamResponseSpec>(relaxed = true)
            every { mockChatClient.prompt(any<Prompt>()).stream() } returns mockStreamSpec
            every { mockStreamSpec.content() } returns Flux.just("OK.")
            // LLM calls: 1) phase-0 JSON, 2) phase-1 JSON, 3) final params
            every {
                mockChatClient.prompt(any<Prompt>()).call().content()
            } returnsMany
                listOf(
                    """{"p":"a"}""",
                    """{"p":"b"}""",
                    """{"final":"result"}""",
                )

            val mockGenerator = mockk<AgentIntentionGenerator>()
            every {
                mockGenerator.generate(any(), any(), any(), any(), any())
            } returnsMany
                listOf(
                    IntentionGeneratedEvent(
                        namespaceId = namespaceId,
                        caseId = caseId,
                        agentId = agentId,
                        intention = "Use multiphase tool.",
                        toolName = "TEST__multiphase",
                    ),
                    IntentionGeneratedEvent(
                        namespaceId = namespaceId,
                        caseId = caseId,
                        agentId = agentId,
                        intention = "Done.",
                        toolName = "Answer",
                    ),
                )

            val context =
                AgentAdvancedContext(
                    chatClient = mockChatClient,
                    tools = listOf(multiPhaseTool),
                    instructions = null,
                    agentId = agentId,
                    confirmationManager = mockk(relaxed = true),
                )
            val agent =
                AgentAdvanced(
                    metadata = EntityMetadata(id = agentId),
                    name = "MultiPhaseAgent",
                    context = context,
                    intentionGenerator = mockGenerator,
                    objectMapper = testObjectMapper,
                    maxIterations = 5,
                )

            val events = agent.run(makeInitialEvents(namespaceId, caseId)).toList()

            // Phase 0 receives null previousContent, phase 1 receives phase-0 output
            receivedPreviousContents shouldHaveSize 2
            receivedPreviousContents[0] shouldBe null
            receivedPreviousContents[1] shouldBe "phase-0-data"

            events.filterIsInstance<ToolResponseEvent>().single().success shouldBe true
            events.filterIsInstance<AgentFinishedEvent>() shouldHaveSize 1
        }

        "detectRepetitionLoop returns null when window contains a synthetic ToolResponseEvent" {
            val agent = makeParserAgent()
            val namespaceId = UUID.randomUUID()
            val caseId = UUID.randomUUID()
            val pendingId = UUID.randomUUID()
            val pendingReqId = "synth-req"
            val pending =
                PendingConfirmationEvent(
                    metadata = EntityMetadata(id = pendingId),
                    namespaceId = namespaceId,
                    caseId = caseId,
                    toolRequestId = pendingReqId,
                    toolName = "FILES__remove",
                    inputJson = "{}",
                )
            val resolved =
                ConfirmationResolvedEvent(
                    namespaceId = namespaceId,
                    caseId = caseId,
                    pendingEventId = pendingId,
                    confirmed = true,
                )
            val events =
                listOf(
                    pending,
                    resolved,
                    ToolResponseEvent(
                        namespaceId = namespaceId,
                        caseId = caseId,
                        toolRequestId = "req-A",
                        toolName = "FILES__remove",
                        output = MessageContent.Text("ok"),
                    ),
                    ToolResponseEvent(
                        namespaceId = namespaceId,
                        caseId = caseId,
                        toolRequestId = pendingReqId,
                        toolName = "FILES__remove",
                        output = MessageContent.Text("done"),
                    ),
                    ToolResponseEvent(
                        namespaceId = namespaceId,
                        caseId = caseId,
                        toolRequestId = "req-B",
                        toolName = "FILES__remove",
                        output = MessageContent.Text("ok"),
                    ),
                )

            agent.detectRepetitionLoop(events) shouldBe null
        }
    })
