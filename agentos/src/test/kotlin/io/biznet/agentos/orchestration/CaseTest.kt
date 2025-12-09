package io.biznet.agentos.orchestration

import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.UUID
import java.util.concurrent.TimeUnit

@Timeout(value = 5, unit = TimeUnit.SECONDS)
class CaseTest {
    private val projectId = UUID.randomUUID()

    @Test
    fun `stop should update status to STOPPING and set stop flag`() =
        runBlocking {
            val services = FakeCaseServices()
            val case =
                Case(
                    projectId = projectId,
                    agentService = services.agentService,
                    caseService = services.caseService,
                    caseEventService = services.eventService,
                )

            // Collect emitted events
            val emittedEvents = mutableListOf<CaseEvent>()
            val job =
                launch {
                    case.events.take(1).toList(emittedEvents)
                }

            kotlinx.coroutines.delay(100)

            // Act
            case.stop()

            job.join()

            // Assert - Status event emitted
            assertEquals(1, emittedEvents.size)
            val statusEvent = emittedEvents[0] as CaseStatusEvent
            assertEquals(CaseStatus.STOPPING, statusEvent.status)
            assertEquals(case.id, statusEvent.caseId)
            assertEquals(projectId, statusEvent.projectId)

            // Assert - Case saved with new status
            val savedCase = services.caseService.savedCases.last()
            assertEquals(case.id, savedCase.id)
            assertEquals(projectId, savedCase.projectId)
            assertEquals(CaseStatus.STOPPING, savedCase.status)
        }

    @Test
    fun `save should call caseService with correct CaseModel`() {
        val services = FakeCaseServices()
        val caseId = UUID.randomUUID()
        val case =
            Case(
                id = caseId,
                projectId = projectId,
                agentService = services.agentService,
                caseService = services.caseService,
                caseEventService = services.eventService,
            )

        // Act
        case.save()

        // Assert
        assertEquals(1, services.caseService.savedCases.size)
        val savedCase = services.caseService.savedCases.first()
        assertEquals(caseId, savedCase.id)
        assertEquals(projectId, savedCase.projectId)
        assertEquals(CaseStatus.PENDING, savedCase.status)
    }

    @Test
    fun `updateStatus via stop should emit CaseStatusEvent and save case`() =
        runBlocking {
            val services = FakeCaseServices()
            val case =
                Case(
                    projectId = projectId,
                    agentService = services.agentService,
                    caseService = services.caseService,
                    caseEventService = services.eventService,
                )

            // Collect emitted events
            val emittedEvents = mutableListOf<CaseEvent>()
            val job =
                launch {
                    case.events.take(1).toList(emittedEvents)
                }

            kotlinx.coroutines.delay(100)

            // Act - stop() triggers updateStatus(STOPPING)
            case.stop()

            job.join()

            // Assert - Event emitted
            assertEquals(1, emittedEvents.size)
            assertTrue(emittedEvents[0] is CaseStatusEvent)
            val statusEvent = emittedEvents[0] as CaseStatusEvent
            assertEquals(CaseStatus.STOPPING, statusEvent.status)

            // Assert - Case saved
            assertTrue(services.caseService.savedCases.isNotEmpty())
            val savedCase = services.caseService.savedCases.last()
            assertEquals(CaseStatus.STOPPING, savedCase.status)
        }

    @Test
    fun `save should persist case without emitting events`() {
        val services = FakeCaseServices()
        val case =
            Case(
                projectId = projectId,
                agentService = services.agentService,
                caseService = services.caseService,
                caseEventService = services.eventService,
            )

        // Act - Multiple saves
        case.save()
        case.save()

        // Assert - Multiple saves recorded
        assertEquals(2, services.caseService.savedCases.size)

        // Assert - All saves have same status (PENDING, unchanged)
        assertTrue(services.caseService.savedCases.all { it.status == CaseStatus.PENDING })
    }

