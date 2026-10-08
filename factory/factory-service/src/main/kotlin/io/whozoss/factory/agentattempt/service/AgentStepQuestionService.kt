package io.whozoss.factory.agentattempt.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.factory.agentattempt.domain.AgentAttemptStatus
import io.whozoss.factory.agentattempt.domain.AgentStepResultCapability
import io.whozoss.factory.agentattempt.domain.DurableAgentAttempt
import io.whozoss.factory.agentattempt.domain.QuestionAlreadyAnsweredException
import io.whozoss.factory.agentattempt.domain.QuestionAlreadyAskedException
import io.whozoss.factory.agentattempt.domain.QuestionAnswerInvalidException
import io.whozoss.factory.agentattempt.domain.QuestionAttemptNotWaitableException
import io.whozoss.factory.agentattempt.domain.QuestionInteractionNotFoundException
import io.whozoss.factory.agentattempt.domain.QuestionInteractionStaleException
import io.whozoss.factory.agentattempt.domain.QuestionSchemaInvalidException
import io.whozoss.factory.agentattempt.domain.QuestionSupersedeConflictException
import io.whozoss.factory.agentattempt.domain.ResultCapabilityExpiredException
import io.whozoss.factory.agentattempt.domain.ResultCapabilityInvalidException
import io.whozoss.factory.agentattempt.domain.ResultIdentityMismatchException
import io.whozoss.factory.agentattempt.domain.StepQuestion
import io.whozoss.factory.agentattempt.domain.StepQuestionLimits
import io.whozoss.factory.agentattempt.domain.StepQuestionType
import io.whozoss.factory.agentattempt.domain.StepQuestionValidation
import io.whozoss.factory.agentattempt.persistence.OutboxEventNode
import io.whozoss.factory.agentattempt.persistence.SpringDataNeo4jOutboxRepository
import io.whozoss.factory.capability.CapabilityExecutionService
import io.whozoss.factory.error.RevisionConflictException
import io.whozoss.factory.persistence.TenantScope
import io.whozoss.factory.workflow.domain.HumanInteractionEventRecord
import io.whozoss.factory.workflow.domain.HumanInteractionRecord
import io.whozoss.factory.workflow.persistence.HumanInteractionRepository
import io.whozoss.factory.workflow.sse.WorkflowSseHub
import mu.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID

/** Canonical response payload of a capability-backed step question. */
data class AgentStepQuestionAsked(
    val attemptId: String,
    val interactionId: String,
    val status: String,
    val idempotent: Boolean,
    val revision: Int,
    val workflowId: String,
)

/** Canonical response payload of a human answer to a step question. */
data class AgentStepQuestionAnswered(
    val interactionId: String,
    val status: String,
    val supersededAttemptId: String,
    val successorAttemptId: String,
    val successorAttemptNumber: Int,
)

