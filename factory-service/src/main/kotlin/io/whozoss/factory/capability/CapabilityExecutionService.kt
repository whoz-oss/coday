package io.whozoss.factory.capability

import io.whozoss.factory.adapter.agentos.AgentOsExecutionAdapter
import io.whozoss.factory.adapter.agentos.AgentOsExecutionVerdict
import io.whozoss.factory.agentattempt.domain.AgentAttemptStatus
import io.whozoss.factory.agentattempt.domain.AgentStepAttemptRecord
import io.whozoss.factory.agentattempt.domain.AgentStepResultCapabilityIdentity
import io.whozoss.factory.agentattempt.domain.AttemptClaimConflictException
import io.whozoss.factory.agentattempt.domain.CanonicalJsonHash
import io.whozoss.factory.agentattempt.domain.DurableAgentAttempt
import io.whozoss.factory.agentattempt.persistence.AgentStepAttemptRepository
import io.whozoss.factory.agentattempt.service.AgentStepResultService
import io.whozoss.factory.agentattempt.service.DurableAgentAttemptService
import io.whozoss.factory.persistence.TenantScope
import io.whozoss.factory.workflow.domain.HumanInteractionEventRecord
import io.whozoss.factory.workflow.domain.HumanInteractionRecord
import io.whozoss.factory.workflow.domain.ResponsibilityKind
import io.whozoss.factory.workflow.domain.WorkflowCodeTransitionRecord
import io.whozoss.factory.workflow.domain.WorkflowEvidenceItem
import io.whozoss.factory.workflow.domain.WorkflowStepDefinition
import io.whozoss.factory.workflow.persistence.HumanInteractionRepository
import io.whozoss.factory.workflow.persistence.WorkflowEvidenceRepository
import io.whozoss.factory.workflow.persistence.WorkflowRepository
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate

/**
 * A resolved capability plus the ids of the facts it recorded (if any).
 *
 *  - `code`  -> a `workflow_code_transitions` row and a `workflow_evidence` fact;
 *  - `human` -> a `human_interactions` checkpoint and its opening event;
 *  - `agent` -> an `agent_step_attempts` attempt and a `workflow_evidence` fact.
 */
data class CapabilityExecution(
    val outcome: CapabilityOutcome,
    val codeTransitionId: String? = null,
    val evidenceId: String? = null,
    val interactionId: String? = null,
    val attemptId: String? = null,
)

/**
 * Persistence boundary of capability resolution (W8.2 / W8.3).
 *
 * Delegates routing to [CapabilityResolver] and records the resulting facts:
 * the code verdict as a code transition + an evidence item, the human request as
 * an open `human_interactions` checkpoint, and the agent turn as an
 * `agent_step_attempts` lifecycle plus an evidence item. Code, human and agent
 * rows carry composite foreign keys to the workflow instance, so the caller must
 * have materialized the instance first (the DAG sequencer of W8.3 drives that).
 *
 * ## Transaction boundaries
 * [resolveAndRecord] is deliberately NOT transactional: the external execution it
 * drives (AgentOS HTTP proxy calls, local verification processes) must never run
 * inside a Neo4j transaction, otherwise a slow turn outlives the transaction
 * timeout and every subsequent write fails with
 * "Cannot run more queries in this transaction". Each persistence phase instead
 * runs in its own short `REQUIRES_NEW` transaction (see [newTransaction]):
 *  - Phase 1 claims the step (attempt reservation + capability issuance);
 *  - Phase 2 executes the capability with no active transaction;
 *  - Phase 3 terminalizes the attempt and records the evidence.
 * When no [transactionManager] is supplied (pure unit tests), the phases execute
 * inline and rely on the individual repository operations for durability.
 */
