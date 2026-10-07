package io.whozoss.factory.agentattempt

import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.factory.Neo4jDomainIntegrationTest
import io.whozoss.factory.agentattempt.domain.AgentAttemptStatus
import io.whozoss.factory.agentattempt.domain.AgentStepAttemptRecord
import io.whozoss.factory.agentattempt.domain.AgentStepResultCapabilityIdentity
import io.whozoss.factory.agentattempt.domain.QuestionAlreadyAnsweredException
import io.whozoss.factory.agentattempt.domain.toDto
import io.whozoss.factory.agentattempt.persistence.AgentStepAttemptRepository
import io.whozoss.factory.agentattempt.persistence.SpringDataNeo4jOutboxRepository
import io.whozoss.factory.agentattempt.service.AgentStepQuestionService
import io.whozoss.factory.agentattempt.service.AgentStepResultService
import io.whozoss.factory.agentattempt.service.DurableAgentAttemptService
import io.whozoss.factory.capability.CapabilityExecutionService
import io.whozoss.factory.error.RevisionConflictException
import io.whozoss.factory.workflow.persistence.HumanInteractionRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * Embedded-Neo4j integration tests of [AgentStepQuestionService] (Phase 4
 * ask-step-question).
 *
 * Proves against the real repositories that:
 *  1. a worker step question transitions the durable attempt to
 *     `waiting_human` and persists the linked `agent_question` interaction;
 *  2. the audited human answer supersedes attempt N and registers attempt N+1
 *     with the bounded resumption context;
 *  3. answering twice is rejected without ever creating an attempt N+2
 *     (double-unblock protection).
 *
 * Extends [Neo4jDomainIntegrationTest]: the engine is the in-process Neo4j
 * test harness, so no Docker is required.
 */
class AgentStepQuestionServiceIntegrationTest : Neo4jDomainIntegrationTest() {

    @Autowired
    private lateinit var questionService: AgentStepQuestionService

    @Autowired
    private lateinit var resultService: AgentStepResultService

    @Autowired
    private lateinit var attemptService: DurableAgentAttemptService

    @Autowired
    private lateinit var legacyAttempts: AgentStepAttemptRepository

    @Autowired
    private lateinit var interactions: HumanInteractionRepository

    @Autowired
    private lateinit var outboxNodes: SpringDataNeo4jOutboxRepository

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    private val namespace = "ns-q4"
    private val workflow = "wf-q4"
    private val step = "step-q4"
    private val caseId = "case-q4"
    private val agentName = "Worker"
    private val attemptId = "attempt-q4"