/**
 * Application service of the Phase 4 ask-step-question channel.
 *
 * ## Design decision (Requirement 2)
 * This is a DEDICATED channel — a capability-bound
 * `POST /api/factory/agent-step-questions` endpoint served here, plus the
 * `FACTORY_WORKER__ask_step_question` worker tool — deliberately NOT an overload of
 * the structured step result. A `PASS`/`FAIL` result is a terminal business
 * verdict guarded by a single-use capability budget; asking a question is not
 * a verdict and must neither consume the result capability nor terminalize
 * the attempt. [io.whozoss.factory.agentattempt.domain.AgentStepResultStatus]
 * therefore keeps exactly `{PASS, FAIL}` and no `WAITING_HUMAN` member exists
 * on the result contract.
 *
 * ## Ask (worker → Factory)
 * [ask] resolves the SAME step-result capability the worker holds (read-only,
 * never redeemed), verifies it binds to the declared attempt, then — in ONE
 * transaction — transitions attempt `N` to `waiting_human` (fenced on the
 * durable lease owner, never on model input) and persists a durable
 * `agent_question` [HumanInteractionRecord] linked by
 * `interactionId`/`attemptId`/`stepId`/`workflowId`/`namespaceId`. The worker
 * call returns as soon as the question is durably recorded: nothing is
 * awaited in memory. Replay safety is anchored on a deterministic
 * `interactionId` derived from `(attemptId, contextHash)` — a retried ask of
 * the same question collapses onto the same interaction.
 *
 * ## Answer (human → Factory)
 * [answer] is revision-safe, audited and single-use, in ONE transaction:
 *  1. CAS-close the interaction (`waiting` → `closed`, optimistic lock on the
 *     caller's `expectedRevision`) — a second answer finds `closed` and is
 *     rejected with `QUESTION_ALREADY_ANSWERED`, so execution unblocks
 *     EXACTLY ONCE and no attempt `N+2` can ever be created;
 *  2. supersede attempt `N` (terminal `superseded`, lease token rotated —
 *     NEVER reactivated or rewritten);
 *  3. register attempt `N+1` (`attemptNumber = N+1`, `pending`, NO carried-over
 *     owner token — the worker re-claims it through the normal path for a
 *     fresh lease) with a bounded resumption context (question + answer +
 *     audited actor + predecessor links).
 *
 * ## Identities
 * `attemptId`, `caseId`, `namespaceId` come from the verified capability
 * binding / `TrustContext`; `actorId` from the authenticated human principal.
 * None is ever read from model-authored prompt strings.
 *
 * ## Notifications
 * The ask notification is enqueued inside the transaction and a best-effort SSE hint is published
 * after commit. Delivery is NOT a correctness prerequisite: the durable
 * transitions are authoritative on their own.
 */
