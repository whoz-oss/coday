package io.whozoss.agentos.caseFlow

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.whozoss.agentos.exception.ConflictException
import io.whozoss.agentos.caseEvent.CaseConversationHistory
import io.whozoss.agentos.caseEvent.CaseEventServiceImpl
import io.whozoss.agentos.caseEvent.InMemoryCaseEventRepository
import io.whozoss.agentos.git.*
import io.whozoss.agentos.sdk.actor.Actor
import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.caseEvent.*
import java.nio.file.Path
import java.time.Instant
import java.util.Optional
import java.util.UUID

class CaseCommandJournalSpec : StringSpec({
    val mapper = jacksonObjectMapper().findAndRegisterModules()
    val ns = UUID.randomUUID()
    val caseId = UUID.randomUUID()
    val actor = Actor(UUID.randomUUID().toString(), "Alice", ActorRole.USER)
    val roots = mockk<ExchangeRootResolver> {
        every { resolve(caseId) } returns ResolvedExchangeRoot(Path.of("/tmp/workspace"), CaseResourceBinding(
            rootCaseId = caseId, namespaceId = ns, integrationConfigId = UUID.randomUUID(), status = CaseResourceStatus.READY,
        ))
    }
    fun repository(): CaseCommandReceiptRepository {
        val rows = mutableMapOf<String, CaseCommandReceipt>()
        return mockk {
            every { save(any<CaseCommandReceipt>()) } answers { firstArg<CaseCommandReceipt>().also { rows[it.id] = it } }
            every { existsById(any()) } answers { rows.containsKey(firstArg<String>()) }
            every { pendingCases() } answers { rows.values.filter { it.state in setOf("QUEUED", "WAITING") }.map { it.caseId }.distinct() }
            every { recordedCases() } answers { rows.values.map { it.caseId }.distinct() }
            every { findById(any()) } answers { Optional.ofNullable(rows[firstArg<String>()]) }
            every { forCase(any()) } answers { rows.values.filter { it.caseId == firstArg<String>() }.sortedBy { it.created } }
            every { hasState(any(), any()) } answers {
                val id = firstArg<String>(); val states = secondArg<Collection<String>>()
                rows.values.any { it.caseId == id && it.state in states }
            }
            every { firstInState(any(), any()) } answers {
                val id = firstArg<String>(); val states = secondArg<Collection<String>>()
                rows.values.filter { it.caseId == id && it.state in states }.minWithOrNull(compareBy<CaseCommandReceipt> { it.created }.thenBy { it.id })
            }
            every { transition(any(), any(), any()) } answers {
                val id = firstArg<String>(); val states = secondArg<Collection<String>>(); val target = thirdArg<String>()
                rows.replaceAll { _, row -> if (row.caseId == id && row.state in states) row.copy(state = target) else row }
            }
            every { recoverInterrupted() } answers { rows.replaceAll { _, value -> if (value.state == "STARTED") value.copy(state = "RECOVERY_REQUIRED") else value }; Unit }
        }
    }
    fun commands() = listOf(
        DurableCaseCommand(actor = actor, content = listOf(MessageContent.Text("expanded step 1")), sessionContext = mapOf("page" to "ticket")),
        DurableCaseCommand(actor = actor.copy(displayName = "Bob"), content = listOf(MessageContent.Text("expanded step 2")), sessionContext = mapOf("page" to "review")),
    )

    "expanded commands survive reconstruction with their original authors and context" {
        val repo = repository()
        val commands = commands()
        CaseCommandJournal(repo, mapper, roots).append(caseId, UUID.randomUUID(), "/workflow", commands)
        val resumed = CaseCommandJournal(repo, mapper, roots)
        resumed.next(caseId) shouldBe commands[0]
        resumed.complete(caseId, commands[0].id)
        resumed.next(caseId) shouldBe commands[1]
        resumed.complete(caseId, commands[1].id)
        resumed.next(caseId) shouldBe null
    }
    "a repeated HTTP receipt cannot enqueue the same work twice or change its payload" {
        val journal = CaseCommandJournal(repository(), mapper, roots)
        val id = UUID.randomUUID()
        journal.append(caseId, id, "/workflow", commands())
        journal.append(caseId, id, "/workflow", commands())
        journal.duplicate(caseId, id, "/workflow") shouldBe true
        shouldThrow<ConflictException> { journal.append(caseId, id, "different", commands()) }
        repeat(2) { journal.complete(caseId, journal.next(caseId)!!.id) }
        journal.hasPending(caseId) shouldBe false
    }
    "a command claimed before a crash is held for explicit recovery" {
        val repo = repository()
        val before = CaseCommandJournal(repo, mapper, roots)
        before.append(caseId, UUID.randomUUID(), "/workflow", commands())
        before.next(caseId)
        val after = CaseCommandJournal(repo, mapper, roots)
        after.recoverInterrupted()
        after.recoveryRequired(caseId) shouldBe true
        after.next(caseId) shouldBe null
        after.acknowledge(caseId)
        after.recoveryRequired(caseId) shouldBe false
        after.hasPending(caseId) shouldBe false
    }
    "kill cancels queued commands even before the worktree is ready" {
        val journal = CaseCommandJournal(repository(), mapper, roots)
        journal.append(caseId, UUID.randomUUID(), "/workflow", commands())
        journal.cancel(caseId)
        journal.next(caseId) shouldBe null
    }
    "runtime consumes the durable inbox one turn at a time" {
        val journal = CaseCommandJournal(repository(), mapper, roots)
        val commands = commands()
        journal.append(caseId, UUID.randomUUID(), "/workflow", commands)
        val messages = mutableListOf<MessageEvent>()
        val seen = mutableListOf<String>()
        lateinit var runtime: CaseRuntime
        runtime = CaseRuntime(
            id = caseId, namespaceId = ns, caseCreatedAt = Instant.now(),
            updateStatusCallback = { _, _ -> },
            storeEvent = { event -> if (event is MessageEvent) messages.add(event); event },
            selectAgent = { _, _ -> listOf(AgentSelectedEvent(namespaceId = ns, caseId = caseId, agentId = UUID.randomUUID(), agentName = "coder")) },
            isAgentAuthorized = { _, _ -> true },
            runAgent = { name, events, _, _, _ ->
                seen.add(events.filterIsInstance<MessageEvent>().last().actor.displayName)
                runtime.pushEvents(listOf(AgentFinishedEvent(namespaceId = ns, caseId = caseId, agentId = UUID.randomUUID(), agentName = name)))
            },
            commandJournal = journal,
        )
        runtime.run()
        seen shouldBe listOf("Alice", "Bob")
        messages.map { it.id } shouldBe commands.map { it.id }
        messages.map { it.sessionContext } shouldBe commands.map { it.sessionContext }
        journal.hasPending(caseId) shouldBe false
    }

    "a quick answer arriving before the questioning agent returns is consumed before the next command" {
        val journal = CaseCommandJournal(repository(), mapper, roots)
        journal.append(caseId, UUID.randomUUID(), "/workflow", commands())
        var calls = 0
        lateinit var runtime: CaseRuntime
        runtime = CaseRuntime(
            id = caseId, namespaceId = ns, caseCreatedAt = Instant.now(),
            updateStatusCallback = { _, _ -> }, storeEvent = { it },
            selectAgent = { _, _ -> listOf(AgentSelectedEvent(namespaceId = ns, caseId = caseId, agentId = UUID.randomUUID(), agentName = "coder")) },
            isAgentAuthorized = { _, _ -> true },
            runAgent = { name, events, _, _, _ ->
                calls++
                runtime.pushEvents(listOf(AgentFinishedEvent(namespaceId = ns, caseId = caseId, agentId = UUID.randomUUID(), agentName = name)))
                if (calls == 1) {
                    val question = QuestionEvent(namespaceId = ns, caseId = caseId, agentId = UUID.randomUUID(), agentName = name, question = "Proceed?")
                    runtime.pushEvents(listOf(question))
                    runtime.addUserMessage(actor, listOf(MessageContent.Text("Yes")), question.id)
                } else if (calls == 2) {
                    events.filterIsInstance<MessageEvent>().last().actor.displayName shouldBe "Alice"
                    events.filterIsInstance<AnswerEvent>().last().answer shouldBe "Yes"
                } else events.filterIsInstance<MessageEvent>().last().actor.displayName shouldBe "Bob"
            }, commandJournal = journal,
        )
        runtime.run()
        calls shouldBe 3
        journal.hasUnfinished(caseId) shouldBe false
    }
    "a persisted answer after WAITING is resumed on a reconstructed runtime" {
        val repo = repository()
        val journal = CaseCommandJournal(repo, mapper, roots)
        val command = commands().first()
        journal.append(caseId, UUID.randomUUID(), "/workflow", listOf(command))
        journal.next(caseId)
        journal.waitForAnswer(caseId)
        val question = QuestionEvent(namespaceId = ns, caseId = caseId, agentId = UUID.randomUUID(), agentName = "coder", question = "Proceed?")
        val stored = listOf<CaseEvent>(question, question.createAnswer(actor, "Yes"))
        val afterRestart = CaseCommandJournal(repo, mapper, roots)
        afterRestart.recoverInterrupted()
        afterRestart.pendingCases() shouldBe listOf(caseId)
        var calls = 0
        lateinit var runtime: CaseRuntime
        runtime = CaseRuntime(id = caseId, namespaceId = ns, caseCreatedAt = Instant.now(),
            updateStatusCallback = { _, _ -> }, storeEvent = { it },
            selectAgent = { _, _ -> emptyList() }, isAgentAuthorized = { _, _ -> true },
            runAgent = { name, _, _, _, _ -> calls++; runtime.pushEvents(listOf(AgentFinishedEvent(namespaceId = ns, caseId = caseId, agentId = question.agentId, agentName = name))) }, commandJournal = afterRestart)
        runtime.pushEvents(stored)
        runtime.hasAnsweredQuestion() shouldBe true
        runtime.run()
        calls shouldBe 1
        afterRestart.hasUnfinished(caseId) shouldBe false
    }
    "accepted input is durable conversation history before execution and after cancellation" {
        val repo = repository()
        val events = CaseEventServiceImpl(InMemoryCaseEventRepository())
        val journal = CaseCommandJournal(repo, mapper, roots)
        val command = commands().first()
        val accepted = journal.append(caseId, UUID.randomUUID(), "original", listOf(command))
        val history = CaseConversationHistory(events, journal)
        events.findByParent(caseId) shouldBe emptyList()
        history.findByCase(caseId) shouldBe listOf(accepted)
        accepted.id shouldBe command.id
        accepted.actor shouldBe command.actor
        accepted.sessionContext shouldBe command.sessionContext
        journal.cancel(caseId)
        CaseConversationHistory(events, CaseCommandJournal(repo, mapper, roots)).findByCase(caseId) shouldBe listOf(accepted)
        history.findByCase(UUID.randomUUID()) shouldBe emptyList()
        journal.next(caseId) shouldBe null
    }

    "materialized input replaces its receipt projection without entering earlier agent turns" {
        val repo = repository()
        val events = CaseEventServiceImpl(InMemoryCaseEventRepository())
        val journal = CaseCommandJournal(repo, mapper, roots)
        val commands = commands()
        val first = journal.append(caseId, UUID.randomUUID(), "first", listOf(commands[0]))
        val second = journal.append(caseId, UUID.randomUUID(), "second", listOf(commands[1]))
        val history = CaseConversationHistory(events, journal)
        history.findByCase(caseId).map { it.id } shouldBe listOf(first.id, second.id)
        var calls = 0
        lateinit var runtime: CaseRuntime
        runtime = CaseRuntime(
            id = caseId, namespaceId = ns, caseCreatedAt = Instant.now(),
            inputEvents = events.findByParent(caseId),
            updateStatusCallback = { _, _ -> }, storeEvent = events::create,
            selectAgent = { _, _ -> listOf(AgentSelectedEvent(namespaceId = ns, caseId = caseId, agentId = UUID.randomUUID(), agentName = "coder")) },
            isAgentAuthorized = { _, _ -> true },
            runAgent = { name, input, _, _, _ ->
                calls++
                input.filterIsInstance<MessageEvent>().map { it.id } shouldBe commands.take(calls).map { it.id }
                runtime.pushEvents(listOf(AgentFinishedEvent(namespaceId = ns, caseId = caseId, agentId = UUID.randomUUID(), agentName = name)))
            }, commandJournal = journal,
        )
        runtime.run()
        calls shouldBe 2
        val storedMessages = events.findByParent(caseId).filterIsInstance<MessageEvent>()
        val displayed = history.findByCase(caseId).filterIsInstance<MessageEvent>()
        displayed.map { it.id } shouldBe listOf(first.id, second.id)
        displayed.map { it.timestamp } shouldBe listOf(first.timestamp, second.timestamp)
        displayed.map { it.content } shouldBe storedMessages.map { it.content }
        storedMessages.map { it.id } shouldBe listOf(first.id, second.id)
        journal.receivedMessages(caseId, storedMessages.mapTo(mutableSetOf()) { it.id }) shouldBe emptyList()
    }

    "an expired OAuth question from a completed turn does not hold later commands" {
        val journal = CaseCommandJournal(repository(), mapper, roots)
        journal.append(caseId, UUID.randomUUID(), "workflow", commands())
        var calls = 0
        lateinit var runtime: CaseRuntime
        runtime = CaseRuntime(
            id = caseId, namespaceId = ns, caseCreatedAt = Instant.now(),
            updateStatusCallback = { _, _ -> }, storeEvent = { it },
            selectAgent = { _, _ -> listOf(AgentSelectedEvent(namespaceId = ns, caseId = caseId, agentId = UUID.randomUUID(), agentName = "coder")) },
            isAgentAuthorized = { _, _ -> true },
            runAgent = { name, _, _, _, _ ->
                calls++
                if (calls == 1) runtime.pushEvents(listOf(QuestionEvent(
                    namespaceId = ns, caseId = caseId, agentId = UUID.randomUUID(), agentName = name,
                    question = "https://example.test/oauth", questionType = QuestionType.OAUTH_AUTHORIZE,
                )))
                runtime.pushEvents(listOf(AgentFinishedEvent(namespaceId = ns, caseId = caseId, agentId = UUID.randomUUID(), agentName = name)))
            }, commandJournal = journal,
        )
        runtime.run()
        calls shouldBe 2
        journal.hasUnfinished(caseId) shouldBe false
        journal.isWaiting(caseId) shouldBe false
    }

})
