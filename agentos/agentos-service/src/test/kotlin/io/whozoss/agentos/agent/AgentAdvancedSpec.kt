package io.whozoss.agentos.agent

import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.mockk.*
import io.whozoss.agentos.redirect.RedirectTool
import io.whozoss.agentos.sdk.actor.Actor
import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.caseEvent.*
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.sdk.tool.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.metadata.ChatGenerationMetadata
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.retry.NonTransientAiException
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
    private val forcedMode: ConfirmationMode = ConfirmationMode.EVERY_TIME,
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

    override suspend fun getConfirmationMode(argsJson: String?, context: ToolContext?) = forcedMode

    override fun getConfirmationInstructions(): String = "Be strict: explicit confirmation only after the assistant's question."

    // The orchestrator invokes executeWithJson (parses JSON then calls execute) for the
    // post-confirmation path. onRejected: default returns "Action cancelled.".
}

/**
 * Creates a Flux<ChatResponse> from text chunks, suitable for mocking .chatResponse().
 * The last chunk carries finishReason="stop"; earlier chunks have null finishReason.
 */
fun chatResponseFlux(vararg chunks: String): Flux<ChatResponse> =
    Flux.fromIterable(
        chunks.mapIndexed { index, text ->
            val isLast = index == chunks.size - 1
            val metadata =
                if (isLast) {
                    ChatGenerationMetadata.builder().finishReason("stop").build()
                } else {
                    ChatGenerationMetadata.NULL
                }
            ChatResponse(listOf(Generation(AssistantMessage(text), metadata)))
        },
    )

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
                llmProvider = "test-provider",
                llmModel = "test-model",
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
                    llmProvider = "test-provider",
                    llmModel = "test-model",
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
                    llmProvider = "test-provider",
                    llmModel = "test-model",
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
                    llmProvider = "test-provider",
                    llmModel = "test-model",
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
            every { mockStreamSpec.chatResponse() } returns chatResponseFlux("Here is ", "my answer.")
            // detectUserLanguage makes a synchronous .call().content() — must be explicitly
            // mocked to avoid relying on relaxed-mock behaviour that can hang on some JDKs.
            every { mockChatClient.prompt(any<Prompt>()).call().content() } returns null

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
                    llmProvider = "test-provider",
                    llmModel = "test-model",
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
            // Scenario: the agent calls FILES__ReadFile THRESHOLD times within the WINDOW,
            // triggering a Warned. The generator self-corrects by switching to Answer on the
            // next iteration (when the repetitionWarning hint is passed).
            //
            // With WINDOW=5 and THRESHOLD=3: to get exactly count==THRESHOLD in the window
            // we need the other (WINDOW-THRESHOLD) slots filled with a different tool.
            // Sequence: OTHER, OTHER, READ, READ, READ → count(READ)=3==THRESHOLD → Warned.
            // On the next iteration the generator receives not(isNull()) and returns Answer.
            val namespaceId = UUID.randomUUID()
            val caseId = UUID.randomUUID()

            val mockChatClient = mockk<ChatClient>(relaxed = true)
            val mockStreamSpec = mockk<ChatClient.StreamResponseSpec>(relaxed = true)
            every { mockChatClient.prompt(any<Prompt>()).stream() } returns mockStreamSpec
            every { mockStreamSpec.chatResponse() } returns chatResponseFlux("Loop stopped.")

            val readTool = mockk<StandardTool<String>>(relaxed = true)
            every { readTool.name } returns "FILES__ReadFile"
            every { readTool.description } returns "Read a file"
            every { readTool.inputSchema } returns "{}"
            every { readTool.paramType } returns String::class.java
            coEvery { readTool.executeWithJson(any(), any()) } returns ToolExecutionResult.success("file content")

            val otherTool = mockk<StandardTool<String>>(relaxed = true)
            every { otherTool.name } returns "FILES__List"
            every { otherTool.description } returns "List files"
            every { otherTool.inputSchema } returns "{}"
            every { otherTool.paramType } returns String::class.java
            coEvery { otherTool.executeWithJson(any(), any()) } returns ToolExecutionResult.success("listing")

            // Build sequence: (WINDOW-THRESHOLD) other-tool calls, then THRESHOLD read calls.
            // This fills the window with exactly THRESHOLD identical entries → Warned (not ForceStop).
            val otherCount = AgentAdvanced.REPETITION_WINDOW - AgentAdvanced.REPETITION_THRESHOLD
            val mockGenerator = mockk<AgentIntentionGenerator>()
            every {
                mockGenerator.generate(any(), any(), any(), any(), isNull())
            } returnsMany
                (1..otherCount).map {
                    IntentionGeneratedEvent(
                        namespaceId = namespaceId,
                        caseId = caseId,
                        agentId = UUID.randomUUID(),
                        intention = "List files (iteration $it).",
                        toolName = "FILES__List",
                    )
                } +
                (1..AgentAdvanced.REPETITION_THRESHOLD).map {
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
                    tools = listOf(readTool, otherTool),
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
                    llmProvider = "test-provider",
                    llmModel = "test-model",
                )

            val events = agent.run(makeInitialEvents(namespaceId, caseId)).toList()

            // Exactly one WarnEvent for the Warned step (count==THRESHOLD, not ForceStop)
            val warnEvents = events.filterIsInstance<WarnEvent>()
            warnEvents shouldHaveSize 1
            warnEvents[0].message shouldContain "FILES__ReadFile"

            // Agent self-corrected: last intention is Answer
            val intentionEvents = events.filterIsInstance<IntentionGeneratedEvent>()
            intentionEvents.last().toolName shouldBe "Answer"

            events.filterIsInstance<AgentFinishedEvent>() shouldHaveSize 1
        }

        // -------------------------------------------------------------------------
        // detectRepetitionLoop unit tests
        // -------------------------------------------------------------------------

        // -------------------------------------------------------------------------
        // detectUserLanguage unit tests
        // -------------------------------------------------------------------------

        "detectUserLanguage returns null when no user messages" {
            val agent = makeParserAgent()
            val namespaceId = UUID.randomUUID()
            val caseId = UUID.randomUUID()
            val events =
                listOf(
                    MessageEvent(
                        namespaceId = namespaceId,
                        caseId = caseId,
                        actor = Actor("agent1", "Astra", ActorRole.AGENT),
                        content = listOf(MessageContent.Text("Bonjour, comment puis-je vous aider ?")),
                    ),
                )
            agent.detectUserLanguage(events) shouldBe null
        }

        "detectUserLanguage calls LLM with user messages only and extracts language from tags" {
            val mockChatClient = mockk<ChatClient>(relaxed = true)
            every {
                mockChatClient.prompt(any<Prompt>()).call().content()
            } returns "<language>French</language>"
            val agentId = UUID.randomUUID()
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
                    name = "TestAgent",
                    context = context,
                    intentionGenerator = mockk(),
                    objectMapper = testObjectMapper,
                    llmProvider = "test-provider",
                    llmModel = "test-model",
                )
            val namespaceId = UUID.randomUUID()
            val caseId = UUID.randomUUID()
            val events =
                listOf(
                    MessageEvent(
                        namespaceId = namespaceId,
                        caseId = caseId,
                        actor = Actor("user1", "User", ActorRole.USER),
                        content = listOf(MessageContent.Text("Cherche-moi des développeurs Angular")),
                    ),
                    MessageEvent(
                        namespaceId = namespaceId,
                        caseId = caseId,
                        actor = Actor("agent1", "Astra", ActorRole.AGENT),
                        content = listOf(MessageContent.Text("Consultor Senior de Ciberseguridad")),
                    ),
                )
            val result = agent.detectUserLanguage(events)
            result shouldBe "French"
        }

        "detectUserLanguage returns null when LLM response has no language tags" {
            val mockChatClient = mockk<ChatClient>(relaxed = true)
            every {
                mockChatClient.prompt(any<Prompt>()).call().content()
            } returns "I cannot determine the language."
            val agentId = UUID.randomUUID()
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
                    name = "TestAgent",
                    context = context,
                    intentionGenerator = mockk(),
                    objectMapper = testObjectMapper,
                    llmProvider = "test-provider",
                    llmModel = "test-model",
                )
            val namespaceId = UUID.randomUUID()
            val caseId = UUID.randomUUID()
            val events =
                listOf(
                    MessageEvent(
                        namespaceId = namespaceId,
                        caseId = caseId,
                        actor = Actor("user1", "User", ActorRole.USER),
                        content = listOf(MessageContent.Text("hello")),
                    ),
                )
            agent.detectUserLanguage(events) shouldBe null
        }

        "detectUserLanguage returns null when LLM call throws" {
            val mockChatClient = mockk<ChatClient>(relaxed = true)
            every {
                mockChatClient.prompt(any<Prompt>()).call().content()
            } throws RuntimeException("provider error")
            val agentId = UUID.randomUUID()
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
                    name = "TestAgent",
                    context = context,
                    intentionGenerator = mockk(),
                    objectMapper = testObjectMapper,
                    llmProvider = "test-provider",
                    llmModel = "test-model",
                )
            val namespaceId = UUID.randomUUID()
            val caseId = UUID.randomUUID()
            val events =
                listOf(
                    MessageEvent(
                        namespaceId = namespaceId,
                        caseId = caseId,
                        actor = Actor("user1", "User", ActorRole.USER),
                        content = listOf(MessageContent.Text("hello")),
                    ),
                )
            agent.detectUserLanguage(events) shouldBe null
        }

        "detectUserLanguage collects newest messages first up to targetChars" {
            val promptSlot = slot<Prompt>()
            val mockChatClient = mockk<ChatClient>(relaxed = true)
            every {
                mockChatClient.prompt(capture(promptSlot)).call().content()
            } returns "<language>English</language>"
            val agentId = UUID.randomUUID()
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
                    name = "TestAgent",
                    context = context,
                    intentionGenerator = mockk(),
                    objectMapper = testObjectMapper,
                    llmProvider = "test-provider",
                    llmModel = "test-model",
                )
            val namespaceId = UUID.randomUUID()
            val caseId = UUID.randomUUID()
            val events =
                listOf(
                    MessageEvent(
                        namespaceId = namespaceId,
                        caseId = caseId,
                        actor = Actor("user1", "User", ActorRole.USER),
                        content = listOf(MessageContent.Text("old message from the start")),
                    ),
                    MessageEvent(
                        namespaceId = namespaceId,
                        caseId = caseId,
                        actor = Actor("user1", "User", ActorRole.USER),
                        content = listOf(MessageContent.Text("Can you look for Angular developers in Paris with 5 years of experience")),
                    ),
                )
            // targetChars=60: the newest message (~70 chars) exceeds the threshold alone,
            // so only it should be sampled — the old message must not appear in the prompt.
            agent.detectUserLanguage(events, targetChars = 60)
            val promptText =
                promptSlot.captured.instructions
                    .first()
                    .text
            promptText shouldContain "Angular developers"
            // The old message must NOT appear: threshold was reached after the newest message alone
            (promptText.contains("old message from the start")) shouldBe false
        }

        // -------------------------------------------------------------------------
        // buildUserFacingGuidelines unit tests
        // -------------------------------------------------------------------------

        "buildUserFacingGuidelines includes hard language constraint when LLM detects a language" {
            val mockChatClient = mockk<ChatClient>(relaxed = true)
            every { mockChatClient.prompt(any<Prompt>()).call().content() } returns "<language>English</language>"
            val agentId = UUID.randomUUID()
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
                    name = "TestAgent",
                    context = context,
                    intentionGenerator = mockk(),
                    objectMapper = testObjectMapper,
                    llmProvider = "test-provider",
                    llmModel = "test-model",
                )
            val namespaceId = UUID.randomUUID()
            val caseId = UUID.randomUUID()
            val events =
                listOf(
                    MessageEvent(
                        namespaceId = namespaceId,
                        caseId = caseId,
                        actor = Actor("user1", "User", ActorRole.USER),
                        content = listOf(MessageContent.Text("Hello, can you help me?")),
                    ),
                )
            val guidelines = agent.buildUserFacingGuidelines(events)
            guidelines shouldNotBe null
            guidelines!! shouldContain "IMPORTANT"
            guidelines shouldContain "English"
            guidelines shouldContain "hard constraint"
        }

        "buildUserFacingGuidelines omits language constraint when no user messages" {
            val agent = makeParserAgent()
            val namespaceId = UUID.randomUUID()
            val caseId = UUID.randomUUID()
            // Only an agent message — detectUserLanguage returns null
            val events =
                listOf(
                    MessageEvent(
                        namespaceId = namespaceId,
                        caseId = caseId,
                        actor = Actor("agent1", "Astra", ActorRole.AGENT),
                        content = listOf(MessageContent.Text("Bonjour, comment puis-je vous aider ?")),
                    ),
                )
            val guidelines = agent.buildUserFacingGuidelines(events)
            guidelines shouldNotBe null
            // Static rules still present
            guidelines!! shouldContain "discriminate"
            guidelines shouldContain "technical IDs"
            // No language instruction
            guidelines shouldNotBe ""
        }

        // -------------------------------------------------------------------------
        // buildLanguageHint unit tests (kept for backward compatibility)
        // -------------------------------------------------------------------------

        "buildLanguageHint returns null when no user messages" {
            val agent = makeParserAgent()
            val namespaceId = UUID.randomUUID()
            val caseId = UUID.randomUUID()
            val events =
                listOf(
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
            val events =
                listOf(
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
            val messages =
                (1..6).map { i ->
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
            val events =
                listOf(
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
            val events =
                listOf(
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

        "detectToolRepetition returns null when fewer than REPETITION_WINDOW tool responses" {
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

            agent.detectToolRepetition(events) shouldBe null
        }

        "detectToolRepetition returns ToolRepetition when THRESHOLD identical calls within WINDOW" {
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

            val result = agent.detectToolRepetition(events)
            result shouldNotBe null
            result!!.toolName shouldBe "FILES__ReadFile"
            result.count shouldBe AgentAdvanced.REPETITION_WINDOW
        }

        "detectToolRepetition returns ToolRepetition on two-tool alternation loop (A,B,A,B,A)" {
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

            val result = agent.detectToolRepetition(events)
            result shouldNotBe null
            result!!.toolName shouldBe "toolA"
            result.count shouldBe 3
        }

        "detectToolRepetition returns null when WINDOW consecutive same-tool responses have different args (WZ-32262)" {
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

            agent.detectToolRepetition(events) shouldBe null
        }

        "detectToolRepetition returns ToolRepetition when THRESHOLD failures with same args within WINDOW" {
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

            val result = agent.detectToolRepetition(events)
            result shouldNotBe null
            result!!.toolName shouldBe "FILES__ReadFile"
            (result.count >= AgentAdvanced.REPETITION_THRESHOLD) shouldBe true
        }

        "detectToolRepetition returns null when no (toolName, args) pair reaches THRESHOLD in the window" {
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

            agent.detectToolRepetition(events) shouldBe null
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
            every { mockStreamSpec.chatResponse() } returns Flux.empty()
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
            every { confirmationManager.formulateQuestion(any(), any(), any(), any(), any(), any()) } returns
                "Voulez-vous supprimer old.txt?"
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
                    llmProvider = "test-provider",
                    llmModel = "test-model",
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
                    llmProvider = "test-provider",
                    llmModel = "test-model",
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
                    llmProvider = "test-provider",
                    llmModel = "test-model",
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
            every { confirmationManager.formulateQuestion(any(), any(), any(), any(), any(), any()) } returns clarificationText
            val (ctx, _) = confirmationContext(listOf(tool), agentId, confirmationManager)
            val agent =
                AgentAdvanced(
                    metadata = EntityMetadata(id = agentId),
                    name = "TestAgent",
                    context = ctx,
                    intentionGenerator = mockGeneratorReturning(namespaceId, caseId, agentId, "Answer"),
                    objectMapper = testObjectMapper,
                    maxIterations = 1,
                    llmProvider = "test-provider",
                    llmModel = "test-model",
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
            val tool = TestRemoveTool(tempDir, name = "TEST__safe", forcedMode = ConfirmationMode.INFER)
            val confirmationManager = mockk<ConfirmationManager>()
            every { confirmationManager.shouldConfirm(any(), any(), any(), any(), any(), any()) } returns false
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
                    llmProvider = "test-provider",
                    llmModel = "test-model",
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

                    override suspend fun getConfirmationMode(argsJson: String?, context: ToolContext?) = ConfirmationMode.INFER

                    override suspend fun execute(
                        input: Map<String, Any>?,
                        context: ToolContext,
                    ): ToolExecutionResult = throw RuntimeException("boom")
                }
            val confirmationManager = mockk<ConfirmationManager>()
            every { confirmationManager.shouldConfirm(any(), any(), any(), any(), any(), any()) } returns false
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
                    llmProvider = "test-provider",
                    llmModel = "test-model",
                )
            val events = agent.run(makeInitialEvents(namespaceId, caseId)).toList()

            events.filterIsInstance<PendingConfirmationEvent>() shouldHaveSize 0
            val response =
                events.filterIsInstance<ToolResponseEvent>().single { it.toolName == "TEST__failing" }
            response.success shouldBe false
            (response.output as MessageContent.Text).content shouldContain "boom"
        }

        // Dynamic: a tool can resolve its mode at runtime via getConfirmationMode(args, ctx).
        // When it returns NONE, the orchestrator skips the confirmation gate and executes
        // the tool directly, even if the static val requires confirmation.
        // Core use case: programmatic bypass of UpdateProfile when CreateProfile was
        // called in-session for the same profileId.
        "Dynamic getConfirmationMode=NONE bypasses confirmation entirely" {
            val namespaceId = UUID.randomUUID()
            val caseId = UUID.randomUUID()
            val agentId = UUID.randomUUID()
            val executed =
                java.util.concurrent.atomic
                    .AtomicBoolean(false)
            val tool =
                object : StandardTool<Map<String, Any>> {
                    override val name = "TEST__bypassable"
                    override val description = "tool with dynamic NONE override"
                    override val inputSchema = "{}"
                    override val version = "1.0.0"
                    override val paramType = null

                    // Always returns NONE dynamically — mimicking a bypass condition
                    override suspend fun getConfirmationMode(
                        argsJson: String?,
                        context: ToolContext?,
                    ): ConfirmationMode = ConfirmationMode.NONE

                    override suspend fun execute(
                        input: Map<String, Any>?,
                        context: ToolContext,
                    ): ToolExecutionResult {
                        executed.set(true)
                        return ToolExecutionResult.success("done")
                    }
                }
            val confirmationManager = mockk<ConfirmationManager>(relaxed = true)
            val (ctx, chatClient) = confirmationContext(listOf(tool), agentId, confirmationManager)
            every { chatClient.prompt(any<Prompt>()).call().content() } returns "{}"

            val mockGenerator = mockk<AgentIntentionGenerator>()
            every { mockGenerator.generate(any(), any(), any(), any(), any()) } returnsMany
                listOf(
                    IntentionGeneratedEvent(
                        namespaceId = namespaceId,
                        caseId = caseId,
                        agentId = agentId,
                        intention = "call bypassable",
                        toolName = "TEST__bypassable",
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
                    llmProvider = "test-provider",
                    llmModel = "test-model",
                )
            val events = agent.run(makeInitialEvents(namespaceId, caseId)).toList()

            // No PendingConfirmation: the dynamic override trumps the static EVERY_TIME val
            events.filterIsInstance<PendingConfirmationEvent>() shouldHaveSize 0
            // shouldConfirm is never called (mode = NONE → no gate)
            verify(exactly = 0) {
                confirmationManager.shouldConfirm(any(), any(), any(), any(), any(), any())
            }
            executed.get() shouldBe true
        }

        // The orchestrator must forward tool-specific instructions to the LLM judge
        // so the prompt template includes the custom guidance (e.g. "pourquoi pas not consent").
        "INFER mode forwards tool.getConfirmationInstructions() to shouldConfirm.toolInstructions" {
            val namespaceId = UUID.randomUUID()
            val caseId = UUID.randomUUID()
            val agentId = UUID.randomUUID()
            val tool =
                object : StandardTool<Map<String, Any>> {
                    override val name = "TEST__withGuidance"
                    override val description = "tool that injects guidance"
                    override val inputSchema = "{}"
                    override val version = "1.0.0"
                    override val paramType = null

                    override suspend fun getConfirmationMode(argsJson: String?, context: ToolContext?) = ConfirmationMode.INFER

                    override fun getConfirmationInstructions(): String = "Be strict: 'pourquoi pas' is not consent."

                    override suspend fun execute(
                        input: Map<String, Any>?,
                        context: ToolContext,
                    ): ToolExecutionResult = ToolExecutionResult.success("ok")
                }
            val confirmationManager = mockk<ConfirmationManager>()
            val toolInstructionsCaptured = slot<String>()
            every {
                confirmationManager.shouldConfirm(
                    chatClient = any(),
                    firstLevelHistory = any(),
                    actionLabel = any(),
                    proposedData = any(),
                    originalData = any(),
                    toolInstructions = capture(toolInstructionsCaptured),
                )
            } returns false
            val (ctx, chatClient) = confirmationContext(listOf(tool), agentId, confirmationManager)
            every { chatClient.prompt(any<Prompt>()).call().content() } returns "{}"

            val mockGenerator = mockk<AgentIntentionGenerator>()
            every { mockGenerator.generate(any(), any(), any(), any(), any()) } returnsMany
                listOf(
                    IntentionGeneratedEvent(
                        namespaceId = namespaceId,
                        caseId = caseId,
                        agentId = agentId,
                        intention = "call with guidance",
                        toolName = "TEST__withGuidance",
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
                    llmProvider = "test-provider",
                    llmModel = "test-model",
                )
            agent.run(makeInitialEvents(namespaceId, caseId)).toList()

            toolInstructionsCaptured.captured shouldBe "Be strict: 'pourquoi pas' is not consent."
        }

        // Wiring: the orchestrator must forward the raw LLM-generated args AND the
        // accumulated (non-empty) caseEvents to the dynamic hook. Without this, a plugin
        // cannot perform a programmatic bypass (core use case of this PR).
        "getConfirmationMode receives the LLM-generated args and the accumulated caseEvents" {
            val namespaceId = UUID.randomUUID()
            val caseId = UUID.randomUUID()
            val agentId = UUID.randomUUID()
            val argsCaptured =
                java.util.concurrent.atomic
                    .AtomicReference<String?>(null)
            val eventsCaptured =
                java.util.concurrent.atomic
                    .AtomicReference<List<CaseEvent>?>(null)
            val tool =
                object : StandardTool<Map<String, Any>> {
                    override val name = "TEST__capturingTool"
                    override val description = "captures args/ctx seen at getConfirmationMode"
                    override val inputSchema = """{"type":"object","properties":{"id":{"type":"string"}}}"""
                    override val version = "1.0.0"
                    override val paramType = null

                    override suspend fun getConfirmationMode(
                        argsJson: String?,
                        context: ToolContext?,
                    ): ConfirmationMode {
                        argsCaptured.set(argsJson)
                        eventsCaptured.set(context?.caseEvents)
                        // Returns EVERY_TIME so the orchestrator still routes through the gate
                        // — we want the seam exercised even when the result is "confirm".
                        return ConfirmationMode.EVERY_TIME
                    }

                    override suspend fun execute(
                        input: Map<String, Any>?,
                        context: ToolContext,
                    ): ToolExecutionResult = ToolExecutionResult.success("captured")
                }
            val confirmationManager = mockk<ConfirmationManager>()
            every {
                confirmationManager.formulateQuestion(any(), any(), any(), any(), any(), any())
            } returns "confirm?"
            val (ctx, chatClient) = confirmationContext(listOf(tool), agentId, confirmationManager)
            // The args returned to the orchestrator by the LLM
            val expectedArgs = """{"id":"abc-123"}"""
            every { chatClient.prompt(any<Prompt>()).call().content() } returns expectedArgs

            val mockGenerator = mockk<AgentIntentionGenerator>()
            every { mockGenerator.generate(any(), any(), any(), any(), any()) } returns
                IntentionGeneratedEvent(
                    namespaceId = namespaceId,
                    caseId = caseId,
                    agentId = agentId,
                    intention = "call capturing tool",
                    toolName = "TEST__capturingTool",
                )

            // Wire caseEventsProvider explicitly so the hook sees the same events the
            // orchestrator emits during this run — mimicking CaseRuntime in production.
            val initialEvents = makeInitialEvents(namespaceId, caseId)
            val agent =
                AgentAdvanced(
                    metadata = EntityMetadata(id = agentId),
                    name = "TestAgent",
                    context = ctx,
                    intentionGenerator = mockGenerator,
                    objectMapper = testObjectMapper,
                    maxIterations = 2,
                    caseEventsProvider = { initialEvents },
                    llmProvider = "test-provider",
                    llmModel = "test-model",
                )
            agent.run(initialEvents).toList()

            // args : verbatim from the LLM response — same string the orchestrator
            // persists on the resulting ToolRequestEvent.
            argsCaptured.get() shouldBe expectedArgs

            // caseEvents : non-null, includes at least the initial USER MessageEvent
            // from `caseEventsProvider`. Proves the hook is wired to the live event
            // history exposed by the orchestrator, not to an empty/default snapshot.
            val seenEvents = eventsCaptured.get()
            seenEvents shouldNotBe null
            seenEvents!!.filterIsInstance<MessageEvent>().any { it.actor.role == ActorRole.USER } shouldBe true
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
                    llmProvider = "test-provider",
                    llmModel = "test-model",
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

                    override suspend fun getConfirmationMode(argsJson: String?, context: ToolContext?) = ConfirmationMode.EVERY_TIME

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
                    llmProvider = "test-provider",
                    llmModel = "test-model",
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
                    llmProvider = "test-provider",
                    llmModel = "test-model",
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
                    llmProvider = "test-provider",
                    llmModel = "test-model",
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
            coEvery { mockTool.getConfirmationMode(any(), any()) } returns ConfirmationMode.NONE
            coEvery { mockTool.executeWithJson(any(), any()) } returns ToolExecutionResult.success("done")

            val mockChatClient = mockk<ChatClient>(relaxed = true)
            val mockStreamSpec = mockk<ChatClient.StreamResponseSpec>(relaxed = true)
            every { mockChatClient.prompt(any<Prompt>()).stream() } returns mockStreamSpec
            every { mockStreamSpec.chatResponse() } returns chatResponseFlux("ok")

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
                    llmProvider = "test-provider",
                    llmModel = "test-model",
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
            coEvery { mockTool.getConfirmationMode(any(), any()) } returns ConfirmationMode.NONE
            coEvery { mockTool.executeWithJson(any(), any()) } returns ToolExecutionResult.success("done")

            val mockChatClient = mockk<ChatClient>(relaxed = true)
            val mockStreamSpec = mockk<ChatClient.StreamResponseSpec>(relaxed = true)
            every { mockChatClient.prompt(any<Prompt>()).stream() } returns mockStreamSpec
            every { mockStreamSpec.chatResponse() } returns chatResponseFlux("ok")

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
                    llmProvider = "test-provider",
                    llmModel = "test-model",
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
            every { mockTool.inputSchema } returns
                """{"type":"object","properties":{"entitiesId":{"type":"array","items":{"type":"string"}}}}"""
            every { mockTool.paramType } returns String::class.java
            coEvery { mockTool.executeWithJson(any(), any()) } returns ToolExecutionResult.success("entity data")

            val mockChatClient = mockk<ChatClient>(relaxed = true)
            val mockStreamSpec = mockk<ChatClient.StreamResponseSpec>(relaxed = true)
            every { mockChatClient.prompt(any<Prompt>()).stream() } returns mockStreamSpec
            every { mockStreamSpec.chatResponse() } returns chatResponseFlux("done")

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
                    llmProvider = "test-provider",
                    llmModel = "test-model",
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
            coEvery { mockTool.getConfirmationMode(any(), any()) } returns ConfirmationMode.NONE
            coEvery { mockTool.executeWithJson(any(), any()) } returns ToolExecutionResult.success("done")

            val mockChatClient = mockk<ChatClient>(relaxed = true)
            val mockStreamSpec = mockk<ChatClient.StreamResponseSpec>(relaxed = true)
            every { mockChatClient.prompt(any<Prompt>()).stream() } returns mockStreamSpec
            every { mockStreamSpec.chatResponse() } returns chatResponseFlux("ok")

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
                    llmProvider = "test-provider",
                    llmModel = "test-model",
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
                    llmProvider = "test-provider",
                    llmModel = "test-model",
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
            every { mockStreamSpec.chatResponse() } returns chatResponseFlux("OK.")

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
                    llmProvider = "test-provider",
                    llmModel = "test-model",
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

            // enrichmentPhases must be populated with the single phase trace
            val phases = toolRequests[0].enrichmentPhases
            phases shouldNotBe null
            phases!! shouldHaveSize 1
            phases[0].phaseIndex shouldBe 0
            phases[0].prompt shouldBe "Identify the entity."
            phases[0].llmOutput shouldContain "123"
            phases[0].enrichmentContent shouldBe "Entity details: name=Foo, status=active"
            phases[0].success shouldBe true

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
            every { mockStreamSpec.chatResponse() } returns chatResponseFlux("Done.")
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
                    llmProvider = "test-provider",
                    llmModel = "test-model",
                )

            val events = agent.run(makeInitialEvents(namespaceId, caseId)).toList()

            // Tool executed
            val toolResponses = events.filterIsInstance<ToolResponseEvent>()
            toolResponses shouldHaveSize 1
            toolResponses[0].success shouldBe true

            // getIntermediatePhaseDescriptor should never be called for a 0-phase tool
            coVerify(exactly = 0) { simpleTool.getIntermediatePhaseDescriptor(any(), any()) }

            // enrichmentPhases must be null for tools with no enrichment phases
            val toolRequests = events.filterIsInstance<ToolRequestEvent>()
            toolRequests shouldHaveSize 1
            toolRequests[0].enrichmentPhases shouldBe null

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
            every { mockStreamSpec.chatResponse() } returns chatResponseFlux("OK.")
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
                    llmProvider = "test-provider",
                    llmModel = "test-model",
                )

            val events = agent.run(makeInitialEvents(namespaceId, caseId)).toList()

            // Tool still executed — enrichment failure is not fatal
            val toolResponses = events.filterIsInstance<ToolResponseEvent>()
            toolResponses shouldHaveSize 1
            toolResponses[0].success shouldBe true
            (toolResponses[0].output as MessageContent.Text).content shouldBe "executed anyway"

            // No WarnEvent for enrichment failure (it's a graceful fallback, not a user-facing warning)

            // enrichmentPhases must contain the failed phase trace even on failure
            val toolRequests = events.filterIsInstance<ToolRequestEvent>()
            toolRequests shouldHaveSize 1
            val phases = toolRequests[0].enrichmentPhases
            phases shouldNotBe null
            phases!! shouldHaveSize 1
            phases[0].phaseIndex shouldBe 0
            phases[0].llmOutput shouldContain "456"
            phases[0].enrichmentContent shouldBe null
            phases[0].success shouldBe false

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
            every { mockStreamSpec.chatResponse() } returns chatResponseFlux("OK.")
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
                    llmProvider = "test-provider",
                    llmModel = "test-model",
                )

            val events = agent.run(makeInitialEvents(namespaceId, caseId)).toList()

            // Phase 0 receives null previousContent, phase 1 receives phase-0 output
            receivedPreviousContents shouldHaveSize 2
            receivedPreviousContents[0] shouldBe null
            receivedPreviousContents[1] shouldBe "phase-0-data"

            // enrichmentPhases must contain traces for both phases
            val toolRequests = events.filterIsInstance<ToolRequestEvent>()
            toolRequests shouldHaveSize 1
            val phases = toolRequests[0].enrichmentPhases
            phases shouldNotBe null
            phases!! shouldHaveSize 2
            phases[0].phaseIndex shouldBe 0
            phases[0].enrichmentContent shouldBe "phase-0-data"
            phases[0].success shouldBe true
            phases[1].phaseIndex shouldBe 1
            phases[1].enrichmentContent shouldBe "phase-1-data"
            phases[1].success shouldBe true

            events.filterIsInstance<ToolResponseEvent>().single().success shouldBe true
            events.filterIsInstance<AgentFinishedEvent>() shouldHaveSize 1
        }

        // -------------------------------------------------------------------------
        // WZ-32491 — Force-stop on double repetition warning
        // -------------------------------------------------------------------------

        "WZ-32491: force-stops loop when count exceeds REPETITION_THRESHOLD within the window" {
            // Scenario: the tool is called WINDOW times with the same args.
            // On the THRESHOLD-th call (count == THRESHOLD) → Warned emitted + loop continues.
            // The generator ignores the warning and keeps calling the same tool.
            // On the next iteration count is still THRESHOLD (window slides, same tool fills it)
            // but wait — with WINDOW=5 and THRESHOLD=3, after 5 identical calls count=5 > THRESHOLD
            // → ForceStop directly. So the sequence is:
            //   iterations 1..THRESHOLD-1: window not full yet, no detection
            //   iteration THRESHOLD: window has THRESHOLD identical → Warned
            //   iteration THRESHOLD+1: window still has ≥THRESHOLD identical → count > THRESHOLD → ForceStop
            // The loop must exit WITHOUT calling intentionGenerator again and must emit
            // a WarnEvent containing the force-stop message, an IntentionGeneratedEvent with
            // toolName=Answer (the force-stop intention), and then a final generated response.
            val namespaceId = UUID.randomUUID()
            val caseId = UUID.randomUUID()
            val agentId = UUID.randomUUID()

            val mockChatClient = mockk<ChatClient>(relaxed = true)
            val mockStreamSpec = mockk<ChatClient.StreamResponseSpec>(relaxed = true)
            every { mockChatClient.prompt(any<Prompt>()).stream() } returns mockStreamSpec
            every { mockStreamSpec.chatResponse() } returns chatResponseFlux("Forced stop.")
            every { mockChatClient.prompt(any<Prompt>()).call().content() } returns "{}"

            val mockTool = mockk<StandardTool<String>>(relaxed = true)
            every { mockTool.name } returns "LOOP__tool"
            every { mockTool.description } returns "loops forever"
            every { mockTool.inputSchema } returns "{}"
            every { mockTool.paramType } returns String::class.java
            coEvery { mockTool.executeWithJson(any(), any()) } returns ToolExecutionResult.success("result")

            // Generator always returns the same looping tool — simulates an agent that never
            // heeds the repetition warning.
            val mockGenerator = mockk<AgentIntentionGenerator>()
            every {
                mockGenerator.generate(any(), any(), any(), any(), any())
            } returns
                IntentionGeneratedEvent(
                    namespaceId = namespaceId,
                    caseId = caseId,
                    agentId = agentId,
                    intention = "Keep calling the tool.",
                    toolName = "LOOP__tool",
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
                    name = "LoopAgent",
                    context = context,
                    intentionGenerator = mockGenerator,
                    objectMapper = testObjectMapper,
                    // High maxIterations: without the fix the agent would run until exhaustion.
                    maxIterations = 50,
                    llmProvider = "test-provider",
                    llmModel = "test-model",
                )

            val events = agent.run(makeInitialEvents(namespaceId, caseId)).toList()

            // With WINDOW=5 and THRESHOLD=3: after WINDOW identical calls the window is full
            // with count=WINDOW=5 > THRESHOLD=3 → ForceStop fires directly on the first detection.
            // There is no intermediate Warned step because count already exceeds THRESHOLD.
            // Exactly one WarnEvent must be emitted containing the force-stop message.
            val warnEvents = events.filterIsInstance<WarnEvent>()
            warnEvents shouldHaveSize 1
            warnEvents[0].message shouldContain "Forcing loop termination"

            // A synthetic IntentionGeneratedEvent with toolName=Answer must be emitted
            // so that generateFinalResponse is called and can explain the forced stop to the user.
            val intentionEvents = events.filterIsInstance<IntentionGeneratedEvent>()
            val forceStopIntention = intentionEvents.last()
            forceStopIntention.toolName shouldBe AgentIntentionGenerator.ANSWER_TOOL
            forceStopIntention.intention shouldContain "repetitions"
            forceStopIntention.isFailedIntention shouldBe false

            // generateFinalResponse must have been called: a TextChunkEvent and MessageEvent
            // are produced from the mocked streaming response.
            events.filterIsInstance<TextChunkEvent>() shouldHaveSize 1
            events.filterIsInstance<TextChunkEvent>()[0].chunk shouldBe "Forced stop."
            val agentMessages = events.filterIsInstance<MessageEvent>().filter { it.actor.role == ActorRole.AGENT }
            agentMessages shouldHaveSize 1
            (agentMessages[0].content.first() as MessageContent.Text).content shouldBe "Forced stop."

            // The WarnEvent must appear before the force-stop IntentionGeneratedEvent.
            val warnIdx = events.indexOf(warnEvents[0])
            val intentionIdx = events.indexOf(forceStopIntention)
            (warnIdx < intentionIdx) shouldBe true

            // The run must have terminated cleanly with AgentFinishedEvent.
            events.filterIsInstance<AgentFinishedEvent>() shouldHaveSize 1

            // The loop must have exited well before maxIterations (50).
            val toolResponses = events.filterIsInstance<ToolResponseEvent>()
            (toolResponses.size < 50) shouldBe true
        }

        "WZ-32491: first repetition detection only emits one WarnEvent, does not force-stop" {
            // Scenario: tool is called WINDOW times (threshold reached at count==THRESHOLD), then
            // the generator switches to Answer. Only one WarnEvent should be emitted and the
            // loop should NOT force-stop — the agent corrected itself before count exceeded THRESHOLD.
            val namespaceId = UUID.randomUUID()
            val caseId = UUID.randomUUID()

            val mockChatClient = mockk<ChatClient>(relaxed = true)
            val mockStreamSpec = mockk<ChatClient.StreamResponseSpec>(relaxed = true)
            every { mockChatClient.prompt(any<Prompt>()).stream() } returns mockStreamSpec
            every { mockStreamSpec.chatResponse() } returns chatResponseFlux("Done.")
            every { mockChatClient.prompt(any<Prompt>()).call().content() } returns "{}"

            val mockTool = mockk<StandardTool<String>>(relaxed = true)
            every { mockTool.name } returns "LOOP__tool"
            every { mockTool.description } returns "looping tool"
            every { mockTool.inputSchema } returns "{}"
            every { mockTool.paramType } returns String::class.java
            coEvery { mockTool.executeWithJson(any(), any()) } returns ToolExecutionResult.success("result")

            val agentId = UUID.randomUUID()
            val mockGenerator = mockk<AgentIntentionGenerator>()
            // WINDOW iterations of the tool, then Answer when the warning is passed.
            // The Warned fires when the window first fills with THRESHOLD identical calls.
            // The agent self-corrects on the very next iteration (with the warning hint),
            // so count never reaches THRESHOLD+1 and ForceStop is never triggered.
            every {
                mockGenerator.generate(any(), any(), any(), any(), isNull())
            } returnsMany
                (1..AgentAdvanced.REPETITION_WINDOW).map {
                    IntentionGeneratedEvent(
                        namespaceId = namespaceId,
                        caseId = caseId,
                        agentId = agentId,
                        intention = "Call tool $it.",
                        toolName = "LOOP__tool",
                    )
                }
            every {
                mockGenerator.generate(any(), any(), any(), any(), not(isNull()))
            } returns
                IntentionGeneratedEvent(
                    namespaceId = namespaceId,
                    caseId = caseId,
                    agentId = agentId,
                    intention = "Stopping.",
                    toolName = "Answer",
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
                    name = "SelfCorrectingAgent",
                    context = context,
                    intentionGenerator = mockGenerator,
                    objectMapper = testObjectMapper,
                    maxIterations = 20,
                    llmProvider = "test-provider",
                    llmModel = "test-model",
                )

            val events = agent.run(makeInitialEvents(namespaceId, caseId)).toList()

            // Exactly one WarnEvent for the first detection — no force-stop
            val warnEvents = events.filterIsInstance<WarnEvent>()
            warnEvents shouldHaveSize 1
            warnEvents[0].message shouldContain "LOOP__tool"

            // Agent finished normally after self-correcting
            events.filterIsInstance<AgentFinishedEvent>() shouldHaveSize 1
        }

        "WZ-32491: handleRepetition returns Warned when count equals REPETITION_THRESHOLD" {
            val agent = makeParserAgent()
            val repetition = ToolRepetition(toolName = "FILES__ReadFile", count = AgentAdvanced.REPETITION_THRESHOLD)

            val outcome =
                agent.handleRepetition(
                    repetition = repetition,
                )

            (outcome is RepetitionOutcome.Warned) shouldBe true
            (outcome as RepetitionOutcome.Warned).message shouldContain "FILES__ReadFile"
            outcome.message shouldContain "times"
        }

        "WZ-32491: handleRepetition returns ForceStop when count exceeds REPETITION_THRESHOLD" {
            val agent = makeParserAgent()
            // count > THRESHOLD triggers ForceStop directly — no need for prior WarnEvent
            val repetition = ToolRepetition(toolName = "FILES__ReadFile", count = AgentAdvanced.REPETITION_THRESHOLD + 1)

            val outcome =
                agent.handleRepetition(
                    repetition = repetition,
                )

            (outcome is RepetitionOutcome.ForceStop) shouldBe true
            (outcome as RepetitionOutcome.ForceStop).message shouldContain "Forcing loop termination"
        }

        "WZ-32491: handleRepetition returns null when repetition is null" {
            val agent = makeParserAgent()

            val outcome = agent.handleRepetition(repetition = null)

            outcome shouldBe null
        }

        "detectToolRepetition returns null when tool responses from a previous turn fill the window (cross-turn false positive)" {
            // Regression: before the fix, tool calls from a previous conversation turn
            // were included in the window, causing a false repetition detection at the
            // start of the next turn — even before the agent had called anything.
            // The fix scopes detection to events after the last user MessageEvent.
            val agent = makeParserAgent()
            val namespaceId = UUID.randomUUID()
            val caseId = UUID.randomUUID()
            val sameArgs = """{"profile":"A"}"""

            // Previous turn: user message + REPETITION_WINDOW calls to the same tool
            val previousUserMsg =
                MessageEvent(
                    namespaceId = namespaceId,
                    caseId = caseId,
                    actor = Actor("user1", "User", ActorRole.USER),
                    content = listOf(MessageContent.Text("first turn")),
                )
            val previousTurnEvents =
                (1..AgentAdvanced.REPETITION_WINDOW).flatMap { i ->
                    listOf(
                        ToolRequestEvent(
                            namespaceId = namespaceId,
                            caseId = caseId,
                            toolRequestId = "prev-req-$i",
                            toolName = "SelectActiveProfile",
                            args = sameArgs,
                        ),
                        ToolResponseEvent(
                            namespaceId = namespaceId,
                            caseId = caseId,
                            toolRequestId = "prev-req-$i",
                            toolName = "SelectActiveProfile",
                            output = MessageContent.Text("profile A selected"),
                        ),
                    )
                }

            // New turn: new user message, no tool calls yet
            val newUserMsg =
                MessageEvent(
                    namespaceId = namespaceId,
                    caseId = caseId,
                    actor = Actor("user1", "User", ActorRole.USER),
                    content = listOf(MessageContent.Text("second turn")),
                )

            val events = listOf(previousUserMsg) + previousTurnEvents + listOf(newUserMsg)

            // The window from the previous turn should NOT trigger detection in the new turn.
            agent.detectToolRepetition(events) shouldBe null
        }

        "detectToolRepetition returns null when window contains a synthetic ToolResponseEvent" {
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

            agent.detectToolRepetition(events) shouldBe null
        }
    })
