package io.whozoss.agentos.agent

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.whozoss.agentos.sdk.tool.ToolExecutionResult
import io.whozoss.agentos.redirect.RedirectTool
import io.whozoss.agentos.sdk.actor.Actor
import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.caseEvent.*
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.sdk.tool.ConfirmationMode
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
    data class Input(val path: String? = null)

    override val description: String = "Remove a file"
    override val inputSchema: String = """{"type":"object","properties":{"path":{"type":"string"}}}"""
    override val version: String = "1.0.0"
    override val paramType: Class<Input>? = Input::class.java

    override suspend fun execute(
        input: Input?,
        context: ToolContext,
    ): ToolExecutionResult {
        val path = input?.path?.takeIf { it.isNotBlank() } ?: return ToolExecutionResult.error("Error: path required")
        val resolved = rootDir.resolve(path)
        if (Files.isDirectory(resolved)) return ToolExecutionResult.error("Error: Cannot remove directories: $path")
        Files.deleteIfExists(resolved)
        return ToolExecutionResult.success("File $path deleted successfully")
    }

    override fun getConfirmationInstructions(): String =
        "Be strict: explicit confirmation only after the assistant's question."

    // The orchestrator invokes executeWithJson (parses JSON then calls execute) for the
    // post-confirmation path. onRejected: default returns "Action cancelled.".
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
                    confirmationManager = mockk(relaxed = true),
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
            coEvery { mockTool.executeWithJson(any(), any()) } returns ToolExecutionResult.success("file content")

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
                    maxIterations = 3,
                )
            val events = agent.run(makeInitialEvents(namespaceId, caseId)).toList()

            val toolReqIdx = events.indexOfFirst { it is ToolRequestEvent }
            val pendingIdx = events.indexOfFirst { it is PendingConfirmationEvent }
            val agentMsgIdx = events.indexOfFirst { it is MessageEvent && (it as MessageEvent).actor.role == ActorRole.AGENT }
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
            val tool = object : StandardTool<Map<String, Any>> {
                override val name = "TEST__failing"
                override val description = "throws on execute"
                override val inputSchema = "{}"
                override val version = "1.0.0"
                override val paramType = null
                override val confirmationMode = ConfirmationMode.INFER
                override suspend fun execute(input: Map<String, Any>?, context: ToolContext): ToolExecutionResult =
                    throw RuntimeException("boom")
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
            val tool = object : StandardTool<Map<String, Any>> {
                override val name = "FAILING__remove"
                override val description = "always throws on executeWithJson"
                override val inputSchema = "{}"
                override val version = "1.0.0"
                override val paramType = null
                override val confirmationMode = ConfirmationMode.EVERY_TIME
                override suspend fun execute(input: Map<String, Any>?, context: ToolContext): ToolExecutionResult =
                    ToolExecutionResult.success("ok")
                override suspend fun executeWithJson(json: String?, context: ToolContext): ToolExecutionResult {
                    throw RuntimeException("disk full")
                }
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
                    maxIterations = 1,
                )

            val events = agent.run(makeInitialEvents(namespaceId, caseId)).toList()

            val warn = events.filterIsInstance<WarnEvent>().single()
            warn.message shouldContain "Unknown tool referenced"
            warn.message shouldContain "DOES_NOT_EXIST"
            events.filterIsInstance<ToolResponseEvent>() shouldHaveSize 0
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