    @Test
    fun `a worker question parks the attempt, the answer supersedes it and resumes as attempt N+1, exactly once`() {
        val token = seedRunningAttemptWithCapability()

        // 1) The worker asks: the attempt parks in waiting_human and the durable
        //    interaction is persisted with every link, in the same operation.
        val asked = questionService.ask(
            scope, token, attemptId,
            objectMapper.readTree("""{"prompt":"Proceed?","type":"SINGLE_CHOICE","options":["yes","no"],"contextHash":"sha256:q1"}"""),
            caseId, agentName, null,
        )
        assertThat(asked.status).isEqualTo("waiting_human")
        assertThat(asked.idempotent).isFalse()

        val waiting = attemptService.find(scope, namespace, workflow, step, attemptId)!!
        assertThat(waiting.status).isEqualTo(AgentAttemptStatus.WAITING_HUMAN)
        val interaction = interactions.find(scope, namespace, workflow, asked.interactionId)!!
        assertThat(interaction.interactionType).isEqualTo("agent_question")
        assertThat(interaction.status).isEqualTo("waiting")
        assertThat(interaction.payload["attemptId"]).isEqualTo(attemptId)
        assertThat(interaction.payload["prompt"]).isEqualTo("Proceed?")
        assertThat(interaction.payload["questionType"]).isEqualTo("SINGLE_CHOICE")
        assertThat(interaction.revision).isEqualTo(waiting.revision)

        // 2) The human answers: audited, revision-safe, single-use.
        val answered = questionService.answer(
            scope, namespace, workflow, asked.interactionId, interaction.revision, "yes", "alice",
        )
        val successorId = CapabilityExecutionService.retryAttemptId(workflow, step, 2)
        assertThat(answered.supersededAttemptId).isEqualTo(attemptId)
        assertThat(answered.successorAttemptId).isEqualTo(successorId)
        assertThat(answered.successorAttemptNumber).isEqualTo(2)

        // Attempt N is terminally superseded and immutable, with the journal
        // entry of the landed waiting_human -> superseded transition.
        val superseded = attemptService.find(scope, namespace, workflow, step, attemptId)!!
        assertThat(superseded.status).isEqualTo(AgentAttemptStatus.SUPERSEDED)
        assertThat(superseded.status.terminal).isTrue()
        assertThat(superseded.completedAt).isNotNull()
        val journal = attemptService.journal(scope, namespace, workflow, step, attemptId)
        assertThat(journal.last().fromStatus).isEqualTo(AgentAttemptStatus.WAITING_HUMAN)
        assertThat(journal.last().toStatus).isEqualTo(AgentAttemptStatus.SUPERSEDED)
        assertThatThrownBy {
            attemptService.requestCancel(scope, namespace, workflow, step, attemptId)
        }.hasMessageContaining("terminal")

        // The interaction is closed with the audited answer and the N -> N+1 link.
        val closed = interactions.find(scope, namespace, workflow, asked.interactionId)!!
        assertThat(closed.status).isEqualTo("closed")
        assertThat(closed.payload["answer"]).isEqualTo("yes")
        assertThat(closed.payload["actorId"]).isEqualTo("alice")
        assertThat(closed.payload["successorAttemptId"]).isEqualTo(successorId)
        val events = interactions.listEvents(scope, namespace, workflow)
        assertThat(events.map { it.eventType }).contains("agent_question_asked", "agent_question_answered")
        assertThat(events.last { it.eventType == "agent_question_answered" }.actorId).isEqualTo("alice")

        // Attempt N+1 is pending, unowned (the worker re-claims it for a fresh
        // lease) and carries the bounded resumption context — also exposed by
        // the Cockpit DTO.
        val successor = attemptService.find(scope, namespace, workflow, step, successorId)!!
        assertThat(successor.status).isEqualTo(AgentAttemptStatus.PENDING)
        assertThat(successor.attemptNumber).isEqualTo(2)
        assertThat(successor.ownerToken).isNull()
        val context = objectMapper.readTree(successor.resumptionContext)
        assertThat(context.path("question").asText()).isEqualTo("Proceed?")
        assertThat(context.path("answer").asText()).isEqualTo("yes")
        assertThat(context.path("actorId").asText()).isEqualTo("alice")
        assertThat(context.path("predecessorAttemptId").asText()).isEqualTo(attemptId)
        assertThat(context.path("predecessorInteractionId").asText()).isEqualTo(asked.interactionId)
        val dto = successor.toDto()
        assertThat(dto.resumptionContext).isEqualTo(successor.resumptionContext)
        assertThat(dto.status).isEqualTo("pending")

        // Only the ask notification is deferred. Answers no longer create an
        // undeliverable projection row targeting AgentOS core.
        val outbox = outboxNodes.findAllByOrganization(scope.organizationId)
        assertThat(outbox.map { it.eventType }).contains("agent_question_asked")
        assertThat(outbox.map { it.eventType }).doesNotContain("agent_question_answered")
        assertThat(outbox.all { it.status == "pending" }).isTrue()

        // 3) Double-unblock protection: a second answer is rejected and no
        //    attempt N+2 is ever created.
        assertThatThrownBy {
            questionService.answer(scope, namespace, workflow, asked.interactionId, closed.revision, "no", "alice")
        }.isInstanceOf(QuestionAlreadyAnsweredException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", "QUESTION_ALREADY_ANSWERED")
        assertThatThrownBy {
            questionService.answer(scope, namespace, workflow, asked.interactionId, interaction.revision, "no", "alice")
        }.isInstanceOf(QuestionAlreadyAnsweredException::class.java)
        assertThat(attemptService.findByWorkflow(scope, namespace, workflow).map { it.attemptId })
            .containsExactlyInAnyOrder(attemptId, successorId)
        assertThat(attemptService.nextAttemptNumber(scope, namespace, workflow, step)).isEqualTo(3)

        // A superseded attempt is invisible to the recovery sweep.
        assertThat(attemptService.findNonTerminal().map { it.attempt.attemptId }).doesNotContain(attemptId)
    }

    @Test
    fun `a stale interaction revision on the answer is a conflict`() {
        val token = seedRunningAttemptWithCapability()
        val asked = questionService.ask(
            scope, token, attemptId,
            objectMapper.readTree("""{"prompt":"Sure?","type":"FREE_TEXT","contextHash":"sha256:q2"}"""),
            caseId, agentName, null,
        )
        val interaction = interactions.find(scope, namespace, workflow, asked.interactionId)!!

        assertThatThrownBy {
            questionService.answer(scope, namespace, workflow, asked.interactionId, interaction.revision + 1, "yes", "alice")
        }.isInstanceOf(RevisionConflictException::class.java)
        // Nothing was mutated: the attempt still waits and no successor exists.
        assertThat(attemptService.find(scope, namespace, workflow, step, attemptId)!!.status)
            .isEqualTo(AgentAttemptStatus.WAITING_HUMAN)
        assertThat(attemptService.findByWorkflow(scope, namespace, workflow)).hasSize(1)
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    /**
     * Seeds the legacy result-path attempt row (required for capability
     * issuance) plus the durable execution attempt driven to `running`, then
     * issues the single-use capability. Returns the clear capability token.
     */
    private fun seedRunningAttemptWithCapability(): String {
        legacyAttempts.insert(
            scope,
            AgentStepAttemptRecord(namespace, workflow, step, attemptId, "agent-1", "running", 1, "{}"),
        )
        attemptService.register(
            scope,
            io.whozoss.factory.agentattempt.domain.DurableAgentAttempt(
                attemptId = attemptId,
                caseId = caseId,
                namespaceId = namespace,
                workflowId = workflow,
                stepId = step,
                attemptNumber = 1,
                agentName = agentName,
                brief = "the-turn-brief",
            ),
        )
        attemptService.claim(scope, namespace, workflow, step, attemptId, "owner-1", leaseTtlMs = 60_000)
        attemptService.transition(scope, namespace, workflow, step, attemptId, "owner-1", AgentAttemptStatus.STARTING)
        attemptService.transition(scope, namespace, workflow, step, attemptId, "owner-1", AgentAttemptStatus.RUNNING)
        return resultService.issue(
            scope,
            AgentStepResultCapabilityIdentity(
                attemptId = attemptId,
                workflowId = workflow,
                stepId = step,
                namespaceId = namespace,
                caseId = caseId,
                agentName = agentName,
                briefHash = "sha256:${"d".repeat(64)}",
            ),
        ).token
    }
}
