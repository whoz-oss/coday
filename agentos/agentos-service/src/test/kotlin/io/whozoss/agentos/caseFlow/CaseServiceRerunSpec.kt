package io.whozoss.agentos.caseFlow

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.whozoss.agentos.agent.AgentConfigProperties
import io.whozoss.agentos.agent.AgentExecutionContext
import io.whozoss.agentos.agent.AgentService
import io.whozoss.agentos.agentConfig.AgentConfig
import io.whozoss.agentos.agentConfig.AgentConfigService
import io.whozoss.agentos.caseEvent.CaseEventService
import io.whozoss.agentos.caseEvent.CaseEventServiceImpl
import io.whozoss.agentos.caseEvent.InMemoryCaseEventRepository
import io.whozoss.agentos.config.LimitsConfigProperties
import io.whozoss.agentos.config.UsageConfigProperties
import io.whozoss.agentos.exception.BadRequestException
import io.whozoss.agentos.exception.ConflictException
import io.whozoss.agentos.namespace.Namespace
import io.whozoss.agentos.namespace.NamespaceService
import io.whozoss.agentos.sdk.actor.Actor
import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.agent.Agent
import io.whozoss.agentos.sdk.caseEvent.AgentFinishedEvent
import io.whozoss.agentos.sdk.caseEvent.CaseEvent
import io.whozoss.agentos.sdk.caseEvent.CaseStatusEvent
import io.whozoss.agentos.sdk.caseEvent.MessageContent
import io.whozoss.agentos.sdk.caseEvent.MessageEvent
import io.whozoss.agentos.sdk.caseFlow.CaseStatus
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.user.User
import io.whozoss.agentos.user.UserService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeout
import org.springframework.security.access.AccessDeniedException
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

