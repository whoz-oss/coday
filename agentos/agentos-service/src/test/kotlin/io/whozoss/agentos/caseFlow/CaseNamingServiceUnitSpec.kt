package io.whozoss.agentos.caseFlow

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldHaveMaxLength
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.whozoss.agentos.aiModel.AiModelService
import io.whozoss.agentos.aiProvider.AiProviderService
import io.whozoss.agentos.chat.ChatClientProvider
import io.whozoss.agentos.sdk.actor.Actor
import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.aiProvider.AiApiType
import io.whozoss.agentos.sdk.aiProvider.AiModel
import io.whozoss.agentos.sdk.aiProvider.AiProvider
import io.whozoss.agentos.sdk.aiProvider.ReasoningEffort
import io.whozoss.agentos.sdk.caseEvent.CaseEvent
import io.whozoss.agentos.sdk.caseEvent.CaseUpdatedEvent
import io.whozoss.agentos.sdk.caseEvent.MessageContent
import io.whozoss.agentos.sdk.caseEvent.MessageEvent
import io.whozoss.agentos.sdk.caseFlow.CaseStatus
import io.whozoss.agentos.sdk.entity.EntityMetadata
import org.springframework.ai.chat.client.ChatClient
import java.util.UUID

class CaseNamingServiceUnitSpec :
    StringSpec({

        val namespaceId: UUID = UUID.randomUUID()
        val providerId: UUID = UUID.randomUUID()
        val userActor = Actor(id = UUID.randomUUID().toString(), displayName = "User", role = ActorRole.USER)

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
            )

        var currentCase: Case? = null
        fun makeCase(title: String? = null): Case {
            val case = Case(metadata = EntityMetadata(), namespaceId = namespaceId, status = CaseStatus.IDLE)
            return (title?.let { case.copy(title = it) } ?: case).also { currentCase = it }
        }

        fun messageEvent(
            text: String,
            caseId: UUID,
        ) = MessageEvent(
            namespaceId = namespaceId,
            caseId = caseId,
            actor = userActor,
            content = listOf(MessageContent.Text(text)),
        )

        /**
         * Builds a [CaseNamingService]. The [caseRepository] mock records saved cases
         * in [savedCases] so tests can assert on the persisted title without fighting
         * MockK's generic type inference on [EntityRepository.save].
         */
        fun buildService(
            aiModelService: AiModelService = mockk { every { findAiModel(any()) } returns model },
            aiProviderService: AiProviderService = mockk { every { getById(any()) } returns provider },
            chatClientProvider: ChatClientProvider = mockk(),
            savedCases: MutableList<Case> = mutableListOf(),
        ): CaseNamingService {
            val caseRepository =
                mockk<CaseRepository> {
                    every { findByIds(any(), any()) } answers { listOfNotNull(currentCase) }
                    every { save(any()) } answers {
                        val c = firstArg<Case>()
                        savedCases.add(c)
                        c
                    }
                }
            return CaseNamingService(aiModelService, aiProviderService, chatClientProvider, caseRepository)
        }

        /**
         * Builds a stubbed [ChatClient] where [ChatClient.ChatClientRequestSpec.call] returns a
         * [ChatClient.CallResponseSpec] whose [ChatClient.CallResponseSpec.content] throws [exception].
         *
         * Mirrors the pattern in [io.whozoss.agentos.agent.ConfirmationManagerSpec.stubChatClient]
         * to avoid MockK type-inference issues with the generic methods on [ChatClient.ChatClientRequestSpec].
         */
        fun stubbedChatClientThrows(exception: Throwable = RuntimeException("Network error")): ChatClient {
            val chatClient = mockk<ChatClient>()
            val reqSpec = mockk<ChatClient.ChatClientRequestSpec>()
            val callSpec = mockk<ChatClient.CallResponseSpec>()
            every { chatClient.prompt(any<org.springframework.ai.chat.prompt.Prompt>()) } returns reqSpec
            every { reqSpec.call() } returns callSpec
            every { callSpec.content() } throws exception
            return chatClient
        }

        /**
         * Builds a stubbed [ChatClient] where [ChatClient.CallResponseSpec.content] returns [title]
         * (or null / blank to exercise fallback paths).
         *
         * Uses a relaxed [ChatClient.CallResponseSpec] mock so [ChatClient.CallResponseSpec.content]
         * returns null by default when [title] is null.
         */
        fun stubbedChatClient(title: String?): ChatClient {
            val chatClient = mockk<ChatClient>()
            val reqSpec = mockk<ChatClient.ChatClientRequestSpec>()
            val callSpec = mockk<ChatClient.CallResponseSpec>(relaxed = true)
            every { chatClient.prompt(any<org.springframework.ai.chat.prompt.Prompt>()) } returns reqSpec
            every { reqSpec.call() } returns callSpec
            if (title != null) {
                every { callSpec.content() } returnsMany listOf(title)
            }
            return chatClient
        }

        /**
         * Stubs [ChatClientProvider.getChatClient] to return the given [chatClient].
         */
        fun ChatClientProvider.stub(chatClient: ChatClient) {
            every { getChatClient(any(), any(), any()) } returns chatClient
        }

        // -------------------------------------------------------------------------
        // Fallback: no model configured
        // -------------------------------------------------------------------------

        "uses first-message fallback when no AI model is configured in the namespace" {
            val aiModelService = mockk<AiModelService> { every { findAiModel(any()) } returns null }
            val saved = mutableListOf<Case>()
            val service = buildService(aiModelService = aiModelService, savedCases = saved)

            val case = makeCase()
            val events: List<CaseEvent> = listOf(messageEvent("Hello, I need help with Kotlin", case.id))
            val emitted = mutableListOf<CaseEvent>()

            service.nameCase(case, events) { emitted.add(it) }

            saved.single().title shouldBe "Hello, I need help with Kotlin"
            emitted.filterIsInstance<CaseUpdatedEvent>().single().title shouldBe "Hello, I need help with Kotlin"
        }

        "ordinary naming fallback preserves multiple recent user messages" {
            //given
            val saved = mutableListOf<Case>()
            val service = buildService(aiModelService = mockk { every { findAiModel(any()) } returns null }, savedCases = saved)
            val case = makeCase()

            //when
            service.nameCase(case, listOf(messageEvent("First message", case.id), messageEvent("Second message", case.id))) { }

            //then
            saved.single().title shouldBe "First message\nSecond message"
            service.shutdown()
        }

        "truncates fallback title to 50 characters when first message is long" {
            val longMessage = "a".repeat(80)
            val aiModelService = mockk<AiModelService> { every { findAiModel(any()) } returns null }
            val saved = mutableListOf<Case>()
            val service = buildService(aiModelService = aiModelService, savedCases = saved)

            val case = makeCase()
            val events: List<CaseEvent> = listOf(messageEvent(longMessage, case.id))
            val emitted = mutableListOf<CaseEvent>()

            service.nameCase(case, events) { emitted.add(it) }

            saved.single().title shouldHaveMaxLength 50
            saved.single().title shouldBe "a".repeat(50)
        }

        // -------------------------------------------------------------------------
        // Fallback: provider not found
        // -------------------------------------------------------------------------

        "uses first-message fallback when provider is not found" {
            val aiProviderService =
                mockk<AiProviderService> {
                    every { getById(any()) } throws RuntimeException("Provider not found")
                }
            val saved = mutableListOf<Case>()
            val service = buildService(aiProviderService = aiProviderService, savedCases = saved)

            val case = makeCase()
            val events: List<CaseEvent> = listOf(messageEvent("Debug my Spring Boot app", case.id))
            val emitted = mutableListOf<CaseEvent>()

            service.nameCase(case, events) { emitted.add(it) }

            saved.single().title shouldBe "Debug my Spring Boot app"
        }

        // -------------------------------------------------------------------------
        // Fallback: LLM call fails or returns blank
        // -------------------------------------------------------------------------

        "uses first-message fallback when LLM call throws" {
            val chatClientProvider = mockk<ChatClientProvider>()
            chatClientProvider.stub(stubbedChatClientThrows())
            val saved = mutableListOf<Case>()
            val service = buildService(chatClientProvider = chatClientProvider, savedCases = saved)

            val case = makeCase()
            val events: List<CaseEvent> = listOf(messageEvent("Review my pull request", case.id))
            val emitted = mutableListOf<CaseEvent>()

            service.nameCase(case, events) { emitted.add(it) }

            saved.single().title shouldBe "Review my pull request"
        }

        "uses first-message fallback when LLM returns null" {
            val chatClientProvider = mockk<ChatClientProvider>()
            chatClientProvider.stub(stubbedChatClient(null))
            val saved = mutableListOf<Case>()
            val service = buildService(chatClientProvider = chatClientProvider, savedCases = saved)

            val case = makeCase()
            val events: List<CaseEvent> = listOf(messageEvent("Explain coroutines", case.id))
            val emitted = mutableListOf<CaseEvent>()

            service.nameCase(case, events) { emitted.add(it) }

            saved.single().title shouldBe "Explain coroutines"
        }

        "uses first-message fallback when LLM returns blank string" {
            val chatClientProvider = mockk<ChatClientProvider>()
            chatClientProvider.stub(stubbedChatClient("   "))
            val saved = mutableListOf<Case>()
            val service = buildService(chatClientProvider = chatClientProvider, savedCases = saved)

            val case = makeCase()
            val events: List<CaseEvent> = listOf(messageEvent("Optimize my SQL query", case.id))
            val emitted = mutableListOf<CaseEvent>()

            service.nameCase(case, events) { emitted.add(it) }

            saved.single().title shouldBe "Optimize my SQL query"
        }

        // -------------------------------------------------------------------------
        // Happy path: LLM returns a title
        // -------------------------------------------------------------------------

        "uses LLM-generated title when available" {
            val chatClientProvider = mockk<ChatClientProvider>()
            chatClientProvider.stub(stubbedChatClient("Kotlin Coroutines Explained"))
            val saved = mutableListOf<Case>()
            val service = buildService(chatClientProvider = chatClientProvider, savedCases = saved)

            val case = makeCase()
            val events: List<CaseEvent> = listOf(messageEvent("Can you explain how coroutines work in Kotlin?", case.id))
            val emitted = mutableListOf<CaseEvent>()

            service.nameCase(case, events) { emitted.add(it) }

            saved.single().title shouldBe "Kotlin Coroutines Explained"
            emitted.filterIsInstance<CaseUpdatedEvent>().single().title shouldBe "Kotlin Coroutines Explained"
        }

        "names the case with the reasoning effort of the default model capped at low" {
            val chatClientProvider = mockk<ChatClientProvider>()
            chatClientProvider.stub(stubbedChatClient("Effort title"))
            val effortModel = model.copy(reasoningEffort = ReasoningEffort.MAX)
            val service =
                buildService(
                    aiModelService = mockk { every { findAiModel(any()) } returns effortModel },
                    chatClientProvider = chatClientProvider,
                )

            val case = makeCase()
            service.nameCase(case, listOf(messageEvent("Plan the migration", case.id))) {}

            verify(exactly = 1) {
                chatClientProvider.getChatClient(effortModel.copy(reasoningEffort = ReasoningEffort.LOW), provider, case.id.toString())
            }
        }

        // -------------------------------------------------------------------------
        // Transcript: last N user messages are used
        // -------------------------------------------------------------------------

        "uses only the last MAX_USER_MESSAGES_FOR_NAMING user messages when more are present" {
            // With MAX_USER_MESSAGES_FOR_NAMING = 3, only the last 3 user messages feed
            // the LLM. We verify this indirectly: when the first two messages are the
            // only content, the transcript would be non-blank and the LLM would be called.
            // But when those two are excluded (beyond the takeLast window), the remaining
            // messages still produce a non-blank transcript — so the LLM IS called and
            // returns the stubbed title. The real assertion is on which messages form the
            // fallback: if the LLM fails we check the fallback is from the LAST messages.
            //
            // We test the window by making the first two messages the ONLY ones with text
            // and the last three empty — then the transcript is blank and nameCase skips.
            val chatClientProvider = mockk<ChatClientProvider>()
            chatClientProvider.stub(stubbedChatClient("Generated Title"))
            val saved = mutableListOf<Case>()
            val service = buildService(chatClientProvider = chatClientProvider, savedCases = saved)

            val case = makeCase()
            // Only the first two messages have text; the last three are empty.
            // takeLast(3) picks the last three (all empty) -> transcript is blank -> skip.
            val events: List<CaseEvent> = listOf(
                messageEvent("first old message", case.id),
                messageEvent("second old message", case.id),
                MessageEvent(
                    namespaceId = namespaceId,
                    caseId = case.id,
                    actor = userActor,
                    content = emptyList(), // no text
                ),
                MessageEvent(
                    namespaceId = namespaceId,
                    caseId = case.id,
                    actor = userActor,
                    content = emptyList(),
                ),
                MessageEvent(
                    namespaceId = namespaceId,
                    caseId = case.id,
                    actor = userActor,
                    content = emptyList(),
                ),
            )
            val emitted = mutableListOf<CaseEvent>()
            service.nameCase(case, events) { emitted.add(it) }

            // Transcript from the last 3 messages is blank -> nameCase must skip entirely
            saved shouldBe emptyList<Case>()
            emitted shouldBe emptyList<CaseEvent>()
        }

        // -------------------------------------------------------------------------
        // Edge cases
        // -------------------------------------------------------------------------

        "skips naming when events contain no user text content" {
            val saved = mutableListOf<Case>()
            val service = buildService(savedCases = saved)

            val case = makeCase()
            val events: List<CaseEvent> = emptyList()
            val emitted = mutableListOf<CaseEvent>()

            service.nameCase(case, events) { emitted.add(it) }

            saved shouldBe emptyList<Case>()
            emitted shouldBe emptyList<CaseEvent>()
        }

        "preserves a manual rename completed while title generation was in flight" {
            //given
            val case = makeCase()
            val renamed = case.copy(title = "Manual title")
            val repository = mockk<CaseRepository> {
                every { findByIds(any(), any()) } returns listOf(renamed)
                every { save(any()) } answers { firstArg() }
            }
            val service = CaseNamingService(
                mockk { every { findAiModel(any()) } returns null },
                mockk(), mockk(), repository,
            )
            val events = mutableListOf<CaseEvent>()

            //when
            service.nameCase(case, listOf(messageEvent("Generate a title", case.id))) { events.add(it) }

            //then
            verify(exactly = 0) { repository.save(any()) }
            events shouldBe emptyList<CaseEvent>()
        }

        "preserves a manual rename that wins an optimistic locking race" {
            //given
            val case = makeCase()
            val renamed = case.copy(title = "Manual title")
            val repository = mockk<CaseRepository> {
                every { findByIds(any(), any()) } returnsMany listOf(listOf(case), listOf(renamed))
                every { save(any()) } throws org.springframework.dao.OptimisticLockingFailureException("concurrent rename")
            }
            val service = CaseNamingService(
                mockk { every { findAiModel(any()) } returns null },
                mockk(), mockk(), repository,
            )
            val events = mutableListOf<CaseEvent>()

            //when
            service.nameCase(case, listOf(messageEvent("Generate a title", case.id))) { events.add(it) }

            //then
            verify(exactly = 1) { repository.save(any()) }
            events shouldBe emptyList<CaseEvent>()
        }

        "a manually set default-looking title is never automatically replaced later" {
            //given
            val case = makeCase().copy(titleEditGeneration = "manual-edit")
            val repository = mockk<CaseRepository> {
                every { findByIds(any(), any()) } returns listOf(case)
                every { save(any()) } answers { firstArg() }
            }
            val service = CaseNamingService(
                mockk { every { findAiModel(any()) } returns null },
                mockk(), mockk(), repository,
            )
            val events = mutableListOf<CaseEvent>()

            //when
            service.nameCase(case, listOf(messageEvent("Generate a title", case.id))) { events.add(it) }

            //then
            verify(exactly = 0) { repository.save(any()) }
            events shouldBe emptyList<CaseEvent>()
        }

        "a concurrent manual rename to the same title wins after an optimistic conflict" {
            //given
            val case = makeCase()
            val renamed = case.copy(titleEditGeneration = "manual-edit-1")
            val repository = mockk<CaseRepository> {
                every { findByIds(any(), any()) } returnsMany listOf(listOf(case), listOf(renamed))
                every { save(any()) } throws org.springframework.dao.OptimisticLockingFailureException("concurrent rename")
            }
            val service = CaseNamingService(
                mockk { every { findAiModel(any()) } returns null },
                mockk(), mockk(), repository,
            )
            val events = mutableListOf<CaseEvent>()

            //when
            service.nameCase(case, listOf(messageEvent("Generate a title", case.id))) { events.add(it) }

            //then
            verify(exactly = 1) { repository.save(any()) }
            events shouldBe emptyList<CaseEvent>()
        }

        "names Factory instructions with the Jira key and the normal fallback when no model is available" {
            //given
            val saved = mutableListOf<Case>()
            val service = buildService(aiModelService = mockk { every { findAiModel(any()) } returns null }, savedCases = saved)
            val case = makeCase(title = "WZ-35428")
            val emitted = mutableListOf<CaseEvent>()

            //when
            service.nameFromInstructions(case, "Analyse the issue and implement the fix", "WZ-35428") { emitted.add(it) }

            //then
            saved.single().title shouldBe "WZ-35428 Analyse the issue and implement the fix"
            emitted.filterIsInstance<CaseUpdatedEvent>().single().title shouldBe saved.single().title
        }

        "Factory naming keeps Jira prefix and normal fallback when model generation fails" {
            //given
            val provider = mockk<ChatClientProvider>()
            provider.stub(stubbedChatClientThrows())
            val saved = mutableListOf<Case>()
            val service = buildService(chatClientProvider = provider, savedCases = saved)
            val case = makeCase(title = "WZ-35428")

            //when
            service.nameFromInstructions(case, "Investigate login\nand update tests", "WZ-35428") { }

            //then
            saved.single().title shouldBe "WZ-35428 Investigate login"
        }

        "Factory naming cannot overwrite same-title manual edit after optimistic conflict" {
            //given
            val case = makeCase().copy(title = "WZ-35428")
            val renamed = case.copy(titleEditGeneration = "manual-edit")
            val repository = mockk<CaseRepository> {
                every { findByIds(any(), any()) } returnsMany listOf(listOf(case), listOf(renamed))
                every { save(any()) } throws org.springframework.dao.OptimisticLockingFailureException("concurrent rename")
            }
            val service = CaseNamingService(
                mockk { every { findAiModel(any()) } returns null }, mockk(), mockk(), repository,
            )
            val events = mutableListOf<CaseEvent>()

            //when
            service.nameFromInstructions(case, "Investigate login", "WZ-35428") { events.add(it) }

            //then
            verify(exactly = 1) { repository.save(any()) }
            events shouldBe emptyList<CaseEvent>()
        }

        "manual title fence avoids even a model lookup for Factory naming" {
            //given
            val case = makeCase().copy(titleEditGeneration = "manual")
            val modelService = mockk<AiModelService>(relaxed = true)
            val service = buildService(aiModelService = modelService)

            //when
            service.nameFromInstructions(case, "Confidential instructions", "WZ-35428") { }

            //then
            verify(exactly = 0) { modelService.findAiModel(any()) }
        }

        "generates a single-line Factory title and retains the Jira key" {
            //given
            val provider = mockk<ChatClientProvider>()
            provider.stub(stubbedChatClient("Repair login\nExtra detail"))
            val saved = mutableListOf<Case>()
            val service = buildService(chatClientProvider = provider, savedCases = saved)
            val case = makeCase(title = "WZ-35428")

            //when
            service.nameFromInstructions(case, "Investigate login", "WZ-35428") { }

            //then
            saved.single().title shouldBe "WZ-35428 Repair login"
        }

        "ordinary automatic naming never clears a concurrently pending Factory naming intent" {
            //given
            val original = makeCase()
            val pending = original.copy(namingInstructions = "Factory request", namingPrefix = "WZ-35428")
            val repository = mockk<CaseRepository> {
                every { findByIds(any(), any()) } returns listOf(pending)
                every { save(any()) } answers { firstArg() }
            }
            val service = CaseNamingService(mockk { every { findAiModel(any()) } returns null }, mockk(), mockk(), repository)

            //when
            service.nameCase(original, listOf(messageEvent("Ordinary message", original.id))) { }

            //then
            verify(exactly = 0) { repository.save(any()) }
            service.shutdown()
        }

        "an expired naming lease cannot publish even before another claimant arrives" {
            //given
            val case = makeCase(title = "WZ-35428").copy(namingInstructions = "Repair login", namingLeaseOwner = "worker", namingLeaseUntil = java.time.Instant.parse("2026-01-01T00:00:00Z"))
            val repository = mockk<CaseRepository> {
                every { completeNaming(any(), any()) } returns null
                every { findByIds(any(), any()) } returns listOf(case)
                every { save(any()) } answers { firstArg() }
            }
            val service = CaseNamingService(mockk { every { findAiModel(any()) } returns null }, mockk(), mockk(), repository,
                clock = java.time.Clock.fixed(java.time.Instant.parse("2026-01-01T00:00:01Z"), java.time.ZoneOffset.UTC))

            //when
            service.nameFromInstructions(case, "Repair login", "WZ-35428") { }

            //then
            verify(exactly = 0) { repository.save(any()) }
            service.shutdown()
        }

        "expired naming owner cannot publish after a successor reclaimed the intent" {
            //given
            val original = makeCase(title = "WZ-35428").copy(namingInstructions = "Repair login", namingPrefix = "WZ-35428", namingLeaseOwner = "old-owner")
            val successor = original.copy(namingLeaseOwner = "new-owner")
            val repository = mockk<CaseRepository> {
                every { completeNaming(any(), any()) } returns null
                every { findByIds(any(), any()) } returns listOf(successor)
                every { save(any()) } answers { firstArg() }
            }
            val service = CaseNamingService(mockk { every { findAiModel(any()) } returns null }, mockk(), mockk(), repository)
            val events = mutableListOf<CaseEvent>()

            //when
            service.nameFromInstructions(original, "Repair login", "WZ-35428") { events.add(it) }

            //then
            verify(exactly = 0) { repository.save(any()) }
            events shouldBe emptyList<CaseEvent>()
            service.shutdown()
        }

        "a blocked model call times out and naming uses fallback without waiting for network release" {
            //given
            val case = makeCase()
            val entered = java.util.concurrent.CountDownLatch(1)
            val release = java.util.concurrent.CountDownLatch(1)
            val chatClient = mockk<ChatClient>()
            val request = mockk<ChatClient.ChatClientRequestSpec>()
            val response = mockk<ChatClient.CallResponseSpec>()
            every { chatClient.prompt(any<org.springframework.ai.chat.prompt.Prompt>()) } returns request
            every { request.call() } returns response
            every { response.content() } answers {
                entered.countDown()
                while (release.count > 0) {
                    try { release.await() } catch (_: InterruptedException) { }
                }
                "Late model title"
            }
            val providerClient = mockk<ChatClientProvider>()
            providerClient.stub(chatClient)
            val saved = mutableListOf<Case>()
            val repository = mockk<CaseRepository> {
                every { findByIds(any(), any()) } returns listOf(case)
                every { save(any()) } answers { firstArg<Case>().also { saved.add(it) } }
            }
            val service = CaseNamingService(
                mockk { every { findAiModel(any()) } returns model },
                mockk { every { getById(any()) } returns provider }, providerClient, repository,
                namingCallTimeoutMs = 100,
            )
            try {
                //when
                service.nameCase(case, listOf(messageEvent("Fallback title", case.id))) { }
                //then
                entered.count shouldBe 0L
                release.count shouldBe 1L
                saved.single().title shouldBe "Fallback title"
            } finally {
                release.countDown()
                service.shutdown()
            }
        }

        "a stalled model call does not block the naming sweep caller" {
            //given
            val pending = makeCase(title = "WZ-35428").copy(namingInstructions = "Repair login", namingPrefix = "WZ-35428")
            val entered = java.util.concurrent.CountDownLatch(1)
            val release = java.util.concurrent.CountDownLatch(1)
            val repository = mockk<CaseRepository> {
                every { purgeExpiredNaming(any(), any()) } returns 0L
                every { findPendingNaming(any()) } returns listOf(pending)
                every { claimNaming(any(), any(), any(), any()) } answers { pending.copy(namingLeaseOwner = secondArg<String>()) }
                every { findByIds(any(), any()) } returns emptyList()
            }
            val modelService = mockk<AiModelService> {
                every { findAiModel(any()) } answers { entered.countDown(); release.await(5, java.util.concurrent.TimeUnit.SECONDS); null }
            }
            val service = CaseNamingService(modelService, mockk(), mockk(), repository)
            try {
                //when
                service.sweepPendingNaming()
                //then
                entered.await(5, java.util.concurrent.TimeUnit.SECONDS) shouldBe true
                release.count shouldBe 1L
            } finally {
                release.countDown()
                service.shutdown()
            }
        }

        "persisted naming intent resumes without a live runtime and is cleared once processed" {
            //given
            val pending = makeCase(title = "WZ-35428").copy(namingInstructions = "Repair login", namingPrefix = "WZ-35428")
            val saved = java.util.concurrent.CompletableFuture<Case>()
            var lastClaimOwner: String? = null
            var lastLeaseUntil: java.time.Instant? = null
            val repository = mockk<CaseRepository> {
                every { purgeExpiredNaming(any(), any()) } returns 0L
                every { findPendingNaming(any()) } returns listOf(pending)
                every { claimNaming(any(), any(), any(), any()) } answers {
                    lastClaimOwner = secondArg<String>()
                    lastLeaseUntil = arg(3)
                    pending.copy(namingLeaseOwner = lastClaimOwner, namingLeaseUntil = lastLeaseUntil)
                }
                every { findByIds(any(), any()) } answers {
                    listOf(pending.copy(namingLeaseOwner = lastClaimOwner, namingLeaseUntil = lastLeaseUntil))
                }
                every { completeNaming(any(), any()) } answers {
                    firstArg<Case>().copy(title = secondArg(), namingInstructions = null, namingPrefix = null, namingLeaseOwner = null, namingLeaseUntil = null).also { saved.complete(it) }
                }
                every { save(any()) } answers { firstArg<Case>().also { saved.complete(it) } }
            }
            val service = CaseNamingService(mockk { every { findAiModel(any()) } returns null }, mockk(), mockk(), repository)

            //when
            service.sweepPendingNaming()
            val result = saved.get(5, java.util.concurrent.TimeUnit.SECONDS)

            //then
            result.title shouldBe "WZ-35428 Repair login"
            result.namingInstructions shouldBe null
            result.namingPrefix shouldBe null
            service.shutdown()
        }

        "emits CaseUpdatedEvent with correct caseId and namespaceId" {
            val chatClientProvider = mockk<ChatClientProvider>()
            chatClientProvider.stub(stubbedChatClient("Generated Title"))
            val saved = mutableListOf<Case>()
            val service = buildService(chatClientProvider = chatClientProvider, savedCases = saved)

            val case = makeCase()
            val events: List<CaseEvent> = listOf(messageEvent("Some user message", case.id))
            val emitted = mutableListOf<CaseEvent>()

            service.nameCase(case, events) { emitted.add(it) }

            val updatedEvent = emitted.filterIsInstance<CaseUpdatedEvent>().single()
            updatedEvent.caseId shouldBe case.id
            updatedEvent.namespaceId shouldBe namespaceId
            updatedEvent.title shouldBe "Generated Title"
        }
    })
