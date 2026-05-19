package io.whozoss.agentos.agent

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.whozoss.agentos.redirect.RedirectTool
import io.whozoss.agentos.sdk.actor.Actor
import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.caseEvent.*
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import kotlinx.coroutines.flow.toList
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.prompt.Prompt
import reactor.core.publisher.Flux
import java.nio.file.Files
import java.util.*
import kotlin.io.path.exists
import kotlin.io.path.writeText

/**
 * Test-only confirmation-aware tool that mimics the shape of RemoveFileTool without
 * requiring a runtime dependency on agentos-file-plugin. Deletes a file path under
 * [rootDir] when confirmed; rejects with "was not performed." text when refused.
 */
internal class TestRemoveTool(
    private val rootDir: java.nio.file.Path,
    override val name: String = "FILES__remove",
    override val supportsConfirmation: Boolean = true,
    override val bypassImplicitConsent: Boolean = true,
) : StandardTool<TestRemoveTool.Input> {
    data class Input(val path: String? = null)

    data class PendingRemoval(val absolutePath: String, val displayPath: String)

    override val description: String = "Remove a file"
    override val inputSchema: String = """{"type":"object","properties":{"path":{"type":"string"}}}"""
    override val version: String = "1.0.0"
    override val paramType: Class<Input>? = Input::class.java

    override fun execute(
        input: Input?,
        context: ToolContext,
    ): String = "TestRemoveTool requires user confirmation."

    override fun requiresConfirmation(
        input: Input?,
        context: ToolContext,
    ): Boolean = !input?.path.isNullOrBlank()

    override fun getConfirmationPayload(
        input: Input?,
        context: ToolContext,
    ): Any {
        val path = input?.path ?: throw IllegalArgumentException("path required")
        val resolved = rootDir.resolve(path)
        if (Files.isDirectory(resolved)) throw IllegalArgumentException("Cannot remove directories: $path")
        return PendingRemoval(absolutePath = resolved.toString(), displayPath = path)
    }

    override fun confirmationLabel(pendingPayload: Any): String {
        val typed = jacksonObjectMapper().convertValue(pendingPayload, PendingRemoval::class.java)
        return "Delete file ${typed.displayPath}"
    }

    override fun getConfirmationAnalysisInstructions(): String =
        "Be strict: explicit confirmation only after the assistant's question."

    override fun executeWithConfirmation(
        pendingPayload: Any,
        context: ToolContext,
    ): String {
        val typed = jacksonObjectMapper().convertValue(pendingPayload, PendingRemoval::class.java)
        Files.deleteIfExists(java.nio.file.Path.of(typed.absolutePath))
        return "File ${typed.displayPath} deleted successfully"
    }

    override fun onRejected(
        pendingPayload: Any,
        userMessage: String,
        context: ToolContext,
    ): String {
        val typed = jacksonObjectMapper().convertValue(pendingPayload, PendingRemoval::class.java)
        return "Deletion of ${typed.displayPath} was not performed."
    }
}