class CaseServiceRerunSpec :
    StringSpec({
        timeout = 20_000

        fun List<CaseEvent>.texts(): List<String> =
            filterIsInstance<MessageEvent>().map { m -> m.content.filterIsInstance<MessageContent.Text>().joinToString { it.content } }

        "a re-run is persisted with rerunOf, the log keeps everything, the agent only sees the effective conversation" {
            val f = RerunFixture()
            try {
                f.send("one")
                f.send("two")
                val two = f.userMessages().last()

                f.send("two, edited", rerunOf = two.id)

                // The log is append-only: the replaced message and its answer are still there.
                val log = f.events.findByParent(f.caseId)
                log.filterIsInstance<MessageEvent>().filter { it.actor.role == ActorRole.USER }.map { it.rerunOf } shouldBe
                    listOf(null, null, two.id)
                log.texts() shouldBe listOf("one", "reply to one", "two", "reply to two", "two, edited", "reply to two, edited")

                // What the agent was handed for the re-run turn: no trace of the replaced message and of its answer.
                f.eventsSeenByRuns.last().texts() shouldBe listOf("one", "reply to one", "two, edited")
                // Same for what its tools read through the provider of the case events.
                f.providerSnapshots.last().texts() shouldBe listOf("one", "reply to one", "two, edited")
            } finally {
                f.service.shutdown()
            }
        }

        "chained re-runs: the second one replaces the first" {
            val f = RerunFixture()
            try {
                f.send("one")
                f.send("two")
                val two = f.userMessages().last()
                f.send("two v2", rerunOf = two.id)
                val v2 = f.userMessages().last()

                f.send("two v3", rerunOf = v2.id)

                f.eventsSeenByRuns.last().texts() shouldBe listOf("one", "reply to one", "two v3")
            } finally {
                f.service.shutdown()
            }
        }

        "re-running the first message restarts the conversation from scratch" {
            val f = RerunFixture()
            try {
                f.send("one")
                f.send("two")

                f.send("one, edited", rerunOf = f.userMessages().first().id)

                f.eventsSeenByRuns.last().texts() shouldBe listOf("one, edited")
            } finally {
                f.service.shutdown()
            }
        }

        "the re-run is rehydrated the same way: a runtime rebuilt from the log sees the effective conversation" {
            val f = RerunFixture()
            try {
                f.send("one")
                f.send("two")
                f.send("two, edited", rerunOf = f.userMessages().last().id)

                f.service.getCaseRuntime(f.caseId).conversationSnapshot().texts() shouldBe
                    listOf("one", "reply to one", "two, edited", "reply to two, edited")
            } finally {
                f.service.shutdown()
            }
        }

        "the non-text parts of the replaced message are kept, only the text is replaced" {
            val f = RerunFixture()
            try {
                val audio = MessageContent.Audio(path = "voice-messages/v.webm", mimeType = "audio/webm", durationMs = 1000)
                f.send(listOf(audio, MessageContent.Text("transcript")))

                f.send("fixed transcript", rerunOf = f.userMessages().single().id)

                // addMessage stores the text first, then the non-text parts: the re-run follows the same rule.
                f.userMessages().last().content shouldContainExactly listOf(MessageContent.Text("fixed transcript"), audio)
            } finally {
                f.service.shutdown()
            }
        }

        "a re-run is refused while a run is in flight" {
            val f = RerunFixture()
            try {
                f.send("one")
                val one = f.userMessages().single()
                f.hold()
                f.sendWithoutWaiting("two")
                f.awaitRunning()

                shouldThrow<ConflictException> { f.service.addMessage(f.caseId, f.author, listOf(MessageContent.Text("x")), rerunOf = one.id) }

                f.release()
                f.awaitIdleRelease()
                f.userMessages().none { it.rerunOf != null } shouldBe true
            } finally {
                f.release()
                f.service.shutdown()
            }
        }

        "only the author of a message can re-run it" {
            val f = RerunFixture()
            try {
                f.send("one")
                val stranger = Actor(UUID.randomUUID().toString(), "Bob", ActorRole.USER)

                shouldThrow<AccessDeniedException> {
                    f.service.addMessage(f.caseId, stranger, listOf(MessageContent.Text("mine now")), rerunOf = f.userMessages().single().id)
                }

                f.userMessages().size shouldBe 1
            } finally {
                f.service.shutdown()
            }
        }

        "the target must be a user message: an agent message, an unknown id and an event of another case are refused" {
            val f = RerunFixture()
            val other = RerunFixture()
            try {
                f.send("one")
                other.send("elsewhere")
                val agentMessage = f.events.findByParent(f.caseId).filterIsInstance<MessageEvent>().first { it.actor.role == ActorRole.AGENT }
                val ofOtherCase = other.userMessages().single()

                listOf(agentMessage.id, UUID.randomUUID(), ofOtherCase.id).forEach { target ->
                    shouldThrow<BadRequestException> {
                        f.service.addMessage(f.caseId, f.author, listOf(MessageContent.Text("x")), rerunOf = target)
                    }
                }
            } finally {
                f.service.shutdown()
                other.service.shutdown()
            }
        }

        "rerunOf and answerToEventId are mutually exclusive" {
            val f = RerunFixture()
            try {
                f.send("one")

                shouldThrow<BadRequestException> {
                    f.service.addMessage(
                        f.caseId,
                        f.author,
                        listOf(MessageContent.Text("x")),
                        answerToEventId = UUID.randomUUID(),
                        rerunOf = f.userMessages().single().id,
                    )
                }
            } finally {
                f.service.shutdown()
            }
        }

        "a message that is no longer in the conversation cannot be re-run" {
            val f = RerunFixture()
            try {
                f.send("one")
                f.send("two")
                val two = f.userMessages().last()
                f.send("two, edited", rerunOf = two.id)

                shouldThrow<ConflictException> {
                    f.service.addMessage(f.caseId, f.author, listOf(MessageContent.Text("again")), rerunOf = two.id)
                }
            } finally {
                f.service.shutdown()
            }
        }

        "the text of a re-run cannot be empty" {
            val f = RerunFixture()
            try {
                f.send("one")

                shouldThrow<BadRequestException> {
                    f.service.addMessage(f.caseId, f.author, listOf(MessageContent.Text("   ")), rerunOf = f.userMessages().single().id)
                }
            } finally {
                f.service.shutdown()
            }
        }
    })

/**
 * Real [CaseServiceImpl] on in-memory stores. The agent answers "reply to <last user text>" and records what it
 * is handed (the events of the run, and what the provider of the case events returns when it finishes).
 */
private class RerunFixture {
    private val namespaceId = UUID.randomUUID()
    private val userId = UUID.randomUUID()
    private val agentName = "rerun-agent"
    private val agentId = UUID.randomUUID()
    val events: CaseEventService = CaseEventServiceImpl(InMemoryCaseEventRepository())
    val eventsSeenByRuns = CopyOnWriteArrayList<List<CaseEvent>>()
    val providerSnapshots = CopyOnWriteArrayList<List<CaseEvent>>()
    val author = Actor(userId.toString(), "Rerun User", ActorRole.USER)

    @Volatile
    private var gate: CompletableDeferred<Unit> = CompletableDeferred<Unit>().also { it.complete(Unit) }