@Service
class AgentStepQuestionService(
    private val resultService: AgentStepResultService,
    private val attempts: DurableAgentAttemptService,
    private val interactions: HumanInteractionRepository,
    private val outbox: SpringDataNeo4jOutboxRepository,
    private val objectMapper: ObjectMapper,
    /**
     * Best-effort SSE hub. Optional so hand-assembled (restart-proof) stacks
     * can run the channel without the web tier; delivery is never a
     * correctness prerequisite.
     */
    private val sseHub: WorkflowSseHub? = null,
) {

    private val logger = KotlinLogging.logger {}

    // ------------------------------------------------------------------
    // Ask (worker)
    // ------------------------------------------------------------------

    /**
     * Durably record a worker step question and park the attempt in
     * `waiting_human`. The capability token is resolved READ-ONLY (the
     * single-use result capability is never redeemed by a question).
     *
     * @param observedNamespaceId the trusted namespace resolved at the HTTP
     *   boundary, when present; a divergence from the capability namespace is
     *   an identity mismatch, never a silent override.
     */
    @Transactional
    fun ask(
        scope: TenantScope,
        token: String,
        attemptId: String,
        questionNode: JsonNode?,
        observedCaseId: String?,
        observedAgentName: String?,
        observedNamespaceId: String?,
        now: Instant = Instant.now(),
    ): AgentStepQuestionAsked {
        val capability = resultService.resolveCapability(scope, token) ?: throw ResultCapabilityInvalidException()
        if (!Instant.parse(capability.expiresAt).isAfter(now)) throw ResultCapabilityExpiredException()
        fenceIdentity(capability, attemptId, observedCaseId, observedAgentName, observedNamespaceId)

        if (!StepQuestionValidation.validate(questionNode)) throw QuestionSchemaInvalidException()
        val question = StepQuestionValidation.parse(questionNode!!)
        if (question.expiresAt != null && !question.expiresAt.isAfter(now)) {
            throw QuestionSchemaInvalidException("'expiresAt' must be in the future")
        }

        val namespaceId = capability.namespaceId
        val workflowId = capability.workflowId
        val stepId = capability.stepId
        val attempt = attempts.find(scope, namespaceId, workflowId, stepId, capability.attemptId)
            ?: throw QuestionInteractionStaleException(
                "No durable attempt '${capability.attemptId}' exists for the capability",
            )
        val interactionId = deterministicInteractionId(attempt.attemptId, question.contextHash)
        val existing = interactions.find(scope, namespaceId, workflowId, interactionId)

        when {
            // Idempotent re-ask: the same question (same deterministic id) is
            // already durably recorded and still waiting — return it unchanged.
            attempt.status == AgentAttemptStatus.WAITING_HUMAN &&
                existing != null &&
                existing.interactionType == AGENT_QUESTION_INTERACTION_TYPE &&
                existing.payload["contextHash"] == question.contextHash ->
                return AgentStepQuestionAsked(attempt.attemptId, interactionId, WAITING_HUMAN_STATUS, idempotent = true, existing.revision, workflowId)

            attempt.status == AgentAttemptStatus.WAITING_HUMAN && existing != null ->
                throw QuestionAlreadyAskedException(
                    details = mapOf("attemptId" to attempt.attemptId, "interactionId" to interactionId),
                )

            attempt.status != AgentAttemptStatus.RUNNING ->
                throw QuestionAttemptNotWaitableException(
                    "Attempt '${attempt.attemptId}' is '${attempt.status.dbValue}', not 'running'",
                    details = mapOf("attemptId" to attempt.attemptId, "status" to attempt.status.dbValue),
                )
        }
        val ownerToken = attempt.ownerToken
            ?: throw QuestionAttemptNotWaitableException(
                "Attempt '${attempt.attemptId}' has no lease owner and cannot wait for a human",
                details = mapOf("attemptId" to attempt.attemptId),
            )

        // ONE logical operation: the attempt transition and the durable
        // interaction (+ its audit event + the deferred notification) commit
        // atomically in this transaction.
        val waiting = attempts.transition(
            scope, namespaceId, workflowId, stepId, attempt.attemptId, ownerToken,
            AgentAttemptStatus.WAITING_HUMAN, now = now,
        )
        val interaction = interactions.insert(
            scope,
            HumanInteractionRecord(
                interactionId = interactionId,
                namespaceId = namespaceId,
                workflowId = workflowId,
                stepId = stepId,
                interactionType = AGENT_QUESTION_INTERACTION_TYPE,
                status = INTERACTION_WAITING,
                revision = waiting.revision,
                payload = questionPayload(attempt, question, capability.caseId),
            ),
        )
        interactions.appendEvent(
            scope,
            namespaceId,
            workflowId,
            HumanInteractionEventRecord(
                eventId = UUID.randomUUID().toString(),
                interactionId = interactionId,
                eventType = AGENT_QUESTION_ASKED,
                actorId = FACTORY_WORKER_ACTOR,
                payload = mapOf(
                    "attemptId" to attempt.attemptId,
                    "contextHash" to question.contextHash,
                    "recipientRole" to question.recipientRole,
                ),
            ),
        )
        enqueueOutbox(
            scope,
            AGENT_QUESTION_ASKED,
            mapOf(
                "attemptId" to attempt.attemptId,
                "interactionId" to interactionId,
                "namespaceId" to namespaceId,
                "workflowId" to workflowId,
                "stepId" to stepId,
                "caseId" to capability.caseId,
            ),
            now,
        )
        sseHub?.publish(
            scope,
            namespaceId,
            mapOf("workflowId" to workflowId, "namespaceId" to namespaceId, "interactionId" to interactionId),
        )
        logger.info { "Step question $interactionId recorded; attempt '${attempt.attemptId}' waits for a human" }
        return AgentStepQuestionAsked(attempt.attemptId, interaction.interactionId, WAITING_HUMAN_STATUS, idempotent = false, interaction.revision, workflowId)
    }

    // ------------------------------------------------------------------
    // Answer (human)
    // ------------------------------------------------------------------

    /**
     * Record the audited human answer, supersede attempt `N` and register the
     * resumption attempt `N+1` — atomically, revision-safe and single-use.
     */
    @Transactional
    fun answer(
        scope: TenantScope,
        legacyNamespaceId: String?,
        workflowId: String,
        interactionId: String,
        expectedRevision: Int,
        answer: String,
        actorId: String,
        now: Instant = Instant.now(),
    ): AgentStepQuestionAnswered {
        if (actorId.isBlank()) throw QuestionAnswerInvalidException("An authenticated actorId is required")
        val interaction = interactions.findOpenByWorkflowAndId(scope, workflowId, interactionId)
            ?.takeIf { it.interactionType == AGENT_QUESTION_INTERACTION_TYPE }
            ?: throw QuestionInteractionNotFoundException(
                details = mapOf("workflowId" to workflowId, "interactionId" to interactionId),
            )
        if (interaction.status != INTERACTION_WAITING) {
            throw QuestionAlreadyAnsweredException(
                details = mapOf("interactionId" to interactionId, "status" to interaction.status),
            )
        }
        if (expectedRevision != interaction.revision) {
            throw RevisionConflictException(
                "Interaction '$interactionId' is at revision ${interaction.revision}, expected $expectedRevision",
                details = mapOf(
                    "interactionId" to interactionId,
                    "currentRevision" to interaction.revision,
                    "expectedRevision" to expectedRevision,
                ),
            )
        }
        val namespaceId = interaction.namespaceId
        if (legacyNamespaceId != null && legacyNamespaceId != namespaceId) {
            logger.warn { "Ignoring untrusted namespace '$legacyNamespaceId' for interaction '$interactionId'; authoritative namespace is '$namespaceId'" }
        }
        val authoritativeWorkflowId = interaction.workflowId
        val normalized = validateAnswer(interaction, answer)
        val stepId = interaction.stepId
        val predecessorAttemptId = interaction.payload["attemptId"] as? String
            ?: throw QuestionInteractionStaleException(
                "The question interaction carries no attemptId",
                details = mapOf("interactionId" to interactionId),
            )
        val predecessor = attempts.find(scope, namespaceId, authoritativeWorkflowId, stepId, predecessorAttemptId)
            ?: throw QuestionInteractionStaleException(
                "No durable attempt '$predecessorAttemptId' exists for the question",
                details = mapOf("interactionId" to interactionId, "attemptId" to predecessorAttemptId),
            )
        if (predecessor.status != AgentAttemptStatus.WAITING_HUMAN) {
            throw QuestionSupersedeConflictException(
                "Attempt '$predecessorAttemptId' is '${predecessor.status.dbValue}', not 'waiting_human'",
                details = mapOf("attemptId" to predecessorAttemptId, "status" to predecessor.status.dbValue),
            )
        }

        // Successor identity, derived BEFORE the mutation so the closed
        // interaction can carry the N -> N+1 link.
        val nextNumber = attempts.nextAttemptNumber(scope, namespaceId, authoritativeWorkflowId, stepId)
        val successorAttemptId = CapabilityExecutionService.retryAttemptId(authoritativeWorkflowId, stepId, nextNumber)
        val resumptionContext = resumptionContext(interaction, normalized, actorId, predecessorAttemptId, now)

        // ONE transaction: close the interaction (CAS — single-use), supersede
        // attempt N (terminal, immutable) and register attempt N+1 (pending,
        // no carried-over owner token). A second answer loses the interaction
        // CAS (or finds it closed) and can never create an attempt N+2.
        val closed = interactions.update(
            scope,
            namespaceId,
            authoritativeWorkflowId,
            interactionId,
            expectedRevision,
            interaction.copy(
                status = INTERACTION_CLOSED,
                revision = interaction.revision + 1,
                payload = interaction.payload + mapOf(
                    "answer" to normalized,
                    "actorId" to actorId,
                    "answeredAt" to now.toString(),
                    "successorAttemptId" to successorAttemptId,
                ),
            ),
        )
        if (!closed) {
            throw RevisionConflictException(
                "Interaction '$interactionId' is at a stale revision",
                details = mapOf("interactionId" to interactionId, "expectedRevision" to expectedRevision),
            )
        }
        attempts.supersede(scope, namespaceId, authoritativeWorkflowId, stepId, predecessorAttemptId, predecessor.revision, now)
        val successor = attempts.registerRetry(
            scope,
            DurableAgentAttempt(
                attemptId = successorAttemptId,
                caseId = predecessor.caseId,
                namespaceId = namespaceId,
                workflowId = authoritativeWorkflowId,
                stepId = stepId,
                attemptNumber = nextNumber,
                agentName = predecessor.agentName,
                capabilityToken = predecessor.capabilityToken,
                brief = predecessor.brief,
                environmentRef = predecessor.environmentRef,
                expectedEnvironmentRevision = predecessor.expectedEnvironmentRevision,
                resumptionContext = resumptionContext,
                // Lot B durable case family: a successor attempt continues the
                // SAME worktree / sub-case; it never changes the family.
                rootCaseId = predecessor.rootCaseId,
                parentCaseId = predecessor.parentCaseId,
            ),
            now,
        )
        interactions.appendEvent(
            scope,
            namespaceId,
            authoritativeWorkflowId,
            HumanInteractionEventRecord(
                eventId = UUID.randomUUID().toString(),
                interactionId = interactionId,
                eventType = AGENT_QUESTION_ANSWERED,
                actorId = actorId,
                payload = mapOf(
                    "attemptId" to predecessorAttemptId,
                    "successorAttemptId" to successorAttemptId,
                ),
            ),
        )
        // The answer audit record and successor attempt are authoritative.
        // No outbox event is emitted: the former sole consumer targeted a removed
        // AgentOS endpoint and would leave an undeliverable poison row.
        sseHub?.publish(
            scope,
            namespaceId,
            mapOf("workflowId" to authoritativeWorkflowId, "namespaceId" to namespaceId, "interactionId" to interactionId),
        )
        logger.info {
            "Step question $interactionId answered by $actorId; attempt '$predecessorAttemptId' superseded " +
                "by '${successor.attemptId}' (#${successor.attemptNumber})"
        }
        return AgentStepQuestionAnswered(
            interactionId = interactionId,
            status = INTERACTION_CLOSED,
            supersededAttemptId = predecessorAttemptId,
            successorAttemptId = successor.attemptId,
            successorAttemptNumber = successor.attemptNumber,
        )
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    /**
     * Identity fencing anchored on the capability: the declared attempt, case,
     * agent and trusted namespace must match the issued capability exactly.
     * `workflowId`/`stepId` are never caller-declared — they come from the
     * capability itself.
     */
    private fun fenceIdentity(
        capability: AgentStepResultCapability,
        attemptId: String,
        observedCaseId: String?,
        observedAgentName: String?,
        observedNamespaceId: String?,
    ) {
        if (attemptId != capability.attemptId ||
            (observedCaseId != null && observedCaseId != capability.caseId) ||
            (observedAgentName != null && observedAgentName != capability.agentName) ||
            (observedNamespaceId != null && observedNamespaceId != capability.namespaceId)
        ) {
            throw ResultIdentityMismatchException(
                details = mapOf(
                    "attemptId" to attemptId,
                    "capabilityAttemptId" to capability.attemptId,
                    "caseId" to observedCaseId,
                    "agentName" to observedAgentName,
                    "namespaceId" to observedNamespaceId,
                ),
            )
        }
    }

    /**
     * Validate the human answer against the question type recorded on the
     * interaction: bounded free text, and strict option membership for
     * `SINGLE_CHOICE`.
     */
    private fun validateAnswer(interaction: HumanInteractionRecord, answer: String): String {
        val normalized = answer.trim()
        if (normalized.isEmpty() || normalized.length > StepQuestionLimits.ANSWER) {
            throw QuestionAnswerInvalidException(
                "answer must contain 1 to ${StepQuestionLimits.ANSWER} characters",
            )
        }
        val questionType = interaction.payload["questionType"] as? String
        @Suppress("UNCHECKED_CAST")
        val options = (interaction.payload["options"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList()
        if (questionType == StepQuestionType.SINGLE_CHOICE.wire && normalized !in options) {
            throw QuestionAnswerInvalidException("answer must be one of the question options")
        }
        return normalized
    }

    /**
     * The bounded resumption context JSON persisted on attempt `N+1`: the
     * question, the audited answer/actor and the predecessor links. When the
     * [StepQuestionLimits.RESUMPTION_CONTEXT_BYTES] bound would be exceeded,
     * the options list is dropped first (it stays durably available on the
     * interaction payload).
     */
    private fun resumptionContext(
        interaction: HumanInteractionRecord,
        answer: String,
        actorId: String,
        predecessorAttemptId: String,
        now: Instant,
    ): String {
        fun build(includeOptions: Boolean): String {
            val node = objectMapper.createObjectNode().apply {
                put("question", interaction.payload["prompt"] as? String)
                put("questionType", interaction.payload["questionType"] as? String)
                if (includeOptions) {
                    @Suppress("UNCHECKED_CAST")
                    val options = interaction.payload["options"] as? List<*>
                    if (options != null) set<JsonNode>("options", objectMapper.valueToTree(options))
                }
                put("answer", answer)
                put("actorId", actorId)
                put("answeredAt", now.toString())
                put("predecessorAttemptId", predecessorAttemptId)
                put("predecessorInteractionId", interaction.interactionId)
            }
            return objectMapper.writeValueAsString(node)
        }

        val full = build(includeOptions = true)
        if (full.toByteArray(StandardCharsets.UTF_8).size <= StepQuestionLimits.RESUMPTION_CONTEXT_BYTES) return full
        val compact = build(includeOptions = false)
        require(compact.toByteArray(StandardCharsets.UTF_8).size <= StepQuestionLimits.RESUMPTION_CONTEXT_BYTES) {
            "The resumption context exceeds ${StepQuestionLimits.RESUMPTION_CONTEXT_BYTES} bytes"
        }
        return compact
    }

    private fun questionPayload(
        attempt: DurableAgentAttempt,
        question: StepQuestion,
        caseId: String,
    ): Map<String, Any?> = linkedMapOf(
        "attemptId" to attempt.attemptId,
        "stepId" to attempt.stepId,
        "caseId" to caseId,
        "prompt" to question.prompt,
        "questionType" to question.type.wire,
        "options" to question.options,
        "recipientRole" to question.recipientRole,
        "contextHash" to question.contextHash,
        "expiresAt" to question.expiresAt?.toString(),
    )

    /** Deferred notification: durable outbox event inside the transaction. */
    private fun enqueueOutbox(
        scope: TenantScope,
        eventType: String,
        attributes: Map<String, Any?>,
        now: Instant,
    ) {
        val eventId = UUID.randomUUID().toString()
        val payload = objectMapper.createObjectNode().apply {
            put("aggregateType", AGGREGATE_TYPE)
            attributes.forEach { (key, value) -> put(key, value?.toString()) }
        }
        outbox.save(
            OutboxEventNode(
                id = OutboxEventNode.compositeId(scope.organizationId, eventId),
                organizationId = scope.organizationId,
                eventId = eventId,
                workstreamId = scope.workstreamId,
                eventType = eventType,
                payload = objectMapper.writeValueAsString(payload),
                status = OUTBOX_PENDING,
                attempts = 0,
                createdAt = now,
            ),
        )
    }

    companion object {
        const val AGENT_QUESTION_INTERACTION_TYPE = "agent_question"
        const val AGENT_QUESTION_ASKED = "agent_question_asked"
        const val AGENT_QUESTION_ANSWERED = "agent_question_answered"
        const val INTERACTION_WAITING = "waiting"
        const val INTERACTION_CLOSED = "closed"
        const val WAITING_HUMAN_STATUS = "waiting_human"
        const val FACTORY_WORKER_ACTOR = "factory-worker"
        const val AGGREGATE_TYPE = "agent_step_question"
        const val OUTBOX_PENDING = "pending"

        /**
         * The deterministic interaction id of a step question: a name-based
         * UUID of `(attemptId, contextHash)`. A retried ask of the same
         * question collapses onto the same durable interaction (idempotent
         * replay); a different question yields a different id.
         */
        fun deterministicInteractionId(attemptId: String, contextHash: String): String =
            UUID.nameUUIDFromBytes(
                "factory-step-question|$attemptId|$contextHash".toByteArray(StandardCharsets.UTF_8),
            ).toString()

    }
}