@Service
class CapabilityExecutionService(
    private val resolver: CapabilityResolver,
    private val workflowRepository: WorkflowRepository,
    private val evidenceRepository: WorkflowEvidenceRepository,
    private val interactionRepository: HumanInteractionRepository,
    private val attemptRepository: AgentStepAttemptRepository,
    /**
     * Optional issuer of the single-use result-submission capability. It is
     * injected in production; pure unit tests that only exercise the DAG
     * persistence boundary may leave it `null` (capability issuance is then
     * skipped and the turn runs without a delegate token).
     */
    private val agentStepResultService: AgentStepResultService? = null,
    /**
     * Optional transaction manager used to isolate each persistence phase in its
     * own short `REQUIRES_NEW` transaction. Injected in production; pure unit
     * tests may omit it and the phases then run inline.
     */
    private val transactionManager: PlatformTransactionManager? = null,
    /**
     * Optional durable-attempt service (Lot C). Together with
     * [agentOsExecutionAdapter] it activates the durable AgentOS bridge for
     * `agent` steps: a short claim transaction, the untransacted remote turn and
     * a short finalize transaction. When either is absent, the legacy polling
     * turn driver (see [resolveAgentViaPolling]) stays the active path.
     */
    private val durableAgentAttemptService: DurableAgentAttemptService? = null,
    /** Optional explicit Factory -> AgentOS execution boundary (SSE/reconcile). */
    private val agentOsExecutionAdapter: AgentOsExecutionAdapter? = null,
    /** Wall-clock observation budget handed to the adapter turn observer. */
    private val agentObservationTimeoutMs: Long = 600_000L,
    /** Lease TTL of the durable attempt claim (a live lease fences a competing worker). */
    private val agentLeaseTtlMs: Long? = 3_600_000L,
) {

    private val shortTransaction: TransactionTemplate? = transactionManager?.let { manager ->
        TransactionTemplate(manager).apply { propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW }
    }

    /** Runs [block] in a fresh short transaction, or inline when none is configured. */
    private fun <T : Any> newTransaction(block: () -> T): T {
        val template = shortTransaction ?: return block()
        return template.execute { block() }!!
    }

    /** The durable ids produced by the agent claim phase (Phase 1). */
    private data class AgentClaim(val attemptId: String, val capabilityToken: String?)

    fun resolveAndRecord(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        step: WorkflowStepDefinition,
        repoRoot: Path,
        ticket: String? = null,
    ): CapabilityExecution {
        return when (step.responsibility.kind) {
            ResponsibilityKind.AGENT -> resolveAgent(scope, namespaceId, workflowId, step, repoRoot, ticket)

            ResponsibilityKind.HUMAN -> resolveHuman(scope, namespaceId, workflowId, step, repoRoot)
            ResponsibilityKind.CODE -> resolveCode(scope, namespaceId, workflowId, step, repoRoot)
        }
    }

    /**
     * Code capability: external process execution (Phase 2) then a short
     * transaction recording the code transition + evidence (Phase 3).
     */
    private fun resolveCode(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        step: WorkflowStepDefinition,
        repoRoot: Path,
    ): CapabilityExecution {
        val outcome = resolver.resolve(step, repoRoot, namespaceId, workflowId)
        return when (outcome) {
            is CapabilityOutcome.CodeExecuted -> newTransaction { recordCode(scope, namespaceId, workflowId, step, outcome) }
            else -> CapabilityExecution(outcome)
        }
    }

    /** Human checkpoint: no external execution; the checkpoint is recorded in a short transaction. */
    private fun resolveHuman(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        step: WorkflowStepDefinition,
        repoRoot: Path,
    ): CapabilityExecution {
        val outcome = resolver.resolve(step, repoRoot, namespaceId, workflowId)
        return when (outcome) {
            is CapabilityOutcome.HumanCheckpointRequired ->
                newTransaction { recordHuman(scope, namespaceId, workflowId, step, outcome) }
            else -> CapabilityExecution(outcome)
        }
    }

    /** The optional ticket reaches the agent persona through the turn brief. */
    private fun briefFromTicket(ticket: String?): String? =
        ticket?.takeIf { it.isNotBlank() }?.let { "Execute this Factory session step for ticket $it." }

    /**
     * Builds the brief handed to the agent persona. It is assembled by Factory
     * code from the DURABLE outputs of the dependencies (the `agent-result`
     * evidence facts persisted when a step succeeded) — never from the last free
     * message of an upstream agent. An optional ticket prefixes the brief.
     */
    private fun buildBrief(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        step: WorkflowStepDefinition,
        ticket: String?,
    ): String {
        val base = briefFromTicket(ticket) ?: "Execute Factory session step '${step.id}'."
        if (step.dependsOn.isEmpty()) return base
        val evidence = evidenceRepository.list(scope, namespaceId, workflowId)
        val inputs = LinkedHashMap<String, Any?>()
        for (dependency in step.dependsOn) {
            val item = evidence.lastOrNull {
                it.stepId == dependency && it.kind == AGENT_RESULT_EVIDENCE_KIND && it.outcome == "pass"
            } ?: continue
            inputs[dependency] = item.facts["outputs"] ?: item.facts
        }
        if (inputs.isEmpty()) return base
        return "$base Inputs:${renderValue(inputs)}"
    }

    /** Renders a nested fact value as compact JSON so a brief stays readable. */
    private fun renderValue(value: Any?): String = when (value) {
        null -> "null"
        is String -> "\"$value\""
        is Map<*, *> -> value.entries.joinToString(prefix = "{", postfix = "}") { (key, nested) ->
            "\"$key\":${renderValue(nested)}"
        }
        is Iterable<*> -> value.joinToString(prefix = "[", postfix = "]") { renderValue(it) }
        is Boolean, is Number -> value.toString()
        else -> "\"$value\""
    }

    private fun recordCode(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        step: WorkflowStepDefinition,
        outcome: CapabilityOutcome.CodeExecuted,
    ): CapabilityExecution {
        val outcomeLabel = if (outcome.verdict) "pass" else "fail"
        val codeTransitionId = UUID.randomUUID().toString()
        workflowRepository.appendCodeTransition(
            scope,
            namespaceId,
            workflowId,
            WorkflowCodeTransitionRecord(
                codeTransitionId = codeTransitionId,
                stepId = step.id,
                outcome = outcomeLabel,
                exitCode = outcome.exitCode,
                payload = mapOf(
                    "verification" to outcome.verificationName,
                    "command" to outcome.command,
                    "verdict" to outcome.verdict,
                    "timedOut" to outcome.timedOut,
                    "durationMs" to outcome.durationMs,
                ),
                createdAt = null,
            ),
        )

        val evidenceId = UUID.randomUUID().toString()
        evidenceRepository.append(
            scope,
            namespaceId,
            workflowId,
            WorkflowEvidenceItem(
                evidenceId = evidenceId,
                namespaceId = namespaceId,
                workflowId = workflowId,
                stepId = step.id,
                kind = "code-verification",
                outcome = outcomeLabel,
                source = mapOf("kind" to "factory-verification", "name" to outcome.verificationName),
                facts = mapOf(
                    "stepId" to step.id,
                    "verification" to outcome.verificationName,
                    "command" to outcome.command,
                    "exitCode" to outcome.exitCode,
                    "timedOut" to outcome.timedOut,
                    "durationMs" to outcome.durationMs,
                    "verdict" to outcome.verdict,
                ),
                idempotencyKey = null,
                createdAt = null,
            ),
        )
        return CapabilityExecution(outcome, codeTransitionId = codeTransitionId, evidenceId = evidenceId)
    }

    private fun recordHuman(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        step: WorkflowStepDefinition,
        outcome: CapabilityOutcome.HumanCheckpointRequired,
    ): CapabilityExecution {
        val interactionId = UUID.randomUUID().toString()
        interactionRepository.insert(
            scope,
            HumanInteractionRecord(
                interactionId = interactionId,
                namespaceId = namespaceId,
                workflowId = workflowId,
                stepId = step.id,
                interactionType = "checkpoint",
                status = "waiting",
                revision = 1,
                payload = linkedMapOf(
                    "stepId" to step.id,
                    "role" to outcome.role,
                    "actions" to listOf(
                        mapOf("id" to "approve", "label" to "Approuver"),
                        mapOf("id" to "reject", "label" to "Rejeter"),
                    ),
                ),
            ),
        )
        interactionRepository.appendEvent(
            scope,
            namespaceId,
            workflowId,
            HumanInteractionEventRecord(
                eventId = UUID.randomUUID().toString(),
                interactionId = interactionId,
                eventType = "interaction_opened",
                actorId = "factory-capability-resolver",
                payload = mapOf("revision" to 1),
            ),
        )
        return CapabilityExecution(outcome, interactionId = interactionId)
    }

    /**
     * Agent capability dispatcher. When the durable-attempt service and the AgentOS
     * execution adapter are both present, the step runs through the durable bridge
     * (see [resolveAgentViaAdapter]); otherwise the legacy polling turn driver
     * ([resolveAgentViaPolling]) is the active path.
     */
    private fun resolveAgent(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        step: WorkflowStepDefinition,
        repoRoot: Path,
        ticket: String?,
    ): CapabilityExecution {
        val attempts = durableAgentAttemptService
        val adapter = agentOsExecutionAdapter
        return if (attempts != null && adapter != null) {
            resolveAgentViaAdapter(scope, namespaceId, workflowId, step, ticket, attempts, adapter)
        } else {
            resolveAgentViaPolling(scope, namespaceId, workflowId, step, repoRoot, ticket)
        }
    }

    /** The durable attempt ids produced by the claim phase of the bridge. */
    private data class AgentReservation(
        val attemptId: String,
        val caseId: String,
        val ownerToken: String,
        /** Whether a `startTurn` already happened for this attempt (recovered mid-flight). */
        val turnStarted: Boolean,
        /** Set when the attempt is already terminal: the outcome is replayed, not re-driven. */
        val terminalStatus: AgentAttemptStatus? = null,
        /** Set when a live competing lease owns the attempt: the turn must not start. */
        val conflicted: Boolean = false,
    )

    /**
     * Durable AgentOS bridge of an `agent` step (Lot C / Etapes 3, 6, 7).
     *
     *  - Phase 1 (short `REQUIRES_NEW` transaction): register/claim (or adopt a
     *    recovered) durable attempt, fenced by an `ownerToken` lease.
     *  - Phase 2 (NO transaction): create-or-recover the AgentOS case, start the
     *    turn at most once (idempotent by `attemptId`) and observe it. A long
     *    turn therefore never holds a Neo4j transaction.
     *  - Phase 3 (short transaction): persist the verdict outputs as `agent-result`
     *    evidence and finalize the attempt.
     */
    private fun resolveAgentViaAdapter(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        step: WorkflowStepDefinition,
        ticket: String?,
        attempts: DurableAgentAttemptService,
        adapter: AgentOsExecutionAdapter,
    ): CapabilityExecution {
        val agentId = step.responsibility.name ?: "agent"
        val attemptId = stableAttemptId(workflowId, step.id)
        val ownerToken = UUID.randomUUID().toString()
        val brief = buildBrief(scope, namespaceId, workflowId, step, ticket)

        // Phase 1 - short transactions: reserve the attempt (register + atomic,
        // lease-fenced claim). Each durable-attempt operation owns its own short
        // transaction: the claim is itself a `REQUIRES_NEW` compare-and-set, so it
        // must not be nested in an outer transaction that holds the uncommitted
        // registration.
        val reservation = withReservationLock("$workflowId#${step.id}") {
            reserveAgentAttempt(attempts, scope, namespaceId, workflowId, step, attemptId, ownerToken, agentId)
        }
        reservation.terminalStatus?.let {
            return terminalAgentExecution(scope, namespaceId, workflowId, step, reservation)
        }
        if (reservation.conflicted) {
            // A live competing lease owns the attempt: never start a duplicate turn.
            return CapabilityExecution(
                CapabilityOutcome.AgentDeferred(
                    stepId = step.id,
                    persona = step.responsibility.name,
                    code = AGENT_ATTEMPT_CONFLICT,
                    message = "Another execution holds a live lease on attempt '$attemptId'.",
                ),
                attemptId = attemptId,
            )
        }

        // Phase 2 - no transaction: create/recover + start (once) + observe.
        val verdict = executeRemoteTurn(
            adapter, attempts, scope, namespaceId, workflowId, step, reservation, agentId, brief,
        )

        // Phase 3 - short transaction: persist outputs + finalize the attempt.
        return newTransaction {
            finalizeAgentAttempt(scope, namespaceId, workflowId, step, reservation, verdict)
        }
    }

    /**
     * Serialises the register+claim of one attempt in this process: the embedded
     * engine is single-writer and the composite attempt id has no graph-level
     * uniqueness constraint, so two concurrent `MERGE`s could otherwise create
     * duplicate nodes before the atomic claim fences the loser out. Mirrors the
     * process-local claim lock of
     * [io.whozoss.factory.agentattempt.persistence.Neo4jDurableAgentAttemptRepository].
     */
    private inline fun <T> withReservationLock(key: String, block: () -> T): T {
        val lock = reservationLocks.computeIfAbsent(key) { ReentrantLock() }
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }

    /**
     * Registers/claims the durable attempt inside a short transaction. A recovered
     * non-terminal attempt is adopted (its `caseId`/`ownerToken` are reused) so the
     * bridge never re-creates the AgentOS case; a live competing lease yields a
     * conflicted reservation and no turn is started.
     */
    private fun reserveAgentAttempt(
        attempts: DurableAgentAttemptService,
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        step: WorkflowStepDefinition,
        attemptId: String,
        ownerToken: String,
        agentId: String,
    ): AgentReservation {
        val existing = attempts.find(scope, namespaceId, workflowId, step.id, attemptId)
        if (existing != null && existing.status.terminal) {
            return AgentReservation(
                attemptId = attemptId,
                caseId = existing.caseId,
                ownerToken = existing.ownerToken ?: ownerToken,
                turnStarted = true,
                terminalStatus = existing.status,
            )
        }
        val newAttempt = existing == null
        if (newAttempt) {
            attempts.register(
                scope,
                DurableAgentAttempt(
                    attemptId = attemptId,
                    caseId = stableCaseId(workflowId, step.id),
                    namespaceId = namespaceId,
                    workflowId = workflowId,
                    stepId = step.id,
                    attemptNumber = 1,
                    agentName = agentId,
                ),
            )
        }
        val turnStarted = existing?.status in setOf(
            AgentAttemptStatus.STARTING,
            AgentAttemptStatus.RUNNING,
            AgentAttemptStatus.WAITING_HUMAN,
        )
        val resolvedCaseId = existing?.caseId?.takeIf { it.isNotBlank() } ?: stableCaseId(workflowId, step.id)
        val claimed = try {
            attempts.claim(
                scope,
                namespaceId,
                workflowId,
                step.id,
                attemptId,
                ownerToken,
                leaseTtlMs = agentLeaseTtlMs,
            )
            true
        } catch (_: AttemptClaimConflictException) {
            false
        }
        return AgentReservation(
            attemptId = attemptId,
            caseId = resolvedCaseId,
            ownerToken = ownerToken,
            turnStarted = turnStarted,
            conflicted = !claimed,
        )
    }

    /**
     * Phase 2 of the bridge: no Neo4j transaction is active while the remote turn
     * runs. The case is created or recovered (idempotent by `attemptId`), the turn
     * is started at most once, and the verdict is observed over SSE with REST
     * reconciliation. An ambiguous start or observation is reconciled, never
     * silently turned into a success.
     */
    private fun executeRemoteTurn(
        adapter: AgentOsExecutionAdapter,
        attempts: DurableAgentAttemptService,
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        step: WorkflowStepDefinition,
        reservation: AgentReservation,
        agentId: String,
        brief: String,
    ): AgentOsExecutionVerdict {
        val handle = adapter.createOrRecoverExecution(
            namespaceId = namespaceId,
            workflowId = workflowId,
            stepId = step.id,
            externalUserId = null,
            attemptId = reservation.attemptId,
            capabilityToken = null,
            caseId = reservation.caseId,
        )
        val caseId = handle.caseId
        // Mark the attempt `starting` BEFORE dispatching the message: a crash
        // after the message is accepted therefore records that the turn was
        // started and a replay never sends a second turn (idempotence by attemptId).
        newTransaction {
            attempts.transition(scope, namespaceId, workflowId, step.id, reservation.attemptId, reservation.ownerToken, AgentAttemptStatus.STARTING)
        }
        if (!reservation.turnStarted) {
            try {
                adapter.startTurn(caseId, agentId, brief, null, reservation.attemptId, null)
            } catch (error: Exception) {
                // Ambiguous dispatch: reconcile before deciding; never a false success.
                runCatching { adapter.reconcile(caseId) }
                    .getOrNull()
                    ?.let { return it }
            }
        }
        newTransaction {
            attempts.transition(scope, namespaceId, workflowId, step.id, reservation.attemptId, reservation.ownerToken, AgentAttemptStatus.RUNNING)
        }
        return try {
            adapter.observeTurn(caseId, reservation.attemptId, agentObservationTimeoutMs)
        } catch (error: Exception) {
            runCatching { adapter.reconcile(caseId) }.getOrElse {
                AgentOsExecutionVerdict.Indeterminate(
                    reason = "AGENT_OBSERVATION_ERROR: ${error.message ?: error.toString()}",
                    evidence = mapOf("caseId" to caseId, "attemptId" to reservation.attemptId),
                )
            }
        }
    }

    /**
     * Phase 3 of the bridge: validates the verdict, persists the durable outputs as
     * `agent-result` evidence and finalizes the attempt (fenced on the owner token).
     */
    private fun finalizeAgentAttempt(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        step: WorkflowStepDefinition,
        reservation: AgentReservation,
        verdict: AgentOsExecutionVerdict,
    ): CapabilityExecution {
        val agentId = step.responsibility.name ?: "agent"
        fun persistEvidence(outcome: String, facts: Map<String, Any?>): String {
            val evidenceId = UUID.randomUUID().toString()
            evidenceRepository.append(
                scope,
                namespaceId,
                workflowId,
                WorkflowEvidenceItem(
                    evidenceId = evidenceId,
                    namespaceId = namespaceId,
                    workflowId = workflowId,
                    stepId = step.id,
                    kind = AGENT_RESULT_EVIDENCE_KIND,
                    outcome = outcome,
                    source = mapOf("kind" to "agentos-adapter", "agentId" to agentId, "attemptId" to reservation.attemptId),
                    facts = facts + mapOf("attemptId" to reservation.attemptId, "stepId" to step.id),
                    idempotencyKey = null,
                    createdAt = null,
                ),
            )
            return evidenceId
        }
        return when (verdict) {
            is AgentOsExecutionVerdict.Succeeded -> {
                val evidenceId = persistEvidence(
                    "pass",
                    mapOf("status" to "PASS", "outputs" to verdict.outputs, "evidence" to verdict.evidence),
                )
                durableAgentAttemptService!!.finalize(
                    scope, namespaceId, workflowId, step.id, reservation.attemptId, reservation.ownerToken,
                    AgentAttemptStatus.SUCCEEDED, resultEvidenceId = evidenceId,
                )
                CapabilityExecution(
                    CapabilityOutcome.AgentCompleted(step.id, step.responsibility.name, "PASS", verdict.outputs),
                    evidenceId = evidenceId,
                    attemptId = reservation.attemptId,
                )
            }
            is AgentOsExecutionVerdict.Failed -> {
                val evidenceId = persistEvidence(
                    "fail",
                    mapOf("status" to "FAILED", "code" to verdict.code, "message" to verdict.message, "evidence" to verdict.evidence),
                )
                durableAgentAttemptService!!.finalize(
                    scope, namespaceId, workflowId, step.id, reservation.attemptId, reservation.ownerToken,
                    AgentAttemptStatus.FAILED, failureCode = verdict.code, resultEvidenceId = evidenceId,
                )
                CapabilityExecution(
                    CapabilityOutcome.AgentFailed(step.id, step.responsibility.name, verdict.code, verdict.message, verdict.evidence),
                    evidenceId = evidenceId,
                    attemptId = reservation.attemptId,
                )
            }
            is AgentOsExecutionVerdict.Interrupted -> {
                val evidenceId = persistEvidence(
                    "fail",
                    mapOf("status" to "INTERRUPTED", "reason" to verdict.reason, "evidence" to verdict.evidence),
                )
                durableAgentAttemptService!!.finalize(
                    scope, namespaceId, workflowId, step.id, reservation.attemptId, reservation.ownerToken,
                    AgentAttemptStatus.INTERRUPTED, failureCode = "AGENT_INTERRUPTED", resultEvidenceId = evidenceId,
                )
                CapabilityExecution(
                    CapabilityOutcome.AgentFailed(step.id, step.responsibility.name, "AGENT_INTERRUPTED", verdict.reason, verdict.evidence),
                    evidenceId = evidenceId,
                    attemptId = reservation.attemptId,
                )
            }
            is AgentOsExecutionVerdict.Indeterminate -> {
                val evidenceId = persistEvidence(
                    "fail",
                    mapOf("status" to "INDETERMINATE", "reason" to verdict.reason, "evidence" to verdict.evidence),
                )
                durableAgentAttemptService!!.finalize(
                    scope, namespaceId, workflowId, step.id, reservation.attemptId, reservation.ownerToken,
                    AgentAttemptStatus.INDETERMINATE, failureCode = "AGENT_INDETERMINATE", resultEvidenceId = evidenceId,
                )
                CapabilityExecution(
                    CapabilityOutcome.AgentFailed(step.id, step.responsibility.name, "AGENT_INDETERMINATE", verdict.reason, verdict.evidence),
                    evidenceId = evidenceId,
                    attemptId = reservation.attemptId,
                )
            }
            is AgentOsExecutionVerdict.WaitingHuman -> {
                persistEvidence(
                    "waiting_human",
                    mapOf(
                        "status" to "WAITING_HUMAN",
                        "questionRef" to verdict.questionRef,
                        "questionText" to verdict.questionText,
                        "evidence" to verdict.evidence,
                    ),
                )
                durableAgentAttemptService!!.transition(
                    scope, namespaceId, workflowId, step.id, reservation.attemptId, reservation.ownerToken,
                    AgentAttemptStatus.WAITING_HUMAN,
                )
                CapabilityExecution(
                    CapabilityOutcome.HumanCheckpointRequired(step.id, step.responsibility.name),
                    attemptId = reservation.attemptId,
                )
            }
        }
    }

    /**
     * Replays a terminal attempt without re-driving AgentOS: the last durable
     * `agent-result` evidence (or the terminal status) decides the outcome.
     */
    private fun terminalAgentExecution(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        step: WorkflowStepDefinition,
        reservation: AgentReservation,
    ): CapabilityExecution {
        val status = reservation.terminalStatus ?: AgentAttemptStatus.INDETERMINATE
        val evidence = evidenceRepository.list(scope, namespaceId, workflowId, step.id)
            .lastOrNull { it.kind == AGENT_RESULT_EVIDENCE_KIND }
        return when (status) {
            AgentAttemptStatus.SUCCEEDED -> CapabilityExecution(
                CapabilityOutcome.AgentCompleted(step.id, step.responsibility.name, "PASS", evidence?.facts ?: emptyMap()),
                evidenceId = evidence?.evidenceId,
                attemptId = reservation.attemptId,
            )
            else -> CapabilityExecution(
                CapabilityOutcome.AgentFailed(
                    step.id,
                    step.responsibility.name,
                    evidence?.facts?.get("code")?.toString() ?: "AGENT_${status.dbValue.uppercase()}",
                    evidence?.facts?.get("message")?.toString() ?: "Attempt already terminal as '${status.dbValue}'.",
                    evidence?.facts ?: emptyMap(),
                ),
                evidenceId = evidence?.evidenceId,
                attemptId = reservation.attemptId,
            )
        }
    }

    /**
     * Agent capability: three phases — claim (short tx), external turn (no tx),
     * terminalize + evidence (short tx). Any transport exception is turned into
     * an explicit failure and terminalized outside the failed call — never a
     * false success and never an unhandled 500 masking the cause.
     */
    private fun resolveAgentViaPolling(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        step: WorkflowStepDefinition,
        repoRoot: Path,
        ticket: String?,
    ): CapabilityExecution {
        val agentId = step.responsibility.name ?: "agent"
        val brief = buildBrief(scope, namespaceId, workflowId, step, ticket)
        // The Factory chooses the AgentOS case id so the submission capability
        // it mints can be bound to the exact case that will submit the result.
        val caseId = UUID.randomUUID().toString()

        // Phase 1 — short transaction: reserve the attempt and mint the token.
        val claim = newTransaction {
            val attemptId = UUID.randomUUID().toString()
            attemptRepository.insert(
                scope,
                AgentStepAttemptRecord(
                    namespaceId = namespaceId,
                    workflowId = workflowId,
                    stepId = step.id,
                    attemptId = attemptId,
                    agentId = agentId,
                    status = "running",
                    revision = 1,
                    payload = "{}",
                ),
            )
            AgentClaim(
                attemptId = attemptId,
                capabilityToken = issueCapability(scope, namespaceId, workflowId, step, attemptId, caseId, agentId, brief),
            )
        }

        // Phase 2 — no transaction: the (potentially long) AgentOS turn.
        val outcome = try {
            resolver.resolve(
                step,
                repoRoot,
                namespaceId,
                workflowId,
                brief,
                claim.attemptId,
                claim.capabilityToken,
                caseId,
            )
        } catch (error: Exception) {
            CapabilityOutcome.AgentFailed(
                stepId = step.id,
                persona = step.responsibility.name,
                code = "AGENT_TURN_ERROR",
                message = error.message ?: error.toString(),
            )
        }

        // Phase 3 — short transaction: terminalize the attempt and record the evidence.
        return newTransaction {
            val passed = outcome is CapabilityOutcome.AgentCompleted
            attemptRepository.terminalize(
                scope,
                namespaceId,
                workflowId,
                step.id,
                claim.attemptId,
                if (passed) "completed" else "failed",
            )
            val evidenceId = UUID.randomUUID().toString()
            evidenceRepository.append(
                scope,
                namespaceId,
                workflowId,
                WorkflowEvidenceItem(
                    evidenceId = evidenceId,
                    namespaceId = namespaceId,
                    workflowId = workflowId,
                    stepId = step.id,
                    kind = "agent-turn",
                    outcome = if (passed) "pass" else "fail",
                    source = mapOf("kind" to "agentos", "agentId" to agentId, "attemptId" to claim.attemptId),
                    facts = agentFacts(outcome, claim.attemptId),
                    idempotencyKey = null,
                    createdAt = null,
                ),
            )
            CapabilityExecution(outcome, evidenceId = evidenceId, attemptId = claim.attemptId)
        }
    }

    /**
     * Mint the single-use submission capability for [attemptId]. Issuance is
     * best-effort: a non-safe persona or a missing issuer must not fail the DAG
     * step, it only means the worker cannot submit through the capability
     * channel. Failures are swallowed (the step still runs and is recorded).
     */
    private fun issueCapability(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        step: WorkflowStepDefinition,
        attemptId: String,
        caseId: String,
        agentName: String,
        brief: String?,
    ): String? {
        val issuer = agentStepResultService ?: return null
        return runCatching {
            issuer.issue(
                scope,
                AgentStepResultCapabilityIdentity(
                    attemptId = attemptId,
                    workflowId = workflowId,
                    stepId = step.id,
                    namespaceId = namespaceId,
                    caseId = caseId,
                    agentName = agentName,
                    briefHash = CanonicalJsonHash.sha256(brief ?: ""),
                ),
            ).token
        }.getOrNull()
    }

    private fun agentFacts(outcome: CapabilityOutcome, attemptId: String): Map<String, Any?> = when (outcome) {
        is CapabilityOutcome.AgentCompleted ->
            outcome.facts + mapOf("attemptId" to attemptId, "status" to outcome.status)
        is CapabilityOutcome.AgentFailed ->
            outcome.facts + mapOf("attemptId" to attemptId, "code" to outcome.code, "message" to outcome.message)
        is CapabilityOutcome.AgentDeferred ->
            mapOf("attemptId" to attemptId, "code" to outcome.code, "message" to outcome.message)
        else -> mapOf("attemptId" to attemptId)
    }

    companion object {
        /** Evidence kind carrying the durable, structured outputs of an agent step. */
        const val AGENT_RESULT_EVIDENCE_KIND = "agent-result"

        /** Failure code returned when another execution holds a live lease on the attempt. */
        const val AGENT_ATTEMPT_CONFLICT = "AGENT_ATTEMPT_CONFLICT"

        /**
         * Deterministic durable attempt id of a step execution. Keying the
         * attempt on `(workflowId, stepId)` is what makes the bridge replayable:
         * a retried run recovers the very same attempt (and its AgentOS case)
         * instead of creating a duplicate.
         */
        fun stableAttemptId(workflowId: String, stepId: String): String = "$workflowId#$stepId"

        /** Deterministic AgentOS case id bound to the durable attempt id. */
        fun stableCaseId(workflowId: String, stepId: String): String = "case:$workflowId#$stepId"

        /** Process-local locks serialising the register+claim of one attempt. */
        private val reservationLocks = ConcurrentHashMap<String, ReentrantLock>()
    }
}