    private val agents =
        mockk<AgentService> {
            every { resolveAgentName(any(), any(), any()) } returns agentName
            coEvery { findAgentByName(agentName, any(), any()) } answers {
                val context = secondArg<AgentExecutionContext>()
                object : Agent {
                    override val metadata = EntityMetadata(id = agentId)
                    override val name = agentName
                    override val llmProvider = "deterministic-provider"
                    override val llmModel = "deterministic-model"

                    override fun run(
                        events: List<CaseEvent>,
                        shouldContinue: () -> Boolean,
                    ): Flow<CaseEvent> =
                        flow {
                            eventsSeenByRuns.add(events)
                            gate.await()
                            providerSnapshots.add(context.caseEventsProvider())
                            val lastUserText =
                                events
                                    .filterIsInstance<MessageEvent>()
                                    .last { it.actor.role == ActorRole.USER }
                                    .content
                                    .filterIsInstance<MessageContent.Text>()
                                    .joinToString { it.content }
                            emit(
                                MessageEvent(
                                    namespaceId = namespaceId,
                                    caseId = checkNotNull(context.caseId),
                                    actor = Actor(agentId.toString(), agentName, ActorRole.AGENT),
                                    content = listOf(MessageContent.Text("reply to $lastUserText")),
                                ),
                            )
                            emit(
                                AgentFinishedEvent(
                                    namespaceId = namespaceId,
                                    caseId = checkNotNull(context.caseId),
                                    agentId = agentId,
                                    agentName = agentName,
                                ),
                            )
                        }
                }
            }
        }
    private val user = User(metadata = EntityMetadata(id = userId), externalId = "rerun-user", email = "rerun@example.test")
    val service =
        CaseServiceImpl(
            agentService = agents,
            agentConfigService =
                mockk<AgentConfigService> {
                    every { findDeployedByNamespaceIdAndUserIdAndName(any(), any(), any()) } returns
                        listOf(AgentConfig(namespaceId = namespaceId, name = agentName))
                },
            agentConfigProperties = AgentConfigProperties(),
            caseRepository = InMemoryCaseRepository(),
            caseEventService = events,
            userService =
                mockk<UserService> {
                    every { findById(userId) } returns user
                    every { getById(userId) } returns user
                },
            namespaceService =
                mockk<NamespaceService> {
                    every { findById(namespaceId) } returns
                        Namespace(metadata = EntityMetadata(id = namespaceId), name = "rerun", defaultAgentName = agentName)
                    every { resolveRunCostThreshold(namespaceId) } returns null
                },
            caseConfig = CaseConfigProperties(),
            permissionService = mockk(relaxed = true),
            promptService = mockk(relaxed = true),
            caseNamingService = mockk(relaxed = true),
            limitsConfig = LimitsConfigProperties(),
            usageRecordService = mockk(relaxed = true),
            usageConfig = UsageConfigProperties(enabled = false),
            caseListUpdateBroadcaster = mockk(relaxed = true),
        )
    private val case = service.create(Case(namespaceId = namespaceId))
    val caseId: UUID = case.id

    fun userMessages(): List<MessageEvent> =
        events.findByParent(caseId).filterIsInstance<MessageEvent>().filter { it.actor.role == ActorRole.USER }

    fun hold() {
        gate = CompletableDeferred()
    }

    fun release() {
        gate.complete(Unit)
    }

    suspend fun awaitRunning() {
        val runtime = service.getCaseRuntime(caseId)
        withTimeout(8_000) { while (!runtime.isRunning()) delay(5) }
    }

    /** IDLE is published before run() lets go of the runtime: wait for the release too, like the other specs. */
    suspend fun awaitIdleRelease() {
        val runtime = service.getCaseRuntime(caseId)
        withTimeout(8_000) { while (runtime.isRunning()) delay(5) }
    }

    suspend fun send(
        text: String,
        rerunOf: UUID? = null,
    ) = send(listOf(MessageContent.Text(text)), rerunOf)

    suspend fun send(
        content: List<MessageContent>,
        rerunOf: UUID? = null,
    ) = coroutineScope {
        val runtime = service.getCaseRuntime(caseId)
        val idle =
            async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(8_000) {
                    runtime.events.filterIsInstance<CaseStatusEvent>().first { it.status == CaseStatus.IDLE }
                }
            }
        runtime.subscriptionCount.first { it >= 1 }
        service.addMessage(caseId, author, content, rerunOf = rerunOf)
        idle.await()
        awaitIdleRelease()
    }

    fun sendWithoutWaiting(text: String) {
        service.addMessage(caseId, author, listOf(MessageContent.Text(text)))
    }
}
