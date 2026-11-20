package io.whozoss.agentos.caseFlow

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.mockk.CapturingSlot
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.whozoss.agentos.agent.LastLlmRequest
import io.whozoss.agentos.agent.LastLlmRequestRegistry
import io.whozoss.agentos.aiModel.AiModelService
import io.whozoss.agentos.aiProvider.AiProviderService
import io.whozoss.agentos.caseEvent.CaseEventService
import io.whozoss.agentos.chat.ChatClientProvider
import io.whozoss.agentos.chat.UsageAccumulator
import io.whozoss.agentos.config.UsageConfigProperties
import io.whozoss.agentos.exception.ConflictException
import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.namespace.NamespaceService
import io.whozoss.agentos.sdk.actor.Actor
import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.aiProvider.AiApiType
import io.whozoss.agentos.sdk.aiProvider.AiModel
import io.whozoss.agentos.sdk.aiProvider.AiProvider
import io.whozoss.agentos.sdk.aiProvider.ReasoningEffort
import io.whozoss.agentos.sdk.api.user.PromptCacheTtl
import io.whozoss.agentos.sdk.caseEvent.CaseEvent
import io.whozoss.agentos.sdk.caseEvent.MessageContent
import io.whozoss.agentos.sdk.caseEvent.MessageEvent
import io.whozoss.agentos.sdk.caseFlow.CaseStatus
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.sdk.usage.LlmUsage
import io.whozoss.agentos.usage.RunCostService
import io.whozoss.agentos.usage.UsageCostAggregate
import io.whozoss.agentos.usage.UsageOutcome
import io.whozoss.agentos.usage.UsageRecord
import io.whozoss.agentos.usage.UsageRecordService
import io.whozoss.agentos.usage.UsageSource
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.MessageType
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.messages.ToolResponseMessage
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.tool.ToolCallback
import org.springframework.ai.tool.definition.ToolDefinition
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