    @Test
    fun `addUserMessage with answerToEventId should create AnswerEvent`() =
        runBlocking {
            // Arrange
            val caseId = UUID.randomUUID()
            val agentId = UUID.randomUUID()
            val services = FakeCaseServices()

            val questionEvent =
                QuestionEvent(
                    projectId = projectId,
                    caseId = caseId,
                    agentId = agentId,
                    agentName = "TestAgent",
                    question = "Do you approve?",
                    options = listOf("Yes", "No"),
                )

            // Save the question event first
            val savedQuestion = services.eventService.save(questionEvent)

            val case =
                Case(
                    id = caseId,
                    projectId = projectId,
                    agentService = services.agentService,
                    caseService = services.caseService,
                    caseEventService = services.eventService,
                    inputEvents = listOf(savedQuestion),
                )

            val actor =
                Actor(
                    id = "user-1",
                    displayName = "Test User",
                    role = ActorRole.USER,
                )

            val content = listOf(MessageContent.Text("Yes"))

            // Act
            case.addUserMessage(actor, content, answerToEventId = savedQuestion.id)

            // Assert
            val savedAnswers =
                services.eventService.savedEvents
                    .filterIsInstance<AnswerEvent>()
                    .filter { it.questionId == savedQuestion.id }

            assertEquals(1, savedAnswers.size)
            val answer = savedAnswers.first()
            assertEquals(savedQuestion.id, answer.questionId)
            assertEquals("Yes", answer.answer)
            assertEquals(actor, answer.actor)
        }

    @Test
    fun `addUserMessage should extract answer text from multiple content items`() =
        runBlocking {
            // Arrange
            val caseId = UUID.randomUUID()
            val agentId = UUID.randomUUID()
            val services = FakeCaseServices()

            val questionEvent =
                QuestionEvent(
                    projectId = projectId,
                    caseId = caseId,
                    agentId = agentId,
                    agentName = "TestAgent",
                    question = "What is your name?",
                )

            val savedQuestion = services.eventService.save(questionEvent)

            val case =
                Case(
                    id = caseId,
                    projectId = projectId,
                    agentService = services.agentService,
                    caseService = services.caseService,
                    caseEventService = services.eventService,
                    inputEvents = listOf(savedQuestion),
                )

            val actor =
                Actor(
                    id = "user-1",
                    displayName = "Test User",
                    role = ActorRole.USER,
                )

            // Multiple text content items
            val content =
                listOf(
                    MessageContent.Text("My name is"),
                    MessageContent.Text("John Doe"),
                )

            // Act
            case.addUserMessage(actor, content, answerToEventId = savedQuestion.id)

            // Assert
            val savedAnswers =
                services.eventService.savedEvents
                    .filterIsInstance<AnswerEvent>()

            assertEquals(1, savedAnswers.size)
            val answer = savedAnswers.first()
            assertEquals("My name is John Doe", answer.answer)
        }

    // Note: The following tests for addUserMessage are skipped because addUserMessage() calls run()
    // which contains TODO() and would block. These tests will be enabled once run() is fully implemented.