class AgentAdvancedSpec :
    StringSpec({
        timeout = 5000

        fun makeParserAgent(): AgentAdvanced {
            val mockChatClient = mockk<ChatClient>(relaxed = true)
            val agentId = UUID.randomUUID()
            val context =
                AgentAdvancedContext(
                    chatClient = mockChatClient,
                    tools = emptyList(),
                    instructions = null,
                    agentId = agentId,
                )
            return AgentAdvanced(
                name = "ParserAgent",
                context = context,
                intentionGenerator = mockk(),
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

            val redirectTool = RedirectTool(
                configName = "REDIRECT",
                eligibleAgents = listOf(RedirectTool.EligibleAgent("TargetAgent", "does stuff")),
            )

            val mockChatClient = mockk<ChatClient>(relaxed = true)
            val mockStreamSpec = mockk<ChatClient.StreamResponseSpec>(relaxed = true)
            // call() → parameter generation returns valid JSON for the redirect tool
            every {
                mockChatClient.prompt(any<Prompt>()).call().content()
            } returns """{"agentName":"TargetAgent"}"""
            every { mockChatClient.prompt(any<Prompt>()).stream() } returns mockStreamSpec
            every { mockStreamSpec.content() } returns Flux.empty()

            val mockGenerator = mockk<AgentIntentionGenerator>()
            every {
                mockGenerator.generate(any(), any(), any(), any(), any())
            } returns IntentionGeneratedEvent(
                namespaceId = namespaceId,
                caseId = caseId,
                agentId = agentId,
                intention = "Redirect to TargetAgent.",
                toolName = "REDIRECT__redirect",
            )

            val context = AgentAdvancedContext(
                chatClient = mockChatClient,
                tools = listOf(redirectTool),
                instructions = null,
                agentId = agentId,
            )
            val agent = AgentAdvanced(
                metadata = EntityMetadata(id = agentId),
                name = "TestAgent",
                context = context,
                intentionGenerator = mockGenerator,
                maxIterations = 5,
            )

            val events = agent.run(
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

            val redirectTool = RedirectTool(
                configName = "REDIRECT",
                eligibleAgents = listOf(RedirectTool.EligibleAgent("TargetAgent", "does stuff")),
            )

            val mockChatClient = mockk<ChatClient>(relaxed = true)
            val mockStreamSpec = mockk<ChatClient.StreamResponseSpec>(relaxed = true)
            // call() → parameter generation returns valid JSON for the redirect tool
            every {
                mockChatClient.prompt(any<Prompt>()).call().content()
            } returns """{"agentName":"TargetAgent"}"""
            every { mockChatClient.prompt(any<Prompt>()).stream() } returns mockStreamSpec
            every { mockStreamSpec.content() } returns Flux.empty()

            val mockGenerator = mockk<AgentIntentionGenerator>()
            every {
                mockGenerator.generate(any(), any(), any(), any(), any())
            } returns IntentionGeneratedEvent(
                namespaceId = namespaceId,
                caseId = caseId,
                agentId = agentId,
                intention = "Redirect to TargetAgent.",
                toolName = "REDIRECT__redirect",
            )

            val context = AgentAdvancedContext(
                chatClient = mockChatClient,
                tools = listOf(redirectTool),
                instructions = null,
                agentId = agentId,
            )
            val agent = AgentAdvanced(
                metadata = EntityMetadata(id = agentId),
                name = "TestAgent",
                context = context,
                intentionGenerator = mockGenerator,
                maxIterations = 5,
            )

            val events = agent.run(
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
                )
            val agent =
                AgentAdvanced(
                    metadata = EntityMetadata(id = agentId),
                    name = "TestAgent",
                    context = context,
                    intentionGenerator = mockGenerator,
                    maxIterations = 5,
                )

            val events = agent.run(makeInitialEvents(namespaceId, caseId)).toList()

            // AgentRunningEvent + ThinkingEvent + IntentionGeneratedEvent
            // + TextChunkEvent(x2) + MessageEvent + AgentFinishedEvent
            events shouldHaveSize 7

            val runningEvent = events[0] as? AgentRunningEvent
            runningEvent shouldNotBe null
            runningEvent!!.agentId shouldBe agentId

            val thinkingEvent = events[1] as? ThinkingEvent
            thinkingEvent shouldNotBe null

            val intentionEvent = events[2] as? IntentionGeneratedEvent
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

            val mockTool = mockk<io.whozoss.agentos.sdk.tool.StandardTool<String>>(relaxed = true)
            every { mockTool.name } returns "FILES__ReadFile"
            every { mockTool.description } returns "Read a file"
            every { mockTool.inputSchema } returns "{}"
            every { mockTool.paramType } returns String::class.java
            every { mockTool.executeWithJson(any(), any()) } returns "file content"

            // The generator returns FILES__ReadFile 3 times, then Answer on the 4th call
            val mockGenerator = mockk<AgentIntentionGenerator>()
            every {
                mockGenerator.generate(any(), any(), any(), any(), isNull())
            } returnsMany
                listOf(
                    IntentionGeneratedEvent(
                        namespaceId = namespaceId,
                        caseId = caseId,
                        agentId = UUID.randomUUID(),
                        intention = "Reading the file.",
                        toolName = "FILES__ReadFile",
                    ),
                    IntentionGeneratedEvent(
                        namespaceId = namespaceId,
                        caseId = caseId,
                        agentId = UUID.randomUUID(),
                        intention = "Reading the file again.",
                        toolName = "FILES__ReadFile",
                    ),
                    IntentionGeneratedEvent(
                        namespaceId = namespaceId,
                        caseId = caseId,
                        agentId = UUID.randomUUID(),
                        intention = "Reading the file yet again.",
                        toolName = "FILES__ReadFile",
                    ),
                )
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

            // Also mock call() for generateParameters
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
                )
            val agent =
                AgentAdvanced(
                    metadata = EntityMetadata(id = agentId),
                    name = "LoopAgent",
                    context = context,
                    intentionGenerator = mockGenerator,
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

        "detectRepetitionLoop returns null when fewer than WINDOW tool responses" {
            val agent = makeParserAgent()
            val namespaceId = UUID.randomUUID()
            val caseId = UUID.randomUUID()
            val events =
                listOf(
                    ToolResponseEvent(
                        namespaceId = namespaceId,
                        caseId = caseId,
                        toolRequestId = "req-1",
                        toolName = "FILES__ReadFile",
                        output = MessageContent.Text("content"),
                    ),
                    ToolResponseEvent(
                        namespaceId = namespaceId,
                        caseId = caseId,
                        toolRequestId = "req-2",
                        toolName = "FILES__ReadFile",
                        output = MessageContent.Text("content"),
                    ),
                )

            agent.detectRepetitionLoop(events) shouldBe null
        }

        "detectRepetitionLoop returns tool name when WINDOW consecutive same-tool responses" {
            val agent = makeParserAgent()
            val namespaceId = UUID.randomUUID()
            val caseId = UUID.randomUUID()
            val events =
                (1..AgentAdvanced.REPETITION_DETECTION_WINDOW).map { i ->
                    ToolResponseEvent(
                        namespaceId = namespaceId,
                        caseId = caseId,
                        toolRequestId = "req-$i",
                        toolName = "FILES__ReadFile",
                        output = MessageContent.Text("content"),
                    )
                }

            agent.detectRepetitionLoop(events) shouldBe "FILES__ReadFile"
        }

        "detectRepetitionLoop returns null when WINDOW consecutive responses are all failures" {
            val agent = makeParserAgent()
            val namespaceId = UUID.randomUUID()
            val caseId = UUID.randomUUID()
            val events =
                (1..AgentAdvanced.REPETITION_DETECTION_WINDOW).map { i ->
                    ToolResponseEvent(
                        namespaceId = namespaceId,
                        caseId = caseId,
                        toolRequestId = "req-$i",
                        toolName = "FILES__ReadFile",
                        output = MessageContent.Text("Error: file not found"),
                        success = false,
                    )
                }

            agent.detectRepetitionLoop(events) shouldBe null
        }

        "detectRepetitionLoop returns null when tools are mixed in the window" {
            val agent = makeParserAgent()
            val namespaceId = UUID.randomUUID()
            val caseId = UUID.randomUUID()
            val events =
                listOf(
                    ToolResponseEvent(
                        namespaceId = namespaceId,
                        caseId = caseId,
                        toolRequestId = "req-1",
                        toolName = "FILES__ReadFile",
                        output = MessageContent.Text("content"),
                    ),
                    ToolResponseEvent(
                        namespaceId = namespaceId,
                        caseId = caseId,
                        toolRequestId = "req-2",
                        toolName = "JIRA__GetIssue",
                        output = MessageContent.Text("content"),
                    ),
                    ToolResponseEvent(
                        namespaceId = namespaceId,
                        caseId = caseId,
                        toolRequestId = "req-3",
                        toolName = "FILES__ReadFile",
                        output = MessageContent.Text("content"),
                    ),
                )

            agent.detectRepetitionLoop(events) shouldBe null
        }

        // -------------------------------------------------------------------------
        // WZ-31596 Confirmation Flow — AC1..AC12
        // -------------------------------------------------------------------------

        fun confirmationContext(
            tools: List<StandardTool<*>>,
            agentId: UUID,
            confirmationManager: ConfirmationManager? = mockk(relaxed = true),
            objectMapper: com.fasterxml.jackson.databind.ObjectMapper? = jacksonObjectMapper(),
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
                    objectMapper = objectMapper,
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

        "AC1: destructive tool triggers PendingConfirmation IN-CHANNEL with full event order" {
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
                    maxIterations = 3,
                )
            val events = agent.run(makeInitialEvents(namespaceId, caseId)).toList()

            val toolReqIdx = events.indexOfFirst { it is ToolRequestEvent }
            val pendingIdx = events.indexOfFirst { it is PendingConfirmationEvent }
            val questionIdx = events.indexOfFirst { it is QuestionEvent }
            // The IN-CHANNEL MessageEvent(AGENT) is the *second* MessageEvent (first is the user input).
            val agentMsgIdx = events.indexOfFirst { it is MessageEvent && (it as MessageEvent).actor.role == ActorRole.AGENT }
            val finishedIdx = events.indexOfFirst { it is AgentFinishedEvent }

            (toolReqIdx >= 0) shouldBe true
            (pendingIdx > toolReqIdx) shouldBe true
            (questionIdx > pendingIdx) shouldBe true
            (agentMsgIdx > questionIdx) shouldBe true
            (finishedIdx > agentMsgIdx) shouldBe true
            // No ToolResponseEvent for this tool call at this turn.
            events.filterIsInstance<ToolResponseEvent>() shouldHaveSize 0
            // File still on disk.
            target.exists() shouldBe true
            // Question text propagates to both QuestionEvent and IN-CHANNEL MessageEvent.
            (events[questionIdx] as QuestionEvent).question shouldBe "Voulez-vous supprimer old.txt?"
            (events[questionIdx] as QuestionEvent).options shouldBe CONFIRMATION_OPTIONS
            val agentMsg = events[agentMsgIdx] as MessageEvent
            (agentMsg.content.first() as MessageContent.Text).content shouldBe "Voulez-vous supprimer old.txt?"
        }

        "AC2: explicit confirm via AnswerEvent executes the tool with success=true synthetic response" {
            val tempDir = Files.createTempDirectory("agentadvanced-ac2-").also { it.toFile().deleteOnExit() }
            val target = tempDir.resolve("old.txt").also { it.writeText("data") }
            val namespaceId = UUID.randomUUID()
            val caseId = UUID.randomUUID()
            val agentId = UUID.randomUUID()
            val tool = TestRemoveTool(tempDir)
            val payload = TestRemoveTool.PendingRemoval(target.toString(), "old.txt")
            val pendingId = UUID.randomUUID()
            val questionId = UUID.randomUUID()
            val pending =
                PendingConfirmationEvent(
                    metadata = EntityMetadata(id = pendingId),
                    namespaceId = namespaceId,
                    caseId = caseId,
                    toolRequestId = "orig-req-1",
                    toolName = "FILES__remove",
                    pendingPayloadJson = jacksonObjectMapper().writeValueAsString(payload),
                    confirmationLabel = "Delete file old.txt",
                    questionId = questionId,
                )
            val answer =
                AnswerEvent(
                    namespaceId = namespaceId,
                    caseId = caseId,
                    questionId = questionId,
                    actor = Actor("user1", "User One", ActorRole.USER),
                    answer = CONFIRMATION_ANSWER_CONFIRM,
                )
            val confirmationManager = mockk<ConfirmationManager>(relaxed = true)
            val (ctx, _) = confirmationContext(listOf(tool), agentId, confirmationManager)
            val agent =
                AgentAdvanced(
                    metadata = EntityMetadata(id = agentId),
                    name = "TestAgent",
                    context = ctx,
                    intentionGenerator = mockGeneratorReturning(namespaceId, caseId, agentId, "Answer"),
                    maxIterations = 1,
                )
            val events = agent.run(makeInitialEvents(namespaceId, caseId) + pending + answer).toList()

            val resolved = events.filterIsInstance<ConfirmationResolvedEvent>().single()
            resolved.confirmed shouldBe true
            resolved.pendingEventId shouldBe pendingId
            resolved.resultText shouldContain "deleted successfully"

            val agentMessages =
                events.filterIsInstance<MessageEvent>().filter { it.actor.role == ActorRole.AGENT }
            val resolutionMsg =
                agentMessages.first {
                    (it.content.first() as MessageContent.Text).content.startsWith("User confirmed.")
                }
            (resolutionMsg.content.first() as MessageContent.Text).content shouldContain "deleted successfully"

            val synthetic =
                events.filterIsInstance<ToolResponseEvent>().single { it.toolRequestId == "orig-req-1" }
            synthetic.success shouldBe true
            (synthetic.output as MessageContent.Text).content shouldContain "deleted successfully"

            target.exists() shouldBe false
            // Confirmation path did not call analyzeConfirmation.
            verify(exactly = 0) { confirmationManager.analyzeConfirmation(any(), any(), any(), any()) }
        }

        "AC3: explicit reject via AnswerEvent invokes onRejected with success=false synthetic response" {
            val tempDir = Files.createTempDirectory("agentadvanced-ac3-").also { it.toFile().deleteOnExit() }
            val target = tempDir.resolve("old.txt").also { it.writeText("data") }
            val namespaceId = UUID.randomUUID()
            val caseId = UUID.randomUUID()
            val agentId = UUID.randomUUID()
            val tool = TestRemoveTool(tempDir)
            val payload = TestRemoveTool.PendingRemoval(target.toString(), "old.txt")
            val pendingId = UUID.randomUUID()
            val questionId = UUID.randomUUID()
            val pending =
                PendingConfirmationEvent(
                    metadata = EntityMetadata(id = pendingId),
                    namespaceId = namespaceId,
                    caseId = caseId,
                    toolRequestId = "orig-req-2",
                    toolName = "FILES__remove",
                    pendingPayloadJson = jacksonObjectMapper().writeValueAsString(payload),
                    confirmationLabel = "Delete file old.txt",
                    questionId = questionId,
                )
            val answer =
                AnswerEvent(
                    namespaceId = namespaceId,
                    caseId = caseId,
                    questionId = questionId,
                    actor = Actor("user1", "User One", ActorRole.USER),
                    answer = CONFIRMATION_ANSWER_REJECT,
                )
            val (ctx, _) = confirmationContext(listOf(tool), agentId)
            val agent =
                AgentAdvanced(
                    metadata = EntityMetadata(id = agentId),
                    name = "TestAgent",
                    context = ctx,
                    intentionGenerator = mockGeneratorReturning(namespaceId, caseId, agentId, "Answer"),
                    maxIterations = 1,
                )
            val events = agent.run(makeInitialEvents(namespaceId, caseId) + pending + answer).toList()

            val resolved = events.filterIsInstance<ConfirmationResolvedEvent>().single()
            resolved.confirmed shouldBe false
            resolved.resultText shouldContain "was not performed"

            val resolutionMsg =
                events
                    .filterIsInstance<MessageEvent>()
                    .filter { it.actor.role == ActorRole.AGENT }
                    .first { (it.content.first() as MessageContent.Text).content.startsWith("User declined.") }
            (resolutionMsg.content.first() as MessageContent.Text).content shouldContain "was not performed"

            val synthetic =
                events.filterIsInstance<ToolResponseEvent>().single { it.toolRequestId == "orig-req-2" }
            synthetic.success shouldBe false
            target.exists() shouldBe true
        }

        "AC4: free-form user MessageEvent triggers analyzeConfirmation (yes case)" {
            val tempDir = Files.createTempDirectory("agentadvanced-ac4-").also { it.toFile().deleteOnExit() }
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
                    toolRequestId = "orig-req-4",
                    toolName = "FILES__remove",
                    pendingPayloadJson = jacksonObjectMapper().writeValueAsString(
                        TestRemoveTool.PendingRemoval(target.toString(), "old.txt"),
                    ),
                    confirmationLabel = "Delete file old.txt",
                    questionId = UUID.randomUUID(),
                )
            val userReply =
                MessageEvent(
                    namespaceId = namespaceId,
                    caseId = caseId,
                    actor = Actor("user1", "User One", ActorRole.USER),
                    content = listOf(MessageContent.Text("oui supprime ça")),
                )
            val confirmationManager = mockk<ConfirmationManager>()
            every { confirmationManager.analyzeConfirmation(any(), any(), any(), any()) } returns true
            val (ctx, _) = confirmationContext(listOf(tool), agentId, confirmationManager)
            val agent =
                AgentAdvanced(
                    metadata = EntityMetadata(id = agentId),
                    name = "TestAgent",
                    context = ctx,
                    intentionGenerator = mockGeneratorReturning(namespaceId, caseId, agentId, "Answer"),
                    maxIterations = 1,
                )
            val events = agent.run(makeInitialEvents(namespaceId, caseId) + pending + userReply).toList()

            events.filterIsInstance<ConfirmationResolvedEvent>().single().confirmed shouldBe true
            target.exists() shouldBe false
            verify(exactly = 1) { confirmationManager.analyzeConfirmation(any(), any(), any(), any()) }
        }

        "AC5: ambiguous free-form reply emits WarnEvent and keeps pending alive" {
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
                    pendingPayloadJson = jacksonObjectMapper().writeValueAsString(
                        TestRemoveTool.PendingRemoval(target.toString(), "old.txt"),
                    ),
                    confirmationLabel = "Delete file old.txt",
                    questionId = UUID.randomUUID(),
                )
            val userReply =
                MessageEvent(
                    namespaceId = namespaceId,
                    caseId = caseId,
                    actor = Actor("user1", "User One", ActorRole.USER),
                    content = listOf(MessageContent.Text("hmm peut-être")),
                )
            val confirmationManager = mockk<ConfirmationManager>()
            every { confirmationManager.analyzeConfirmation(any(), any(), any(), any()) } throws
                AmbiguousConfirmationException("ambiguous")
            val (ctx, _) = confirmationContext(listOf(tool), agentId, confirmationManager)
            val agent =
                AgentAdvanced(
                    metadata = EntityMetadata(id = agentId),
                    name = "TestAgent",
                    context = ctx,
                    intentionGenerator = mockGeneratorReturning(namespaceId, caseId, agentId, "Answer"),
                    maxIterations = 1,
                )
            val events = agent.run(makeInitialEvents(namespaceId, caseId) + pending + userReply).toList()

            events.filterIsInstance<ConfirmationResolvedEvent>() shouldHaveSize 0
            events.filterIsInstance<ToolResponseEvent>() shouldHaveSize 0
            val warn = events.filterIsInstance<WarnEvent>().single()
            warn.message shouldContain "Could not interpret your reply"
            events.filterIsInstance<AgentFinishedEvent>() shouldHaveSize 1
            target.exists() shouldBe true
        }

        "AC6bis: non-destructive tool with shouldConfirm=false executes directly without PendingConfirmation" {
            val namespaceId = UUID.randomUUID()
            val caseId = UUID.randomUUID()
            val agentId = UUID.randomUUID()
            // bypassImplicitConsent=false so the orchestrator consults shouldConfirm.
            val tempDir = Files.createTempDirectory("agentadvanced-ac6bis-").also { it.toFile().deleteOnExit() }
            val target = tempDir.resolve("safe.txt").also { it.writeText("data") }
            val tool = TestRemoveTool(tempDir, name = "TEST__safe", bypassImplicitConsent = false)
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
                    maxIterations = 5,
                )
            val events = agent.run(makeInitialEvents(namespaceId, caseId)).toList()

            events.filterIsInstance<PendingConfirmationEvent>() shouldHaveSize 0
            val response = events.filterIsInstance<ToolResponseEvent>().single()
            response.success shouldBe true
            (response.output as MessageContent.Text).content shouldContain "deleted successfully"
            target.exists() shouldBe false
        }

        "AC7: reload session without user reply emits AgentFinished, no ConfirmationResolved, file untouched" {
            val tempDir = Files.createTempDirectory("agentadvanced-ac7-").also { it.toFile().deleteOnExit() }
            val target = tempDir.resolve("old.txt").also { it.writeText("data") }
            val namespaceId = UUID.randomUUID()
            val caseId = UUID.randomUUID()
            val agentId = UUID.randomUUID()
            val tool = TestRemoveTool(tempDir)
            // User MessageEvent is BEFORE the pending (original request). No reply AFTER.
            val initialUserMsg = makeInitialEvents(namespaceId, caseId)
            val pending =
                PendingConfirmationEvent(
                    namespaceId = namespaceId,
                    caseId = caseId,
                    toolRequestId = "orig-req-7",
                    toolName = "FILES__remove",
                    pendingPayloadJson = jacksonObjectMapper().writeValueAsString(
                        TestRemoveTool.PendingRemoval(target.toString(), "old.txt"),
                    ),
                    confirmationLabel = "Delete file old.txt",
                    questionId = UUID.randomUUID(),
                )
            val (ctx, _) = confirmationContext(listOf(tool), agentId)
            val agent =
                AgentAdvanced(
                    metadata = EntityMetadata(id = agentId),
                    name = "TestAgent",
                    context = ctx,
                    intentionGenerator = mockk(),
                    maxIterations = 1,
                )
            val events = agent.run(initialUserMsg + pending).toList()

            events.filterIsInstance<ConfirmationResolvedEvent>() shouldHaveSize 0
            events.filterIsInstance<WarnEvent>() shouldHaveSize 0
            events.filterIsInstance<AgentFinishedEvent>() shouldHaveSize 1
            target.exists() shouldBe true
        }

        "AC9: validation error in getConfirmationPayload surfaces as tool error and skips pending" {
            val tempDir = Files.createTempDirectory("agentadvanced-ac9-").also { it.toFile().deleteOnExit() }
            // Make a sub-directory so getConfirmationPayload throws (Cannot remove directories).
            val subDir = tempDir.resolve("un-dossier").also { Files.createDirectories(it) }
            val namespaceId = UUID.randomUUID()
            val caseId = UUID.randomUUID()
            val agentId = UUID.randomUUID()
            val tool = TestRemoveTool(tempDir)
            val (ctx, chatClient) = confirmationContext(listOf(tool), agentId)
            every { chatClient.prompt(any<Prompt>()).call().content() } returns """{"path":"un-dossier"}"""

            val mockGenerator = mockk<AgentIntentionGenerator>()
            every {
                mockGenerator.generate(any(), any(), any(), any(), any())
            } returnsMany
                listOf(
                    IntentionGeneratedEvent(
                        namespaceId = namespaceId,
                        caseId = caseId,
                        agentId = agentId,
                        intention = "delete un-dossier",
                        toolName = "FILES__remove",
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
                    maxIterations = 5,
                )
            val events = agent.run(makeInitialEvents(namespaceId, caseId)).toList()

            events.filterIsInstance<PendingConfirmationEvent>() shouldHaveSize 0
            val errResponse =
                events.filterIsInstance<ToolResponseEvent>().single { !it.success }
            (errResponse.output as MessageContent.Text).content shouldContain "Cannot remove directories"
            subDir.exists() shouldBe true
        }

        "AC10: missing ConfirmationManager throws IllegalStateException — surfaced as WarnEvent" {
            val tempDir = Files.createTempDirectory("agentadvanced-ac10-").also { it.toFile().deleteOnExit() }
            tempDir.resolve("old.txt").writeText("data")
            val namespaceId = UUID.randomUUID()
            val caseId = UUID.randomUUID()
            val agentId = UUID.randomUUID()
            val tool = TestRemoveTool(tempDir)
            // No confirmationManager / objectMapper.
            val (ctx, chatClient) = confirmationContext(
                listOf(tool),
                agentId,
                confirmationManager = null,
                objectMapper = null,
            )
            every { chatClient.prompt(any<Prompt>()).call().content() } returns """{"path":"old.txt"}"""

            val agent =
                AgentAdvanced(
                    metadata = EntityMetadata(id = agentId),
                    name = "TestAgent",
                    context = ctx,
                    intentionGenerator = mockGeneratorReturning(namespaceId, caseId, agentId, "FILES__remove"),
                    maxIterations = 2,
                )
            val events = agent.run(makeInitialEvents(namespaceId, caseId)).toList()

            // The global catch surfaces the IllegalStateException as a WarnEvent.
            val warn = events.filterIsInstance<WarnEvent>().single()
            warn.message shouldContain "no ConfirmationManager"
            events.filterIsInstance<PendingConfirmationEvent>() shouldHaveSize 0
        }

        "AC11: AnswerEvent with unrecognized value falls through to LLM judge" {
            val tempDir = Files.createTempDirectory("agentadvanced-ac11-").also { it.toFile().deleteOnExit() }
            val target = tempDir.resolve("old.txt").also { it.writeText("data") }
            val namespaceId = UUID.randomUUID()
            val caseId = UUID.randomUUID()
            val agentId = UUID.randomUUID()
            val tool = TestRemoveTool(tempDir)
            val questionId = UUID.randomUUID()
            val pending =
                PendingConfirmationEvent(
                    namespaceId = namespaceId,
                    caseId = caseId,
                    toolRequestId = "orig-req-11",
                    toolName = "FILES__remove",
                    pendingPayloadJson = jacksonObjectMapper().writeValueAsString(
                        TestRemoveTool.PendingRemoval(target.toString(), "old.txt"),
                    ),
                    confirmationLabel = "Delete file old.txt",
                    questionId = questionId,
                )
            val answer =
                AnswerEvent(
                    namespaceId = namespaceId,
                    caseId = caseId,
                    questionId = questionId,
                    actor = Actor("user1", "User One", ActorRole.USER),
                    answer = "yep",
                )
            val confirmationManager = mockk<ConfirmationManager>(relaxed = true)
            every { confirmationManager.analyzeConfirmation(any(), any(), any(), any()) } returns true
            val (ctx, _) = confirmationContext(listOf(tool), agentId, confirmationManager)
            val agent =
                AgentAdvanced(
                    metadata = EntityMetadata(id = agentId),
                    name = "TestAgent",
                    context = ctx,
                    intentionGenerator = mockGeneratorReturning(namespaceId, caseId, agentId, "Answer"),
                    maxIterations = 1,
                )
            val events = agent.run(makeInitialEvents(namespaceId, caseId) + pending + answer).toList()

            verify(exactly = 1) { confirmationManager.analyzeConfirmation(any(), any(), any(), any()) }
            events.filterIsInstance<ConfirmationResolvedEvent>().single().confirmed shouldBe true
            target.exists() shouldBe false
        }

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
                    pendingPayloadJson = """{"a":"b"}""",
                    confirmationLabel = "obsolete",
                    questionId = UUID.randomUUID(),
                )
            // No matching tool in the registry.
            val (ctx, _) = confirmationContext(emptyList(), agentId)
            val agent =
                AgentAdvanced(
                    metadata = EntityMetadata(id = agentId),
                    name = "TestAgent",
                    context = ctx,
                    intentionGenerator = mockGeneratorReturning(namespaceId, caseId, agentId, "Answer"),
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

        "detectRepetitionLoop returns null when window contains a synthetic ToolResponseEvent (v3-F4)" {
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
                    pendingPayloadJson = "{}",
                    confirmationLabel = "delete",
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
                    // Synthetic response paired on the pending's toolRequestId.
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