class CaseSideQuestionServiceUnitSpec :
    StringSpec({

        val namespaceId: UUID = UUID.randomUUID()
        val providerId: UUID = UUID.randomUUID()
        val userId: UUID = UUID.randomUUID()
        val user = Actor(id = userId.toString(), displayName = "Alice", role = ActorRole.USER)
        val agent = Actor(id = UUID.randomUUID().toString(), displayName = "Planner", role = ActorRole.AGENT)

        val provider =
            AiProvider(
                metadata = EntityMetadata(id = providerId),
                namespaceId = namespaceId,
                name = "test-provider",
                apiType = AiApiType.OpenAI,
            )
        val model =
            AiModel(
                metadata = EntityMetadata(),
                aiProviderId = providerId,
                namespaceId = namespaceId,
                apiModelName = "test-model",
                reasoningEffort = ReasoningEffort.HIGH,
            )
        val agentProvider = provider.copy(metadata = EntityMetadata(), name = "agent-provider", headers = mapOf("X-Team" to "a"))
        val agentModel = model.copy(metadata = EntityMetadata(), apiModelName = "agent-model", temperature = 0.3)
        val case = Case(metadata = EntityMetadata(), namespaceId = namespaceId, status = CaseStatus.RUNNING)

        fun message(
            text: String,
            actor: Actor = user,
        ): CaseEvent =
            MessageEvent(
                namespaceId = namespaceId,
                caseId = case.id,
                actor = actor,
                content = listOf(MessageContent.Text(text)),
            )

        val firstQuestion = message("Migrate the billing service")
        val agentAnswer = message("Billing is migrated.", agent)

        fun definition(name: String): ToolDefinition =
            ToolDefinition
                .builder()
                .name(name)
                .description("The $name tool")
                .inputSchema("""{"type":"object","properties":{}}""")
                .build()

        /** The agent's last request: system, question, a tool round; then its answer. */
        fun lastRequest(lastCoveredEventId: UUID = firstQuestion.id) =
            LastLlmRequest(
                caseId = case.id,
                messages =
                    listOf(
                        SystemMessage("You are the planner."),
                        UserMessage("Migrate the billing service"),
                        AssistantMessage
                            .builder()
                            .content("")
                            .toolCalls(listOf(AssistantMessage.ToolCall("call-1", "function", "deploy", "{}")))
                            .build(),
                        ToolResponseMessage
                            .builder()
                            .responses(listOf(ToolResponseMessage.ToolResponse("call-1", "deploy", "deployed")))
                            .build(),
                    ),
                toolDefinitions = listOf(definition("deploy"), definition("search")),
                model = agentModel,
                provider = agentProvider,
                finalText = "Billing is migrated.",
                agentName = "Planner",
                agentConfigId = UUID.randomUUID(),
                coveredEventCount = 1,
                lastCoveredEventId = lastCoveredEventId,
            )

        val usage = LlmUsage(inputTokens = 1000, outputTokens = 20, cacheReadTokens = 900, totalTokens = 1020, estimatedCostUsd = 0.002)

        class Fixture(
            storedCase: Case? = case,
            aiModel: AiModel? = model,
            events: List<CaseEvent> = listOf(firstQuestion),
            providerLookup: () -> AiProvider = { provider },
            stored: LastLlmRequest? = null,
            usageEnabled: Boolean = true,
            val runCostService: RunCostService? = null,
        ) {
            val chatClientProvider: ChatClientProvider = mockk()
            val caseService: CaseService = mockk { every { findById(any(), any()) } returns storedCase }
            val caseEventService: CaseEventService = mockk { every { findByParent(any()) } returns events }
            val records = CopyOnWriteArrayList<UsageRecord>()
            val usageRecordService: UsageRecordService =
                mockk { every { create(any()) } answers { firstArg<UsageRecord>().also { records.add(it) } } }
            val registry = LastLlmRequestRegistry().also { registry -> stored?.let(registry::put) }
            /** The accumulators the chat clients were made with. */
            val accumulators = mutableListOf<UsageAccumulator?>()
            val accumulator: UsageAccumulator? get() = accumulators.lastOrNull()
            val service =
                CaseSideQuestionService(
                    caseService,
                    caseEventService,
                    mockk<AiModelService> { every { findAiModel(any()) } returns aiModel },
                    mockk<AiProviderService> { every { getById(any()) } answers { providerLookup() } },
                    chatClientProvider,
                    registry,
                    usageRecordService,
                    runCostService,
                    UsageConfigProperties(enabled = usageEnabled),
                )

            fun stub(chatClient: ChatClient) {
                every { chatClientProvider.getChatClient(any(), any(), any(), captureNullable(accumulators), any(), any(), any(), any()) } returns
                    chatClient
            }
        }

        /** A client answering [answer], whose call records [recorded] in the accumulator the client was made with. */
        fun Fixture.stubbedChatClient(
            answer: String?,
            promptSlot: CapturingSlot<Prompt> = slot(),
            toolsSlot: CapturingSlot<List<ToolCallback>> = slot(),
            recorded: LlmUsage? = usage,
            duringCall: () -> Unit = {},
        ) {
            val chatClient = mockk<ChatClient>()
            val reqSpec = mockk<ChatClient.ChatClientRequestSpec>()
            val callSpec = mockk<ChatClient.CallResponseSpec>()
            every { chatClient.prompt(capture(promptSlot)) } returns reqSpec
            every { reqSpec.toolCallbacks(capture(toolsSlot)) } returns reqSpec
            every { reqSpec.call() } returns callSpec
            every { callSpec.content() } answers {
                recorded?.let { accumulator?.record(it) }
                duringCall()
                answer
            }
            stub(chatClient)
        }

        fun Fixture.throwingChatClient() {
            val chatClient = mockk<ChatClient>()
            val reqSpec = mockk<ChatClient.ChatClientRequestSpec>()
            val callSpec = mockk<ChatClient.CallResponseSpec>()
            every { chatClient.prompt(any<Prompt>()) } returns reqSpec
            every { reqSpec.call() } returns callSpec
            every { callSpec.content() } answers {
                accumulator?.record(usage)
                throw RuntimeException("401 invalid api key sk-secret")
            }
            stub(chatClient)
        }

        /** A real cost service over one case, whose usage since the last user message cost [pastCost]. */
        fun runCostService(
            threshold: Double?,
            pastCost: Double? = null,
        ): RunCostService {
            val withThreshold = case.copy(runCostThreshold = threshold)
            return RunCostService(
                mockk { every { findById(case.id) } returns withThreshold },
                mockk { every { findByParent(any()) } returns listOf(firstQuestion) },
                mockk<NamespaceService> { every { resolveRunCostThreshold(any()) } returns null },
                mockk { every { sumCostByCaseTreeSince(any(), any()) } returns pastCost?.let { UsageCostAggregate(it, 0) } },
                UsageConfigProperties(enabled = true),
            )
        }

        // ---------------------------------------------------------------------------------------------------------
        // From the agent's last request
        // ---------------------------------------------------------------------------------------------------------

        "from the agent's last request: its messages unchanged, its answer, then the framed question" {
            val stored = lastRequest()
            val fixture = Fixture(stored = stored, events = listOf(firstQuestion, agentAnswer))
            val prompt = slot<Prompt>()
            fixture.stubbedChatClient("  It deployed billing.  ", prompt)

            fixture.service.answer(case.id, "What did you deploy?", userId) shouldBe "It deployed billing."

            val messages = prompt.captured.instructions
            messages shouldHaveSize stored.messages.size + 2
            stored.messages.forEachIndexed { index, sent -> messages[index] shouldBeSameInstanceAs sent }
            messages[stored.messages.size].shouldBeInstanceOf<AssistantMessage>().text shouldBe "Billing is migrated."
            val question = messages.last().shouldBeInstanceOf<UserMessage>().text!!
            question shouldContain "<side_question>\nWhat did you deploy?\n</side_question>"
            question shouldContain "do not call any tool"
            messages.filterIsInstance<SystemMessage>().single() shouldBeSameInstanceAs stored.messages.first()
        }

        "from the agent's last request: same model, provider and case, tools declared only, and never run" {
            val stored = lastRequest()
            val fixture = Fixture(stored = stored)
            val tools = slot<List<ToolCallback>>()
            fixture.stubbedChatClient("ok", toolsSlot = tools)

            fixture.service.answer(case.id, "?", userId)

            verify(exactly = 1) {
                fixture.chatClientProvider.getChatClient(agentModel, agentProvider, case.id.toString(), any(), null, PromptCacheTtl.FIVE_MINUTES, false, null)
            }
            fixture.accumulator.shouldNotBeNull()
            tools.captured.map { it.toolDefinition } shouldBe stored.toolDefinitions
            tools.captured.forEachIndexed { index, tool -> tool.toolDefinition shouldBeSameInstanceAs stored.toolDefinitions[index] }
            tools.captured.forEach { tool -> shouldThrow<IllegalStateException> { tool.call("{}") } }
        }

        "from the agent's last request: records its usage for the agent, the user and the model" {
            val stored = lastRequest()
            val fixture = Fixture(stored = stored)
            fixture.stubbedChatClient("ok")

            fixture.service.answer(case.id, "?", userId)

            val record = fixture.records.single()
            record.caseId shouldBe case.id
            record.namespaceId shouldBe namespaceId
            record.source shouldBe UsageSource.LLM
            record.outcome shouldBe UsageOutcome.COMPLETED
            record.agentName shouldBe "Planner"
            record.agentConfigId shouldBe stored.agentConfigId
            record.userId shouldBe userId
            record.providerName shouldBe "agent-provider"
            record.apiModelName shouldBe "agent-model"
            record.inputTokens shouldBe 1000L
            record.cacheReadTokens shouldBe 900L
            record.cost shouldBe 0.002
        }

        "a user message the agent has not answered yet outdates its last request: the transcript is used" {
            val fixture =
                Fixture(stored = lastRequest(), events = listOf(firstQuestion, agentAnswer, message("Now migrate invoicing")))
            val prompt = slot<Prompt>()
            fixture.stubbedChatClient("ok", prompt)

            fixture.service.answer(case.id, "?", userId)

            prompt.captured.instructions.first().text shouldBe CaseSideQuestionService.SYSTEM_PROMPT
            prompt.captured.instructions[1].text!! shouldContain "Now migrate invoicing"
            verify(exactly = 1) { fixture.chatClientProvider.getChatClient(model, provider, case.id.toString(), any(), null, PromptCacheTtl.FIVE_MINUTES, false, null) }
        }

        "a last request covering events the case no longer has is not used" {
            val fixture = Fixture(stored = lastRequest(lastCoveredEventId = UUID.randomUUID()))
            val prompt = slot<Prompt>()
            fixture.stubbedChatClient("ok", prompt)

            fixture.service.answer(case.id, "?", userId)

            prompt.captured.instructions.first().text shouldBe CaseSideQuestionService.SYSTEM_PROMPT
        }

        "a response with tool calls only has no text: a 502, and nothing ran" {
            val fixture = Fixture(stored = lastRequest())
            val tools = slot<List<ToolCallback>>()
            fixture.stubbedChatClient(null, toolsSlot = tools)

            shouldThrow<ResponseStatusException> { fixture.service.answer(case.id, "?", userId) }.statusCode shouldBe
                HttpStatus.BAD_GATEWAY
        }

        // ---------------------------------------------------------------------------------------------------------
        // Run cost
        // ---------------------------------------------------------------------------------------------------------

        "a case over its run cost threshold gets a 409, without calling the model nor pausing the case" {
            val runCost = runCostService(threshold = 1.0, pastCost = 1.5)
            val fixture = Fixture(stored = lastRequest(), runCostService = runCost)
            fixture.stubbedChatClient("ok")

            val error = shouldThrow<ConflictException> { fixture.service.answer(case.id, "?", userId) }

            error.message!! shouldContain "run cost limit"
            verify(exactly = 0) { fixture.chatClientProvider.getChatClient(any(), any(), any(), any(), any(), any(), any(), any()) }
            runCost.isPaused(case.id) shouldBe false
            runCost.state(case.id).paused shouldBe false
            fixture.records.shouldBeEmpty()
        }

        "under the threshold, the side question counts toward the run cost and is never held by the cost gate" {
            val runCost = runCostService(threshold = 1.0)
            val fixture = Fixture(stored = lastRequest(), runCostService = runCost)
            var liveCost: Double? = null
            var gateOpen: Boolean? = null
            fixture.stubbedChatClient(
                "ok",
                recorded = LlmUsage(totalTokens = 10, estimatedCostUsd = 5.0),
                duringCall = {
                    liveCost = runCost.state(case.id).cost
                    gateOpen = fixture.accumulator!!.beforeCall().isDone
                },
            )

            fixture.service.answer(case.id, "?", userId) shouldBe "ok"

            liveCost shouldBe 5.0
            gateOpen shouldBe true
            runCost.isPaused(case.id) shouldBe false
            fixture.records.single().cost shouldBe 5.0
        }

        "with usage disabled, nothing is recorded and the run cost is not consulted" {
            val runCost = mockk<RunCostService>()
            val fixture = Fixture(stored = lastRequest(), usageEnabled = false, runCostService = runCost)
            fixture.stubbedChatClient("ok")

            fixture.service.answer(case.id, "?", userId) shouldBe "ok"

            fixture.accumulators shouldBe listOf(null)
            fixture.records.shouldBeEmpty()
            verify(exactly = 0) { runCost.state(any()) }
        }

        // ---------------------------------------------------------------------------------------------------------
        // From a transcript
        // ---------------------------------------------------------------------------------------------------------

        "without a last request: answers from the transcript, trimmed, with a system message and the question after it" {
            val fixture = Fixture()
            val prompt = slot<Prompt>()
            fixture.stubbedChatClient("  It migrates billing.  ", prompt)

            val answer = fixture.service.answer(case.id, "What is this case about?", userId)

            answer shouldBe "It migrates billing."
            val messages = prompt.captured.instructions
            messages.map { it.messageType } shouldBe listOf(MessageType.SYSTEM, MessageType.USER)
            messages[0].text shouldContain "no tools"
            messages[0].text shouldContain "does not contain the answer"
            val user = messages[1].text!!
            user shouldContain "User Alice: Migrate the billing service"
            user shouldContain "Side question: What is this case about?"
            (user.indexOf("Migrate the billing service") < user.indexOf("Side question")) shouldBe true
        }

        "without a last request: the default model of the namespace as configured, no tool, and its usage recorded" {
            val fixture = Fixture()
            fixture.stubbedChatClient("ok")

            fixture.service.answer(case.id, "anything?", userId)

            verify(exactly = 1) { fixture.chatClientProvider.getChatClient(model, provider, case.id.toString(), any(), null, PromptCacheTtl.FIVE_MINUTES, false, null) }
            val record = fixture.records.single()
            record.agentName shouldBe CaseSideQuestionService.SIDE_QUESTION_AGENT_NAME
            record.agentConfigId shouldBe null
            record.userId shouldBe userId
            record.providerName shouldBe "test-provider"
            record.apiModelName shouldBe "test-model"
            record.totalTokens shouldBe 1020L
        }

        "works on a case with no runtime and no message yet" {
            val fixture = Fixture(events = emptyList())
            val prompt = slot<Prompt>()
            fixture.stubbedChatClient("Nothing was said yet.", prompt)

            fixture.service.answer(case.id, "What happened?") shouldBe "Nothing was said yet."

            prompt.captured.instructions[1].text!! shouldContain SideQuestionTranscript.EMPTY
        }

        "fails with not found when the case does not exist, without calling the model" {
            val fixture = Fixture(storedCase = null)

            shouldThrow<ResourceNotFoundException> { fixture.service.answer(UUID.randomUUID(), "?") }

            verify(exactly = 0) { fixture.chatClientProvider.getChatClient(any(), any(), any(), any(), any(), any(), any(), any()) }
        }

        "fails with a readable conflict when the namespace has no default model" {
            val fixture = Fixture(aiModel = null)

            val error = shouldThrow<ConflictException> { fixture.service.answer(case.id, "?") }

            error.message!! shouldContain "No AI model is configured"
        }

        "fails with a readable conflict when the provider of the model cannot be found" {
            val fixture = Fixture(providerLookup = { throw RuntimeException("gone") })

            val error = shouldThrow<ConflictException> { fixture.service.answer(case.id, "?") }

            error.message!! shouldContain "provider"
        }

        "turns a provider failure into a 502 whose message carries nothing of the provider's error, and records the usage as failed" {
            val fixture = Fixture()
            fixture.throwingChatClient()

            val error = shouldThrow<ResponseStatusException> { fixture.service.answer(case.id, "?", userId) }

            error.statusCode shouldBe HttpStatus.BAD_GATEWAY
            error.reason!! shouldNotContain "sk-secret"
            error.reason!! shouldNotContain "401"
            fixture.records.single().outcome shouldBe UsageOutcome.FAILED
        }

        "turns a blank or missing answer into a 502" {
            listOf("   ", null).forEach { blank ->
                val fixture = Fixture()
                fixture.stubbedChatClient(blank)

                shouldThrow<ResponseStatusException> { fixture.service.answer(case.id, "?") }.statusCode shouldBe
                    HttpStatus.BAD_GATEWAY
            }
        }

        "persists nothing in the case: the service has no write path into the case or its events" {
            listOf(lastRequest(), null).forEach { stored ->
                val fixture = Fixture(stored = stored)
                fixture.stubbedChatClient("ok")

                fixture.service.answer(case.id, "?", userId)

                // CaseService and CaseEventService are strict mocks that only stub the two reads: any write
                // (addMessage, create, emit...) would have failed the call above.
                verify(exactly = 1) { fixture.caseEventService.findByParent(case.id) }
                verify(exactly = 0) { fixture.caseService.addMessage(any(), any(), any(), any(), any()) }
            }
        }
    })