    /*
    @Test
    fun `addUserMessage should create and emit MessageEvent without agent mention`() = runBlocking {
        val services = FakeCaseServices()
        val case = Case(
            projectId = projectId,
            agentService = services.agentService,
            caseService = services.caseService,
            caseEventService = services.eventService,
        )

        val actor = Actor(
            id = "user-1",
            displayName = "Test User",
            role = ActorRole.USER
        )
        val content = listOf(MessageContent.Text("Hello, how are you?"))

        // Note: addUserMessage calls run() which has TODOs and infinite loop
        // We can't test the full flow, but we can verify the event emission happens
        // by collecting events before run() blocks
        val emittedEvents = mutableListOf<CaseEvent>()
        val job = launch {
            case.events.take(2).toList(emittedEvents) // MessageEvent + StatusEvent from run()
        }

        kotlinx.coroutines.delay(100)

        // Act - This will try to run() but we'll collect events before it blocks
        launch {
            case.addUserMessage(actor, content)
        }

        // Wait a bit for events to be emitted before run() loop
        kotlinx.coroutines.delay(500)

        // Stop the case to exit run() loop
        case.stop()

        job.join()

        // Assert - MessageEvent was emitted
        val messageEvents = emittedEvents.filterIsInstance<MessageEvent>()
        assertTrue(messageEvents.isNotEmpty(), "Should have emitted at least one MessageEvent")

        val messageEvent = messageEvents.first()
        assertEquals(actor, messageEvent.actor)
        assertEquals(content, messageEvent.content)
        assertEquals(case.id, messageEvent.caseId)

        // Assert - Event was saved
        val savedEvents = services.eventService.savedEvents.filterIsInstance<MessageEvent>()
        assertTrue(savedEvents.isNotEmpty())
    }

    @Test
    fun `detectAgentSelection should emit AgentSelectedEvent when agent is found`() = runBlocking {
        val services = FakeCaseServices()

        // Register a test agent
        val testAgent = FakeAgent(
            metadata = EntityMetadata(id = UUID.randomUUID()),
            name = "TestAgent"
        )
        services.agentService.registerAgent(testAgent)

        val case = Case(
            projectId = projectId,
            agentService = services.agentService,
            caseService = services.caseService,
            caseEventService = services.eventService,
        )

        val actor = Actor(
            id = "user-1",
            displayName = "Test User",
            role = ActorRole.USER
        )
        val content = listOf(MessageContent.Text("@TestAgent please help me"))

        val emittedEvents = mutableListOf<CaseEvent>()
        val job = launch {
            // Expect: MessageEvent, AgentSelectedEvent, AgentRunningEvent, StatusEvent (RUNNING)
            case.events.take(4).toList(emittedEvents)
        }

        kotlinx.coroutines.delay(100)

        // Act
        launch {
            case.addUserMessage(actor, content)
        }

        kotlinx.coroutines.delay(500)
        case.stop()

        job.join()

        // Assert - AgentSelectedEvent was emitted
        val agentSelectedEvents = emittedEvents.filterIsInstance<AgentSelectedEvent>()
        assertTrue(agentSelectedEvents.isNotEmpty(), "Should have emitted AgentSelectedEvent")

        val agentSelectedEvent = agentSelectedEvents.first()
        assertEquals(testAgent.id, agentSelectedEvent.agentId)
        assertEquals(testAgent.name, agentSelectedEvent.agentName)
        assertEquals(case.id, agentSelectedEvent.caseId)

        // Assert - Event was saved
        val savedEvents = services.eventService.savedEvents.filterIsInstance<AgentSelectedEvent>()
        assertTrue(savedEvents.isNotEmpty())
    }

    @Test
    fun `detectAgentSelection should emit WarnEvent when agent is not found`() = runBlocking {
        val services = FakeCaseServices()
        // No agent registered

        val case = Case(
            projectId = projectId,
            agentService = services.agentService,
            caseService = services.caseService,
            caseEventService = services.eventService,
        )

        val actor = Actor(
            id = "user-1",
            displayName = "Test User",
            role = ActorRole.USER
        )
        val content = listOf(MessageContent.Text("@UnknownAgent please help"))

        val emittedEvents = mutableListOf<CaseEvent>()
        val job = launch {
            // Expect: MessageEvent, WarnEvent, StatusEvent (RUNNING)
            case.events.take(3).toList(emittedEvents)
        }

        kotlinx.coroutines.delay(100)

        // Act
        launch {
            case.addUserMessage(actor, content)
        }

        kotlinx.coroutines.delay(500)
        case.stop()

        job.join()

        // Assert - WarnEvent was emitted
        val warnEvents = emittedEvents.filterIsInstance<WarnEvent>()
        assertTrue(warnEvents.isNotEmpty(), "Should have emitted WarnEvent")

        val warnEvent = warnEvents.first()
        assertTrue(warnEvent.message.contains("UnknownAgent"))
        assertTrue(warnEvent.message.contains("not found"))
        assertEquals(case.id, warnEvent.caseId)

        // Assert - Event was saved
        val savedEvents = services.eventService.savedEvents.filterIsInstance<WarnEvent>()
        assertTrue(savedEvents.isNotEmpty())
    }

    @Test
    fun `detectAgentSelection should not emit extra events when no agent mention`() = runBlocking {
        val services = FakeCaseServices()
        val case = Case(
            projectId = projectId,
            agentService = services.agentService,
            caseService = services.caseService,
            caseEventService = services.eventService,
        )

        val actor = Actor(
            id = "user-1",
            displayName = "Test User",
            role = ActorRole.USER
        )
        val content = listOf(MessageContent.Text("Just a normal message"))

        val emittedEvents = mutableListOf<CaseEvent>()
        val job = launch {
            // Expect: MessageEvent, StatusEvent (RUNNING) - no agent-related events
            case.events.take(2).toList(emittedEvents)
        }

        kotlinx.coroutines.delay(100)

        // Act
        launch {
            case.addUserMessage(actor, content)
        }

        kotlinx.coroutines.delay(500)
        case.stop()

        job.join()

        // Assert - No AgentSelectedEvent or WarnEvent
        val agentSelectedEvents = emittedEvents.filterIsInstance<AgentSelectedEvent>()
        assertTrue(agentSelectedEvents.isEmpty(), "Should not emit AgentSelectedEvent")

        val warnEvents = emittedEvents.filterIsInstance<WarnEvent>()
        assertTrue(warnEvents.isEmpty(), "Should not emit WarnEvent")

        // Assert - Only MessageEvent and StatusEvent
        val messageEvents = emittedEvents.filterIsInstance<MessageEvent>()
        assertTrue(messageEvents.isNotEmpty())

        val statusEvents = emittedEvents.filterIsInstance<CaseStatusEvent>()
        assertTrue(statusEvents.isNotEmpty())
    }
     */
}

