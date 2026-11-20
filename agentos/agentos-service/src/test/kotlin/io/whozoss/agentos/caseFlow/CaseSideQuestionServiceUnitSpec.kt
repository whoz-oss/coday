package io.whozoss.agentos.caseFlow

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.whozoss.agentos.agent.LastLlmRequest
import io.whozoss.agentos.agent.LastLlmRequestRegistry
import io.whozoss.agentos.agent.putCurrent
import io.whozoss.agentos.agent.MovingClock
import io.whozoss.agentos.aiModel.AiModelService
import io.whozoss.agentos.aiProvider.AiProviderService
import io.whozoss.agentos.caseEvent.CaseEventService
import io.whozoss.agentos.chat.ChatClientProvider
import io.whozoss.agentos.chat.DefinitionOnlyToolCallback
import io.whozoss.agentos.chat.LlmRequestOptions
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
import io.whozoss.agentos.sdk.caseEvent.AgentFinishedEvent
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
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.tool.ToolCallback
import org.springframework.ai.tool.definition.ToolDefinition
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

class CaseSideQuestionServiceUnitSpec :
    StringSpec({

        val namespaceId: UUID = UUID.randomUUID()
        val runUserId: UUID = UUID.randomUUID()
        val otherUserId: UUID = UUID.randomUUID()
        val user = Actor(id = runUserId.toString(), displayName = "Alice", role = ActorRole.USER)
        val agent = Actor(id = UUID.randomUUID().toString(), displayName = "Planner", role = ActorRole.AGENT)
        val start: Instant = Instant.parse("2026-01-01T10:00:00Z")

        val provider =
            AiProvider(metadata = EntityMetadata(), namespaceId = namespaceId, name = "test-provider", apiType = AiApiType.OpenAI)
        val model =
            AiModel(
                metadata = EntityMetadata(),
                aiProviderId = provider.id,
                namespaceId = namespaceId,
                apiModelName = "test-model",
                reasoningEffort = ReasoningEffort.HIGH,
            )
        val agentProvider =
            AiProvider(
                metadata = EntityMetadata(),
                namespaceId = namespaceId,
                name = "agent-provider",
                apiType = AiApiType.OpenAI,
                apiKey = "namespace-key",
                headers = mapOf("X-Team" to "a"),
            )
        val agentModel = model.copy(metadata = EntityMetadata(), aiProviderId = agentProvider.id, apiModelName = "agent-model", temperature = 0.3)
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
        val agentFinished = AgentFinishedEvent(namespaceId = namespaceId, caseId = case.id, agentId = UUID.randomUUID(), agentName = "Planner")
        val turnEvents = listOf(firstQuestion, agentAnswer, agentFinished)

        fun definition(name: String): ToolDefinition =
            ToolDefinition
                .builder()
                .name(name)
                .description("The $name tool")
                .inputSchema("""{"type":"object","properties":{}}""")
                .build()

        /** The agent's last request: system, question, a tool round; then its answer. */
        fun lastRequest(requestOptions: LlmRequestOptions = LlmRequestOptions.of(agentModel, agentProvider)) =
            LastLlmRequest(
                caseId = case.id,
                namespaceId = namespaceId,
                userId = runUserId,
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
                modelId = agentModel.id,
                providerId = agentProvider.id,
                requestOptions = requestOptions,
                finalText = "Billing is migrated.",
                agentName = "Planner",
                agentConfigId = UUID.randomUUID(),
                coveredEventCount = 2,
                lastCoveredEventId = agentAnswer.id,
            )

        val usage = LlmUsage(inputTokens = 1000, outputTokens = 20, cacheReadTokens = 900, totalTokens = 1020, estimatedCostUsd = 0.002)

        fun text(answer: String) = ChatResponse(listOf(Generation(AssistantMessage(answer))))

        val toolCallsOnly =
            ChatResponse(
                listOf(
                    Generation(
                        AssistantMessage
                            .builder()
                            .content("")
                            .toolCalls(listOf(AssistantMessage.ToolCall("call-2", "function", "deploy", "{}")))
                            .build(),
                    ),
                ),
            )

        /** A real cost service over [cases], whose usage since the last user message cost [pastCost] per case. */
        fun runCostService(
            vararg cases: Case,
            pastCost: (UUID) -> Double? = { null },
        ): RunCostService =
            RunCostService(
                mockk { every { findById(any()) } answers { cases.firstOrNull { it.id == firstArg() } } },
                mockk { every { findByParent(any()) } returns listOf(firstQuestion) },
                mockk<NamespaceService> { every { resolveRunCostThreshold(any()) } returns null },
                mockk { every { sumCostByCaseTreeSince(any(), any()) } answers { pastCost(firstArg())?.let { UsageCostAggregate(it, 0) } } },
                UsageConfigProperties(enabled = true),
            )

        class Fixture(
            storedCase: Case? = case,
            aiModel: AiModel? = model,
            val events: List<CaseEvent> = turnEvents,
            providerLookup: (UUID) -> AiProvider = { id -> listOf(provider, agentProvider).first { it.id == id } },
            /** The provider [base] resolves to for a user: the same by default (no personal overlay). */
            resolveFor: (UUID, AiProvider) -> AiProvider = { _, base -> base },
            stored: LastLlmRequest? = null,
            usageEnabled: Boolean = true,
            val runCostService: RunCostService = runCostService(case),
            usageRecordFailure: Exception? = null,
        ) {
            val clock = MovingClock(start)
            val chatClientProvider: ChatClientProvider = mockk()
            val caseService: CaseService = mockk { every { findById(any(), any()) } returns storedCase }
            val caseEventService: CaseEventService = mockk { every { findByParent(any()) } returns events }
            val records = CopyOnWriteArrayList<UsageRecord>()
            val usageRecordService: UsageRecordService =
                mockk {
                    every { create(any()) } answers {
                        usageRecordFailure?.let { throw it }
                        firstArg<UsageRecord>().also { records.add(it) }
                    }
                }
            val aiProviderService: AiProviderService =
                mockk {
                    every { getById(any()) } answers { providerLookup(firstArg()) }
                    every { resolveProvider(any(), any(), any()) } answers {
                        val base = listOf(provider.id, agentProvider.id).map(providerLookup).first { it.name == thirdArg<String>() }
                        resolveFor(secondArg(), base)
                    }
                }
            val registry = LastLlmRequestRegistry(clock).also { registry -> stored?.let(registry::putCurrent) }

            /** The accumulators the chat clients were made with. */
            val accumulators = mutableListOf<UsageAccumulator?>()
            val prompts = mutableListOf<Prompt>()
            val tools = mutableListOf<List<ToolCallback>>()
            val service =
                CaseSideQuestionService(
                    caseService,
                    caseEventService,
                    mockk<AiModelService> {
                        every { findAiModel(any()) } returns aiModel
                        every { findById(any(), any()) } answers { listOf(model, agentModel).firstOrNull { it.id == firstArg() } }
                    },
                    aiProviderService,
                    chatClientProvider,
                    registry,
                    usageRecordService,
                    runCostService,
                    clock,
                    SideQuestionProperties(),
                    UsageConfigProperties(enabled = usageEnabled),
                )

            /** Each request answers the next of [responses] (the last one repeats), recording [recorded]. */
            fun answering(
                vararg responses: ChatResponse?,
                recorded: LlmUsage? = usage,
                duringCall: () -> Unit = {},
            ) {
                var index = 0
                every {
                    chatClientProvider.getChatClient(any(), any(), any(), captureNullable(accumulators), any(), any(), any())
                } answers {
                    val chatClient = mockk<ChatClient>()
                    val reqSpec = mockk<ChatClient.ChatClientRequestSpec>()
                    val callSpec = mockk<ChatClient.CallResponseSpec>()
                    every { chatClient.prompt(capture(prompts)) } returns reqSpec
                    every { reqSpec.toolCallbacks(capture(tools)) } returns reqSpec
                    every { reqSpec.call() } returns callSpec
                    every { callSpec.chatResponse() } answers {
                        recorded?.let { accumulators.last()?.record(it) }
                        duringCall()
                        responses[minOf(index++, responses.lastIndex)]
                    }
                    chatClient
                }
            }

            fun failing() {
                every {
                    chatClientProvider.getChatClient(any(), any(), any(), captureNullable(accumulators), any(), any(), any())
                } answers {
                    val chatClient = mockk<ChatClient>()
                    val reqSpec = mockk<ChatClient.ChatClientRequestSpec>()
                    val callSpec = mockk<ChatClient.CallResponseSpec>()
                    every { chatClient.prompt(any<Prompt>()) } returns reqSpec
                    every { reqSpec.call() } returns callSpec
                    every { callSpec.chatResponse() } answers {
                        accumulators.last()?.record(usage)
                        throw RuntimeException("401 invalid api key sk-secret")
                    }
                    chatClient
                }
            }

            /** True when the request was built from the transcript (its own system message), not the agent's. */
            fun usedTranscript(index: Int = prompts.lastIndex): Boolean = prompts[index].instructions.first().text == CaseSideQuestionService.SYSTEM_PROMPT
        }

        // ---------------------------------------------------------------------------------------------------------
        // From the agent's last request
        // ---------------------------------------------------------------------------------------------------------

        "from the agent's last request: its messages unchanged, its answer, then the framed question" {
            val stored = lastRequest()
            val fixture = Fixture(stored = stored)
            fixture.answering(text("  It deployed billing.  "))

            val answer = fixture.service.answer(case.id, "What did you deploy?", runUserId)

            answer shouldBe "It deployed billing."
            val messages = fixture.prompts.single().instructions
            messages shouldHaveSize stored.messages.size + 2
            stored.messages.forEachIndexed { index, sent -> messages[index] shouldBeSameInstanceAs sent }
            messages[stored.messages.size].shouldBeInstanceOf<AssistantMessage>().text shouldBe "Billing is migrated."
            val question = messages.last().shouldBeInstanceOf<UserMessage>().text!!
            question shouldContain "<side_question>\nWhat did you deploy?\n</side_question>"
            question shouldContain "do not call any tool"
            messages.filterIsInstance<SystemMessage>().single() shouldBeSameInstanceAs stored.messages.first()
        }

        "from the agent's last request: same model and provider resolved again, tools declared only, no tool run" {
            val stored = lastRequest()
            val fixture = Fixture(stored = stored)
            fixture.answering(text("ok"))

            fixture.service.answer(case.id, "?", runUserId)

            verify(exactly = 1) {
                fixture.chatClientProvider.getChatClient(agentModel, agentProvider, case.id.toString(), any(), null, false, null)
            }
            val tools = fixture.tools.single()
            tools.map { it.toolDefinition } shouldBe stored.toolDefinitions
            tools.forEach { tool -> tool.shouldBeInstanceOf<DefinitionOnlyToolCallback>() }
            tools.forEach { tool -> shouldThrow<IllegalStateException> { tool.call("{}") } }
        }

        "a rotated API key no longer matches the credential fingerprint: the transcript answers, never a 502" {
            val rotated = agentProvider.copy(apiKey = "rotated-key")
            val fixture =
                Fixture(stored = lastRequest(), providerLookup = { id -> if (id == agentProvider.id) rotated else provider })
            fixture.answering(text("ok"))

            fixture.service.answer(case.id, "?", runUserId) shouldBe "ok"

            fixture.usedTranscript() shouldBe true
        }

        "changed provider headers no longer match the header digest: the transcript answers" {
            val reheadered = agentProvider.copy(headers = mapOf("X-Team" to "b"))
            val fixture =
                Fixture(stored = lastRequest(), providerLookup = { id -> if (id == agentProvider.id) reheadered else provider })
            fixture.answering(text("ok"))

            fixture.service.answer(case.id, "?", runUserId)

            fixture.usedTranscript() shouldBe true
        }

        "the last request keeps no API key nor verbatim header, only their digests" {
            val options = lastRequest().requestOptions

            options.toString() shouldNotContain "namespace-key"
            options.toString() shouldNotContain "X-Team"
            options.credentialFingerprint!!.length shouldBe 64
        }

        "a run starting between the read and the dispatch outdates the last request: the transcript answers" {
            val fixture = Fixture(stored = lastRequest())
            fixture.answering(text("ok"))
            var reads = 0
            every { fixture.caseEventService.findByParent(any()) } answers {
                // The second read is the check right before dispatch: a run started just before it.
                if (++reads == 2) fixture.registry.invalidate(case.id)
                turnEvents
            }

            fixture.service.answer(case.id, "?", runUserId) shouldBe "ok"

            fixture.prompts shouldHaveSize 1
            fixture.usedTranscript() shouldBe true
            fixture.records.single().outcome shouldBe UsageOutcome.COMPLETED
        }

        "a message posted between the read and the dispatch outdates the last request: the transcript answers with it" {
            val fixture = Fixture(stored = lastRequest())
            fixture.answering(text("ok"))
            var reads = 0
            every { fixture.caseEventService.findByParent(any()) } answers {
                if (++reads >= 2) turnEvents + message("Also migrate invoicing") else turnEvents
            }

            fixture.service.answer(case.id, "?", runUserId)

            fixture.prompts shouldHaveSize 1
            fixture.usedTranscript() shouldBe true
            fixture.prompts.single().instructions[1].text!! shouldContain "Also migrate invoicing"
        }

        "a successful side question from the agent's last request records that it refreshed the cache" {
            val fixture = Fixture(stored = lastRequest())
            fixture.answering(text("ok"))
            fixture.clock.now = start.plusSeconds(120)

            fixture.service.answer(case.id, "?", runUserId)

            fixture.registry.get(case.id)!!.lastUsedAt shouldBe start.plusSeconds(120)
        }

        "records the usage of every side question under the side-question name, for the caller and the model used" {
            val fixture = Fixture(stored = lastRequest())
            fixture.answering(text("ok"))

            fixture.service.answer(case.id, "?", runUserId)

            val record = fixture.records.single()
            record.caseId shouldBe case.id
            record.namespaceId shouldBe namespaceId
            record.source shouldBe UsageSource.LLM
            record.outcome shouldBe UsageOutcome.COMPLETED
            record.agentName shouldBe CaseSideQuestionService.SIDE_QUESTION_AGENT_NAME
            record.agentConfigId shouldBe null
            record.userId shouldBe runUserId
            record.providerName shouldBe "agent-provider"
            record.apiModelName shouldBe "agent-model"
            record.inputTokens shouldBe 1000L
            record.cacheReadTokens shouldBe 900L
            record.cost shouldBe 0.002
        }

        // ---------------------------------------------------------------------------------------------------------
        // When the agent's last request is not reused
        // ---------------------------------------------------------------------------------------------------------

        "another agent speaking since (a redirect, without any user message) outdates the last request" {
            val events = turnEvents + message("Redirected to the reviewer: all good.", agent)
            val fixture = Fixture(stored = lastRequest(), events = events)
            fixture.answering(text("ok"))

            fixture.service.answer(case.id, "?", runUserId)

            fixture.usedTranscript() shouldBe true
            fixture.prompts.single().instructions[1].text!! shouldContain "Redirected to the reviewer"
        }

        "a user message the agent has not answered yet outdates the last request" {
            val fixture = Fixture(stored = lastRequest(), events = turnEvents + message("Now migrate invoicing"))
            fixture.answering(text("ok"))

            fixture.service.answer(case.id, "?", runUserId)

            fixture.usedTranscript() shouldBe true
            verify(exactly = 1) { fixture.chatClientProvider.getChatClient(model, provider, case.id.toString(), any(), null, false, null) }
        }

        "a last request whose cache went cold is not replayed, one used recently is" {
            val fixture = Fixture(stored = lastRequest())
            fixture.answering(text("ok"))
            val maxAge = SideQuestionProperties().snapshotMaxAge

            fixture.clock.now = start.plus(maxAge).minusSeconds(1)
            fixture.service.answer(case.id, "?", runUserId)
            fixture.usedTranscript() shouldBe false

            // Its use just now refreshed the cache: still warm a little later.
            fixture.clock.now = start.plus(maxAge).plusSeconds(30)
            fixture.service.answer(case.id, "?", runUserId)
            fixture.usedTranscript() shouldBe false

            fixture.clock.now = start.plus(maxAge.multipliedBy(3))
            fixture.service.answer(case.id, "?", runUserId)
            fixture.usedTranscript() shouldBe true
        }

        "another user whose provider resolves differently (a personal API key) gets the transcript, with their provider" {
            val personal = agentProvider.copy(apiKey = "bob-personal-key")
            val transcriptPersonal = provider.copy(apiKey = "bob-personal-key")
            val fixture =
                Fixture(
                    stored = lastRequest(),
                    resolveFor = { userId, base ->
                        when {
                            userId != otherUserId -> base
                            base.id == agentProvider.id -> personal
                            else -> transcriptPersonal
                        }
                    },
                )
            fixture.answering(text("ok"))

            fixture.service.answer(case.id, "?", otherUserId)

            fixture.usedTranscript() shouldBe true
            verify(exactly = 1) { fixture.chatClientProvider.getChatClient(model, transcriptPersonal, any(), any(), any(), false, any()) }
            fixture.records.single().userId shouldBe otherUserId
        }

        "the run user's personal provider is never used for another user" {
            val runUsersOwn = agentProvider.copy(apiKey = "alice-personal-key")
            val fixture =
                Fixture(
                    stored = lastRequest(),
                    resolveFor = { userId, base -> if (userId == runUserId && base.id == agentProvider.id) runUsersOwn else base },
                )
            fixture.answering(text("ok"))

            fixture.service.answer(case.id, "?", otherUserId)

            fixture.usedTranscript() shouldBe true
            verify(exactly = 0) { fixture.chatClientProvider.getChatClient(any(), runUsersOwn, any(), any(), any(), any(), any()) }
        }

        "another user is never answered from the last request, even when the provider resolves the same for both" {
            listOf(otherUserId, null).forEach { caller ->
                val fixture = Fixture(stored = lastRequest())
                fixture.answering(text("ok"))

                fixture.service.answer(case.id, "?", caller)

                // The system message of the turn may carry what the agent knows of the user it ran for.
                fixture.usedTranscript() shouldBe true
            }
        }

        "request options that changed since (the case's reasoning effort) fall back to the transcript" {
            val fixture = Fixture(storedCase = case.copy(reasoningEffort = ReasoningEffort.LOW), stored = lastRequest())
            fixture.answering(text("ok"))

            fixture.service.answer(case.id, "?", runUserId)

            fixture.usedTranscript() shouldBe true
        }

        "the case's reasoning effort is applied as the agent did, so an unchanged one reuses the request" {
            val withCaseEffort = agentModel.copy(reasoningEffort = ReasoningEffort.LOW)
            val fixture =
                Fixture(
                    storedCase = case.copy(reasoningEffort = ReasoningEffort.LOW),
                    stored = lastRequest(LlmRequestOptions.of(withCaseEffort, agentProvider)),
                )
            fixture.answering(text("ok"))

            fixture.service.answer(case.id, "?", runUserId)

            fixture.usedTranscript() shouldBe false
            verify(exactly = 1) { fixture.chatClientProvider.getChatClient(withCaseEffort, agentProvider, any(), any(), any(), false, any()) }
        }

        // ---------------------------------------------------------------------------------------------------------
        // Answers without text
        // ---------------------------------------------------------------------------------------------------------

        "tool calls only from the last request: recorded as failed, then asked once from the transcript, with no tool" {
            val fixture = Fixture(stored = lastRequest())
            fixture.answering(toolCallsOnly, text("From the transcript."))

            fixture.service.answer(case.id, "?", runUserId) shouldBe "From the transcript."

            fixture.prompts shouldHaveSize 2
            fixture.usedTranscript(0) shouldBe false
            fixture.usedTranscript(1) shouldBe true
            // Only the first request declared tools, definitions only.
            fixture.tools.single().forEach { it.shouldBeInstanceOf<DefinitionOnlyToolCallback>() }
            fixture.records.map { it.outcome } shouldBe listOf(UsageOutcome.FAILED, UsageOutcome.COMPLETED)
        }

        "no text from the transcript either: a 502" {
            val fixture = Fixture(stored = lastRequest())
            fixture.answering(toolCallsOnly)

            val error = shouldThrow<ResponseStatusException> { fixture.service.answer(case.id, "?", runUserId) }

            error.statusCode shouldBe HttpStatus.BAD_GATEWAY
            fixture.prompts shouldHaveSize 2
            fixture.records.map { it.outcome } shouldBe listOf(UsageOutcome.FAILED, UsageOutcome.FAILED)
        }

        "a blank answer is recorded as failed, then a 502" {
            listOf(text("   "), null).forEach { blank ->
                val fixture = Fixture()
                fixture.answering(blank)

                val error = shouldThrow<ResponseStatusException> { fixture.service.answer(case.id, "?", runUserId) }

                error.statusCode shouldBe HttpStatus.BAD_GATEWAY
                fixture.records.single().outcome shouldBe UsageOutcome.FAILED
            }
        }

        // ---------------------------------------------------------------------------------------------------------
        // Run cost
        // ---------------------------------------------------------------------------------------------------------

        "a case over its run cost threshold gets a 409, without calling the model nor pausing the case" {
            val limited = case.copy(runCostThreshold = 1.0)
            val runCost = runCostService(limited, pastCost = { 1.5 })
            val fixture = Fixture(stored = lastRequest(), runCostService = runCost)
            fixture.answering(text("ok"))

            val error = shouldThrow<ConflictException> { fixture.service.answer(case.id, "?", runUserId) }

            error.message!! shouldContain "run cost limit"
            verify(exactly = 0) { fixture.chatClientProvider.getChatClient(any(), any(), any(), any(), any(), any(), any()) }
            runCost.isPaused(case.id) shouldBe false
            runCost.state(case.id).paused shouldBe false
            fixture.records.shouldBeEmpty()
        }

        "the transcript path is refused the same way over the threshold" {
            val limited = case.copy(runCostThreshold = 1.0)
            val fixture = Fixture(runCostService = runCostService(limited, pastCost = { 1.5 }))
            fixture.answering(text("ok"))

            shouldThrow<ConflictException> { fixture.service.answer(case.id, "?", runUserId) }

            fixture.prompts.shouldBeEmpty()
        }

        "a case waiting for a run cost confirmation gets a 409" {
            val limited = case.copy(runCostThreshold = 1.0)
            val runCost = runCostService(limited)
            val turn = UsageAccumulator()
            val registration = runCost.register(case.id, turn)
            turn.record(LlmUsage(totalTokens = 10, estimatedCostUsd = 2.0))
            registration.beforeCall()
            val fixture = Fixture(runCostService = runCost)
            fixture.answering(text("ok"))

            val error = shouldThrow<ConflictException> { fixture.service.answer(case.id, "?", runUserId) }

            error.message!! shouldContain "confirmation"
            fixture.prompts.shouldBeEmpty()
        }

        "a sub-case whose parent is over its threshold gets a 409" {
            val parent = Case(metadata = EntityMetadata(), namespaceId = namespaceId, runCostThreshold = 1.0)
            val child = case.copy(parentCaseId = parent.id)
            val runCost = runCostService(parent, child, pastCost = { id -> if (id == parent.id) 1.5 else null })
            val fixture = Fixture(storedCase = child, runCostService = runCost)
            fixture.answering(text("ok"))

            val error = shouldThrow<ConflictException> { fixture.service.answer(child.id, "?", runUserId) }

            error.message!! shouldContain "parent"
            fixture.prompts.shouldBeEmpty()
            runCost.isPaused(parent.id) shouldBe false
        }

        "under the threshold, the side question counts toward the run cost and never waits on the cost gate" {
            val limited = case.copy(runCostThreshold = 1.0)
            val runCost = runCostService(limited)
            val fixture = Fixture(stored = lastRequest(), runCostService = runCost)
            var liveCost: Double? = null
            var gateOpen: Boolean? = null
            fixture.answering(
                text("ok"),
                recorded = LlmUsage(totalTokens = 10, estimatedCostUsd = 5.0),
                duringCall = {
                    liveCost = runCost.state(case.id).cost
                    gateOpen = fixture.accumulators.last()!!.beforeCall().isDone
                },
            )

            fixture.service.answer(case.id, "?", runUserId) shouldBe "ok"

            liveCost shouldBe 5.0
            gateOpen shouldBe true
            runCost.isPaused(case.id) shouldBe false
            runCost.state(case.id).active shouldBe false
            fixture.records.single().cost shouldBe 5.0
        }

        "with usage disabled, nothing is recorded and the run cost is not consulted" {
            val runCost = mockk<RunCostService>()
            val fixture = Fixture(stored = lastRequest(), usageEnabled = false, runCostService = runCost)
            fixture.answering(text("ok"))

            fixture.service.answer(case.id, "?", runUserId) shouldBe "ok"

            fixture.accumulators shouldBe listOf(null)
            fixture.records.shouldBeEmpty()
            verify(exactly = 0) { runCost.registerNonPausing(any(), any()) }
        }

        "a usage record that cannot be written does not change the answer, and the live cost is released" {
            val runCost = runCostService(case)
            val fixture = Fixture(runCostService = runCost, usageRecordFailure = IllegalStateException("store down"))
            fixture.answering(text("ok"))

            fixture.service.answer(case.id, "?", runUserId) shouldBe "ok"

            verify(exactly = 1) { fixture.usageRecordService.create(any()) }
            runCost.state(case.id).active shouldBe false
        }

        // ---------------------------------------------------------------------------------------------------------
        // From a transcript
        // ---------------------------------------------------------------------------------------------------------

        "without a last request: answers from the transcript, trimmed, with a system message and the question after it" {
            val fixture = Fixture()
            fixture.answering(text("  It migrates billing.  "))

            val answer = fixture.service.answer(case.id, "What is this case about?", runUserId)

            answer shouldBe "It migrates billing."
            val messages = fixture.prompts.single().instructions
            messages.map { it.messageType } shouldBe listOf(MessageType.SYSTEM, MessageType.USER)
            messages[0].text shouldContain "no tools"
            messages[0].text shouldContain "does not contain the answer"
            val question = messages[1].text!!
            question shouldContain "User Alice: Migrate the billing service"
            question shouldContain "Side question: What is this case about?"
            question.indexOf("Migrate the billing service") shouldBeLessThan question.indexOf("Side question")
        }

        "without a last request: the default model of the namespace as configured, no tool, its usage recorded" {
            val fixture = Fixture()
            fixture.answering(text("ok"))

            fixture.service.answer(case.id, "anything?", runUserId)

            verify(exactly = 1) { fixture.chatClientProvider.getChatClient(model, provider, case.id.toString(), any(), null, false, null) }
            fixture.tools.shouldBeEmpty()
            val record = fixture.records.single()
            record.agentName shouldBe CaseSideQuestionService.SIDE_QUESTION_AGENT_NAME
            record.agentConfigId shouldBe null
            record.userId shouldBe runUserId
            record.providerName shouldBe "test-provider"
            record.apiModelName shouldBe "test-model"
            record.totalTokens shouldBe 1020L
        }

        "works on a case with no runtime and no message yet" {
            val fixture = Fixture(events = emptyList())
            fixture.answering(text("Nothing was said yet."))

            fixture.service.answer(case.id, "What happened?") shouldBe "Nothing was said yet."

            fixture.prompts.single().instructions[1].text!! shouldContain SideQuestionTranscript.EMPTY
        }

        "fails with not found when the case does not exist, without calling the model" {
            val fixture = Fixture(storedCase = null)

            shouldThrow<ResourceNotFoundException> { fixture.service.answer(UUID.randomUUID(), "?") }

            verify(exactly = 0) { fixture.chatClientProvider.getChatClient(any(), any(), any(), any(), any(), any(), any()) }
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

        "turns a provider failure into a 502 whose message carries nothing of the provider's error, recorded as failed" {
            val fixture = Fixture()
            fixture.failing()

            val error = shouldThrow<ResponseStatusException> { fixture.service.answer(case.id, "?", runUserId) }

            error.statusCode shouldBe HttpStatus.BAD_GATEWAY
            error.reason!! shouldNotContain "sk-secret"
            error.reason!! shouldNotContain "401"
            fixture.records.single().outcome shouldBe UsageOutcome.FAILED
        }

        "persists nothing in the case: the service has no write path into the case or its events" {
            listOf(lastRequest(), null).forEach { stored ->
                val fixture = Fixture(stored = stored)
                fixture.answering(text("ok"))

                fixture.service.answer(case.id, "?", runUserId)

                // CaseService and CaseEventService are strict mocks that only stub the two reads: any write
                // (addMessage, create, emit...) would have failed the call above.
                verify(atLeast = 1) { fixture.caseEventService.findByParent(case.id) }
                verify(exactly = 0) { fixture.caseService.addMessage(any(), any(), any(), any(), any()) }
            }
        }
    })