/**
 * Fake implementations of services for testing.
 * Simpler than mocking frameworks for straightforward test cases.
 */
class FakeCaseServices {
    val agentService = FakeAgentService()
    val caseService = FakeCaseService()
    val eventService = FakeCaseEventService()
}

class FakeAgentService : IAgentService {
    private val agents = mutableMapOf<String, IAgent>()

    override fun findAgentByName(namePart: String): IAgent =
        agents[namePart] ?: throw IllegalArgumentException("Agent not found: $namePart")

    override fun getDefaultAgent(): IAgent? = null

    override suspend fun cleanup() {
        // Fake cleanup - do nothing
    }

    override suspend fun kill() {
        // Fake kill - do nothing
    }

    fun registerAgent(agent: IAgent) {
        agents[agent.name] = agent
    }
}

class FakeCaseService : ICaseService {
    val savedCases = mutableListOf<CaseModel>()

    override fun save(entity: CaseModel): CaseModel {
        savedCases.add(entity)
        return entity
    }

    override fun findByIds(ids: Collection<UUID>): List<CaseModel> = emptyList()
    override fun findByParent(parentId: UUID): List<CaseModel> = emptyList()
    override fun deleteMany(ids: Collection<UUID>): Int = 0
    override fun createCaseInstance(projectId: UUID, initialEvents: List<CaseEvent>): Case =
        throw NotImplementedError()
    override fun getCaseInstance(caseId: UUID): Case? = null
    override fun getActiveCasesByProject(projectId: UUID): List<Case> = emptyList()
    override fun getAllActiveCases(): List<Case> = emptyList()
    override fun getCaseEventStream(caseId: UUID) = null
    override fun stopCase(caseId: UUID): Boolean = false
    override fun killCase(caseId: UUID): Boolean = false
}

class FakeCaseEventService : ICaseEventService {
    val savedEvents = mutableListOf<CaseEvent>()

    override fun save(entity: CaseEvent): CaseEvent {
        savedEvents.add(entity)
        return entity
    }

    override fun findByIds(ids: Collection<UUID>): List<CaseEvent> {
        return savedEvents.filter { it.id in ids }
    }

    override fun findByParent(parentId: UUID): List<CaseEvent> {
        return savedEvents.filter { it.caseId == parentId }.sortedBy { it.timestamp }
    }

    override fun deleteMany(ids: Collection<UUID>): Int {
        val toRemove = savedEvents.filter { it.id in ids }
        savedEvents.removeAll(toRemove)
        return toRemove.size
    }
}

class FakeAgent(
    override val metadata: EntityMetadata,
    override val name: String,
) : IAgent {
    val runCallCount = mutableListOf<List<CaseEvent>>()

    override fun run(events: List<CaseEvent>) =
        flow {
            runCallCount.add(events)
            // Fake agent emits a finished event
            emit(
                AgentFinishedEvent(
                    projectId = events.firstOrNull()?.projectId ?: UUID.randomUUID(),
                    caseId = events.firstOrNull()?.caseId ?: UUID.randomUUID(),
                    agentId = metadata.id,
                    agentName = name,
                ),
            )
        }
}
