package io.whozoss.factory.workflow.service

import io.whozoss.factory.agentattempt.domain.AttemptLeaseFencingException
import io.whozoss.factory.capability.AgentObservationUpdate
import io.whozoss.factory.capability.CapabilityExecution
import io.whozoss.factory.capability.CapabilityExecutionService
import io.whozoss.factory.capability.CapabilityOutcome
import io.whozoss.factory.capability.NeedsResearchRouter
import io.whozoss.factory.oracle.domain.OracleApplicableCondition
import io.whozoss.factory.oracle.domain.OracleDefinition
import io.whozoss.factory.oracle.domain.OracleExecutionStatus
import io.whozoss.factory.oracle.registry.OracleDefinitionRegistry
import io.whozoss.factory.oracle.service.OracleExecutionService
import io.whozoss.factory.oracle.service.OracleRunCommand
import io.whozoss.factory.persistence.TenantScope
import io.whozoss.factory.workflow.domain.CanonicalHash
import io.whozoss.factory.workflow.domain.HumanInteractionRecord
import io.whozoss.factory.workflow.domain.SessionSequencer
import io.whozoss.factory.workflow.domain.WorkflowErrorCodes
import io.whozoss.factory.workflow.domain.WorkflowEvidenceItem
import io.whozoss.factory.workflow.domain.WorkflowInstanceRecord
import io.whozoss.factory.workflow.domain.WorkflowProjectionRecord
import io.whozoss.factory.workflow.domain.WorkflowStatuses
import io.whozoss.factory.workflow.domain.WorkflowStepDefinition
import io.whozoss.factory.workflow.domain.WorkflowStepStateRecord
import io.whozoss.factory.workflow.domain.WorkflowTransitionRequest
import io.whozoss.factory.workflow.domain.nowIso
import io.whozoss.factory.workflow.domain.workflowException
import io.whozoss.factory.workflow.persistence.HumanInteractionRepository
import io.whozoss.factory.workflow.persistence.WorkflowEvidenceRepository
import io.whozoss.factory.workflow.persistence.WorkflowRepository
import io.whozoss.factory.workflow.sse.WorkflowSseHub
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import mu.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate

/** The projected state of one session step. */
data class SessionStepState(val stepId: String, val status: String)

/** The projected state of a session after a run/resume. */
data class SessionRunResult(
    val namespaceId: String,
    val workflowId: String,
    val status: String,
    val steps: List<SessionStepState>,
)

/**
 * In-memory per-step progress of a single run/resume: the current status plus
 * the execution window (`startedAt` when a step first became `running`,
 * `completedAt` when it reached a terminal status). Timings are also persisted
 * in `workflow_step_states.payload` so a resumed run and a read-only
 * [SessionRunService.sessionState] recover them.
 */
private class SessionProgress {
    val statuses = LinkedHashMap<String, String>()
    val startedAt = HashMap<String, String>()
    val completedAt = HashMap<String, String>()
    val waitingQuestions = HashMap<String, Map<String, Any?>>()

    /** Persisted payload of a step state: timings plus bounded intermediate question. */
    fun payload(stepId: String): Map<String, Any?> = buildMap {
        startedAt[stepId]?.let { put("startedAt", it) }
        completedAt[stepId]?.let { put("completedAt", it) }
        waitingQuestions[stepId]?.let { put("waitingQuestion", it) }
    }

    companion object {
        /** Rebuilds a progress from the durable per-step states (resume / read). */
        fun fromStates(states: Map<String, WorkflowStepStateRecord>): SessionProgress {
            val progress = SessionProgress()
            for ((stepId, state) in states) {
                (state.payload["startedAt"] as? String)?.let { progress.startedAt[stepId] = it }
                (state.payload["completedAt"] as? String)?.let { progress.completedAt[stepId] = it }
                @Suppress("UNCHECKED_CAST")
                (state.payload["waitingQuestion"] as? Map<String, Any?>)?.let { progress.waitingQuestions[stepId] = it }
            }
            return progress
        }
    }
}

/**
 * Outcome of the applicable auto-oracles evaluated for a single step: whether
 * every run succeeded and the evidence ids the runs published (if any).
 */
private data class OracleEvaluation(
    val allSucceeded: Boolean,
    val evidenceIds: List<String>,
) {
    companion object {
        val EMPTY = OracleEvaluation(allSucceeded = true, evidenceIds = emptyList())
    }
}

/**
 * Automatic DAG execution of a declarative session (W8.3).
 *
 * The sequencer runs the whole DAG by itself (never step by step). At every
 * iteration it evaluates the pure [SessionSequencer] rules, executes ONE ready
 * step through [CapabilityExecutionService] and applies the failure rule:
 *  - a succeeded step releases its dependents (`ready`);
 *  - a succeeded step first runs its applicable auto-oracles (matched by
 *    `OracleDefinition.applicable`); a failing oracle fails the step instead;
 *  - a failed step marks ALL its transitive dependents `blocked`;
 *  - independent branches keep running;
 *  - a `human` step suspends the session in `waiting_human` until the interaction
 *    is answered; re-running the sequencer resumes from the current state;
 *  - when no step is `ready` any more the session is `completed` (all passed) or
 *    `failed` (any failed/blocked). There is NO automatic retry.
 *
 * Steps are executed sequentially (one ready step per iteration) rather than in
 * parallel: this is the documented, deterministic choice for W8.3 — it keeps the
 * projection/evidence ordering stable and the failure propagation easy to reason
 * about. The DAG structure (independent branches) is still honoured: a failure on
 * one branch never stops another.
 *
 * Every step records its state (`workflow_step_states`), a transition
 * (`workflow_transitions`) and a `session-step` evidence item, then the instance
 * projection is refreshed for the cockpit timeline.
 *
 * Note: the run is an orchestrator loop that is deliberately NOT transactional:
 * each persistence step (step-state claim/update, transition, evidence, projection)
 * opens its own short transaction, so a long external capability call (an agent
 * turn or a local verification process) never holds — and never outlives — a Neo4j
 * transaction. The in-process sequencer serialises runs of the same workflow with
 * a process-local lock, which is exactly the guarantee the embedded, single-process
 * engine provides (mirroring the lease claim).
 */
@Service
class SessionRunService(
    private val repository: WorkflowRepository,
    private val evidenceRepository: WorkflowEvidenceRepository,
    private val interactionRepository: HumanInteractionRepository,
    private val capabilityExecutionService: CapabilityExecutionService,
    private val sseHub: WorkflowSseHub,
    /**
     * Optional oracle catalogue. When present, the step's applicable oracle
     * definitions (`applicable.workflowTypes` / `applicable.stepIds`) are run
     * automatically and gate its completion. Pure unit tests may omit it.
     */
    private val oracleDefinitionRegistry: OracleDefinitionRegistry? = null,
    /**
     * Optional oracle execution service. When present, matching definitions are
     * really run (and `oracle-result` evidence is published); when absent, the
     * auto-oracle hook is a no-op.
     */
    private val oracleExecutionService: OracleExecutionService? = null,
    /**
     * Optional transaction manager used to bracket emergency failure-recovery
     * writes in a FRESH short transaction, so they succeed even if a prior query
     * or external operation failed. Injected in production; pure unit tests may
     * omit it and the recovery then runs inline.
     */
    private val transactionManager: PlatformTransactionManager? = null,
    /**
     * Optional automatic NEEDS_RESEARCH → Searcher router (Lot E). When present,
     * a step whose authoritative result is `NEEDS_RESEARCH` is routed to a
     * Searcher turn and re-armed on the same worktree, instead of stalling.
     * Pure unit tests may omit it and the step then simply stays blocked in
     * `needs_research` (never sealed as failed).
     */
    private val needsResearchRouter: NeedsResearchRouter? = null,
) {

    private val logger = KotlinLogging.logger {}

    private val failureTransaction: TransactionTemplate? = transactionManager?.let { manager ->
        TransactionTemplate(manager).apply { propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW }
    }

    /** Runs [block] in a fresh short transaction, or inline when none is configured. */
    private fun <T : Any> newTransaction(block: () -> T): T {
        val template = failureTransaction ?: return block()
        return template.execute { block() }!!
    }

    /** Runs (or resumes) the session DAG to a terminal state or to a human suspension. */
    fun runSession(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        repoRoot: Path,
        ticket: String? = null,
    ): SessionRunResult = withWorkflowLock(scope, namespaceId, workflowId) {
        runSessionLocked(scope, namespaceId, workflowId, repoRoot, ticket)
    }

    /**
     * Serialises concurrent runs of the same workflow: the in-process sequencer
     * is the only owner of a step's `running` state, so two racing runs must not
     * both execute the same ready step. This mirrors the lease-claim lock used by
     * [io.whozoss.factory.lease.persistence.Neo4jLeaseRepository] for the embedded,
     * single-process engine.
     */
    private inline fun <T> withWorkflowLock(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        block: () -> T,
    ): T {
        val key = "${scope.organizationId}|${scope.workstreamId}|$namespaceId|$workflowId"
        val lock = runLocks.computeIfAbsent(key) { ReentrantLock() }
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }

    private fun runSessionLocked(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        repoRoot: Path,
        ticket: String? = null,
    ): SessionRunResult {
        val instance = activeInstance(scope, namespaceId, workflowId)
        // The instance revision is the optimistic-locking precondition every
        // transition must carry: it is read once at the start of the run and
        // stays stable until `persistProjection` bumps it at the end. Using it
        // (instead of a hardcoded 1) keeps `expectedRevision` in step with the
        // persisted snapshot and avoids stale-revision conflicts across turns.
        val expectedRevision = instance.revision
        // The ticket may be supplied by the run request, or have travelled with
        // the start command / instance relations. Either way it must reach the
        // step execution context (agent brief) and be persisted on the instance.
        val effectiveTicket = ticket?.takeIf { it.isNotBlank() } ?: instanceTicket(instance)
        val workflowType = instance.instance["workflowType"] as? String
        val steps = resolveDefinition(scope, instance).steps
        if (steps.isEmpty()) {
            throw workflowException(WorkflowErrorCodes.INVALID_START_REQUEST, "The session has no steps.")
        }
        val statuses = loadProgress(scope, namespaceId, workflowId, steps)
        var suspended = false
        val guard = steps.size * 4 + 8
        var iterations = 0
        run {
            while (iterations++ < guard) {
                applyEvaluation(scope, namespaceId, workflowId, steps, statuses)
                if (statuses.statuses.values.any { it == WorkflowStatuses.WAITING_HUMAN }) {
                    if (!resolveWaitingHuman(scope, namespaceId, workflowId, steps, statuses, expectedRevision)) {
                        suspended = true
                        return@run
                    }
                    continue
                }
                val readyId = SessionSequencer.readySteps(steps, statuses.statuses).firstOrNull() ?: return@run
                val step = steps.first { it.id == readyId }
                executeStep(scope, namespaceId, workflowId, steps, statuses, step, repoRoot, effectiveTicket, expectedRevision, workflowType)?.let { terminal ->
                    if (terminal == WorkflowStatuses.WAITING_HUMAN) {
                        suspended = true
                        return@run
                    }
                }
            }
        }
        val sessionStatus = if (suspended) WorkflowStatuses.WAITING_HUMAN else SessionSequencer.terminalStatus(steps, statuses.statuses)
        val committedRevision = persistProjection(scope, namespaceId, workflowId, steps, statuses, sessionStatus, effectiveTicket)
        sseHub.publish(
            scope,
            namespaceId,
            buildMap<String, Any?> {
                put("workflowId", workflowId)
                put("namespaceId", namespaceId)
                // The committed revision lets a client discard a stale REST re-read.
                if (committedRevision != null) put("revision", committedRevision)
            },
        )
        return SessionRunResult(namespaceId, workflowId, sessionStatus, statusesOf(steps, statuses.statuses))
    }

    /** The ticket carried by a persisted instance (top-level or in its relations). */
    private fun instanceTicket(instance: WorkflowInstanceRecord): String? {
        (instance.instance["ticket"] as? String)?.takeIf { it.isNotBlank() }?.let { return it }
        val relations = instance.instance["relations"] as? Map<*, *>
        return (relations?.get("ticket") as? String)?.takeIf { it.isNotBlank() }
    }

    /** Read-only projection of the current session state. */
    @Transactional(readOnly = true)
    fun sessionState(scope: TenantScope, namespaceId: String, workflowId: String): SessionRunResult? {
        val instance = repository.findInstance(scope, namespaceId, workflowId) ?: return null
        val steps = resolveDefinition(scope, instance).steps
        if (steps.isEmpty()) {
            return SessionRunResult(namespaceId, workflowId, sessionStatus(instance), emptyList())
        }
        val stored = repository.findStepStates(scope, namespaceId, workflowId).associateBy { it.stepId }
        val statuses = LinkedHashMap<String, String>()
        for (step in steps) {
            statuses[step.id] = stored[step.id]?.status ?: WorkflowStatuses.PENDING
        }
        return SessionRunResult(namespaceId, workflowId, sessionStatus(instance), statusesOf(steps, statuses))
    }

    private fun sessionStatus(instance: WorkflowInstanceRecord): String =
        (instance.projection["status"] as? String) ?: (instance.instance["status"] as? String) ?: "unknown"

    private fun statusesOf(
        steps: List<WorkflowStepDefinition>,
        statuses: Map<String, String>,
    ): List<SessionStepState> = steps.map { SessionStepState(it.id, statuses[it.id] ?: WorkflowStatuses.PENDING) }

    private fun loadProgress(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        steps: List<WorkflowStepDefinition>,
    ): SessionProgress {
        val progress = SessionProgress()
        val existing = repository.findStepStates(scope, namespaceId, workflowId)
        if (existing.isEmpty()) {
            val initial = SessionSequencer.initialStatuses(steps)
            for (step in steps) {
                val status = initial[step.id] ?: WorkflowStatuses.PENDING
                setStatus(scope, namespaceId, workflowId, step.id, status, progress)
            }
            return progress
        }
        val byId = existing.associateBy { it.stepId }
        val restored = SessionProgress.fromStates(byId)
        for (step in steps) {
            val stored = byId[step.id]?.status ?: WorkflowStatuses.PENDING
            // Never reset a durable RUNNING step to READY automatically. The
            // external AgentOS turn may have been accepted even when this process
            // lost observation; replaying it could duplicate uncertain work. A
            // normal submission replay therefore observes the persisted status
            // and leaves reconciliation/retry to explicit durable-attempt flows.
            progress.statuses[step.id] = stored
        }
        progress.startedAt.putAll(restored.startedAt)
        progress.completedAt.putAll(restored.completedAt)
        progress.waitingQuestions.putAll(restored.waitingQuestions)
        return progress
    }

    private fun applyEvaluation(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        steps: List<WorkflowStepDefinition>,
        progress: SessionProgress,
    ) {
        val evaluation = SessionSequencer.evaluate(steps, progress.statuses)
        for (stepId in evaluation.blocked) setStatus(scope, namespaceId, workflowId, stepId, WorkflowStatuses.BLOCKED, progress)
        for (stepId in evaluation.ready) setStatus(scope, namespaceId, workflowId, stepId, WorkflowStatuses.READY, progress)
    }

    /** Executes one ready step; returns its terminal status, or null on an unexpected failure path. */
    private fun executeStep(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        steps: List<WorkflowStepDefinition>,
        statuses: SessionProgress,
        step: WorkflowStepDefinition,
        repoRoot: Path,
        ticket: String?,
        expectedRevision: Int,
        workflowType: String?,
    ): String? {
        // Atomic claim: only the run that transitions the step `ready -> running`
        // owns its external execution. A concurrent or stale run gets `false` and
        // leaves the step alone. The claim is a short, isolated write.
        if (!claimStep(scope, namespaceId, workflowId, step.id, statuses)) return null
        val execution = try {
            capabilityExecutionService.resolveAndRecord(scope, namespaceId, workflowId, step, repoRoot, ticket) { update ->
                projectAgentObservation(scope, namespaceId, workflowId, step.id, statuses, update)
            }
        } catch (fenced: AttemptLeaseFencingException) {
            // Another claimant became authoritative while this run was observing
            // AgentOS. Fencing is a hand-off, not a business failure: the loser
            // must stop without publishing FAILED or blocking dependants. The
            // current owner/recovery path will publish the terminal projection.
            logger.info {
                "Step '${step.id}' of workflow '$workflowId' lost its attempt lease; " +
                    "leaving the step running for authoritative reconciliation"
            }
            return null
        } catch (error: Exception) {
            // The failure is handled OUTSIDE any (possibly dead) transaction: the
            // recovery writes run in a FRESH short transaction so they succeed
            // even when the prior query/operation failed.
            recordStepFailure(scope, namespaceId, workflowId, step, error, expectedRevision, statuses)
            return WorkflowStatuses.FAILED
        }
        if (execution.outcome is CapabilityOutcome.AgentDeferred) {
            // A competing live claimant owns the durable attempt. This runner is
            // non-authoritative and must not turn claim contention into a failed
            // workflow step.
            return null
        }
        var terminal = classify(execution.outcome)
        // Lot E: an authoritative NEEDS_RESEARCH verdict BLOCKS the step (its
        // dependants are not launched) but NEVER seals the run as FAILED. The
        // engine routes a Searcher attempt and, once the research is available,
        // re-arms the step as a brand-new attempt on the same worktree.
        if (execution.outcome is CapabilityOutcome.AgentNeedsResearch) {
            val reArmed = routeNeedsResearch(scope, namespaceId, workflowId, step, execution.outcome, repoRoot, ticket)
            transition(scope, namespaceId, workflowId, step, WorkflowStatuses.NEEDS_RESEARCH, expectedRevision)
            val resultingStatus = if (reArmed) WorkflowStatuses.READY else WorkflowStatuses.NEEDS_RESEARCH
            setStatus(scope, namespaceId, workflowId, step.id, resultingStatus, statuses)
            recordStepEvidence(scope, namespaceId, workflowId, step, WorkflowStatuses.NEEDS_RESEARCH, execution)
            return resultingStatus
        }
        // Auto-oracles: once the step's own capability has succeeded, run every
        // oracle definition whose `applicable` conditions match this
        // (workflowType, stepId). A failing oracle gates the transition: the step
        // is classified FAILED and its dependents are blocked. The oracle-result
        // evidence ids are carried on the transition for traceability.
        val oracleEvidenceIds = if (terminal == WorkflowStatuses.COMPLETED) {
            val evaluation = runApplicableOracles(scope, namespaceId, workflowId, workflowType, step.id)
            if (!evaluation.allSucceeded) terminal = WorkflowStatuses.FAILED
            evaluation.evidenceIds
        } else {
            emptyList()
        }
        transition(scope, namespaceId, workflowId, step, terminal, expectedRevision, oracleEvidenceIds)
        setStatus(scope, namespaceId, workflowId, step.id, terminal, statuses)
        recordStepEvidence(scope, namespaceId, workflowId, step, terminal, execution)
        return terminal
    }

    /**
     * Runs every oracle definition applicable to the current `(workflowType,
     * stepId)`.
     *
     * Matching follows `OracleDefinition.applicable`: an empty
     * `applicable.workflowTypes` (resp. `applicable.stepIds`) is a wildcard, a
     * non-empty one must contain the current `workflowType` (resp. `stepId`). A
     * definition that matches neither is skipped; one that throws is treated as
     * a failure (its run is abandoned, never a silent success).
     */
    private fun runApplicableOracles(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        workflowType: String?,
        stepId: String,
    ): OracleEvaluation {
        val registry = oracleDefinitionRegistry ?: return OracleEvaluation.EMPTY
        val service = oracleExecutionService ?: return OracleEvaluation.EMPTY
        val matching = runCatching { registry.list() }
            .onFailure { logger.warn(it) { "Oracle registry listing failed; skipping auto-oracles for step '$stepId'" } }
            .getOrElse { return OracleEvaluation.EMPTY }
            .filter { appliesTo(it, workflowType, stepId) }
        if (matching.isEmpty()) return OracleEvaluation.EMPTY

        var allSucceeded = true
        val evidenceIds = ArrayList<String>()
        for (definition in matching) {
            val result = runCatching {
                service.run(
                    scope,
                    OracleRunCommand(
                        workflowId = workflowId,
                        stepId = stepId,
                        oracleId = definition.id,
                        namespaceId = namespaceId,
                        idempotencyKey = "dag-oracle:$workflowId:$stepId:${definition.id}",
                    ),
                )
            }.getOrElse { error ->
                logger.warn(error) { "Oracle '${definition.id}' could not run for step '$stepId'" }
                allSucceeded = false
                continue
            }
            result.evidenceId?.let(evidenceIds::add)
            if (result.status != OracleExecutionStatus.SUCCEEDED) allSucceeded = false
        }
        return OracleEvaluation(allSucceeded, evidenceIds)
    }

    /** Whether an oracle definition's `applicable` conditions match the step. */
    private fun appliesTo(definition: OracleDefinition, workflowType: String?, stepId: String): Boolean {
        val applicable: OracleApplicableCondition = definition.applicable
        val typesMatch = applicable.workflowTypes.isEmpty() ||
            (workflowType != null && workflowType in applicable.workflowTypes)
        val stepsMatch = applicable.stepIds.isEmpty() || stepId in applicable.stepIds
        return typesMatch && stepsMatch
    }

    private fun classify(outcome: CapabilityOutcome): String = when (outcome) {
        is CapabilityOutcome.CodeExecuted -> if (outcome.verdict) WorkflowStatuses.COMPLETED else WorkflowStatuses.FAILED
        is CapabilityOutcome.CodeRefused -> WorkflowStatuses.FAILED
        is CapabilityOutcome.AgentCompleted -> WorkflowStatuses.COMPLETED
        is CapabilityOutcome.AgentFailed -> WorkflowStatuses.FAILED
        is CapabilityOutcome.AgentDeferred -> WorkflowStatuses.FAILED
        is CapabilityOutcome.AgentNeedsResearch -> WorkflowStatuses.NEEDS_RESEARCH
        is CapabilityOutcome.HumanCheckpointRequired -> WorkflowStatuses.WAITING_HUMAN
    }

    /**
     * Routes a NEEDS_RESEARCH step to a Searcher attempt and re-arms the initial
     * attempt on the same worktree. Returns `true` when the step was re-armed
     * (and must be re-run), `false` when it stays blocked.
     */
    private fun routeNeedsResearch(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        step: WorkflowStepDefinition,
        outcome: CapabilityOutcome.AgentNeedsResearch,
        repoRoot: Path,
        ticket: String?,
    ): Boolean {
        val router = needsResearchRouter ?: return false
        return runCatching {
            router.route(scope, namespaceId, workflowId, step, outcome) { searcherStep ->
                capabilityExecutionService.resolveAndRecord(scope, namespaceId, workflowId, searcherStep, repoRoot, ticket)
            }.reArmed
        }.getOrElse { error ->
            logger.warn(error) {
                "NEEDS_RESEARCH routing failed for step '${step.id}' of workflow '$workflowId'; leaving it blocked"
            }
            false
        }
    }

    /**
     * Resolves the steps suspended on a human interaction from the durable
     * `human_interactions` journal: a closed interaction with `approve` completes
     * the step, any other decision fails it. Returns false when at least one step
     * is still awaiting an answer (the session stays suspended).
     */
    private fun resolveWaitingHuman(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        steps: List<WorkflowStepDefinition>,
        statuses: SessionProgress,
        expectedRevision: Int,
    ): Boolean {
        val interactions = interactionRepository.list(scope, namespaceId, workflowId, openOnly = false)
        var allResolved = true
        for (step in steps) {
            if (statuses.statuses[step.id] != WorkflowStatuses.WAITING_HUMAN) continue
            val interaction = interactions.filter { it.stepId == step.id }.maxByOrNull { it.revision }
            val terminal = humanDecision(interaction)
            if (terminal == null || interaction == null) {
                allResolved = false
                continue
            }
            transition(scope, namespaceId, workflowId, step, terminal, expectedRevision)
            setStatus(scope, namespaceId, workflowId, step.id, terminal, statuses)
            recordHumanResolutionEvidence(scope, namespaceId, workflowId, step, terminal, interaction)
        }
        return allResolved
    }

    @Suppress("UNCHECKED_CAST")
    private fun humanDecision(interaction: HumanInteractionRecord?): String? {
        if (interaction == null || interaction.status != "closed") return null
        val response = interaction.payload["response"] as? Map<*, *>
        val actionId = response?.get("actionId") as? String
        return if (actionId == "approve") WorkflowStatuses.COMPLETED else WorkflowStatuses.FAILED
    }

    private fun setStatus(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        stepId: String,
        status: String,
        progress: SessionProgress,
    ) {
        if (progress.statuses[stepId] == status) return
        progress.statuses[stepId] = status
        val now = nowIso()
        when (status) {
            WorkflowStatuses.RUNNING -> progress.startedAt.putIfAbsent(stepId, now)
            WorkflowStatuses.COMPLETED,
            WorkflowStatuses.FAILED,
            WorkflowStatuses.CANCELLED,
            -> {
                progress.startedAt.putIfAbsent(stepId, now)
                progress.completedAt.putIfAbsent(stepId, now)
                progress.waitingQuestions.remove(stepId)
            }
        }
        repository.upsertStepState(
            scope,
            WorkflowStepStateRecord(
                namespaceId = namespaceId,
                workflowId = workflowId,
                stepId = stepId,
                revision = 1,
                status = status,
                payload = progress.payload(stepId),
            ),
        )
        // Persist and announce every meaningful step transition. The cockpit
        // treats SSE as an invalidation hint and reloads this authoritative
        // projection; it never infers progress from elapsed time or silence.
        persistProgressProjection(scope, namespaceId, workflowId, progress)
        sseHub.publish(namespaceId, mapOf("workflowId" to workflowId, "namespaceId" to namespaceId))
    }

    /**
     * Atomic claim of a ready step: a graph-native compare-and-swap transitions
     * the step to `running` only when it is currently `ready`/`pending`, so two
     * racing runs can never both own the step. The in-memory progress is updated
     * only once the CAS succeeded.
     */
    private fun claimStep(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        stepId: String,
        progress: SessionProgress,
    ): Boolean {
        val startedAt = progress.startedAt[stepId] ?: nowIso()
        val claimed = repository.claimStep(
            scope,
            namespaceId,
            workflowId,
            stepId,
            fromStatuses = listOf(WorkflowStatuses.READY, WorkflowStatuses.PENDING),
            payload = progress.payload(stepId) + mapOf("startedAt" to startedAt),
        )
        if (!claimed) {
            logger.warn { "Step '$stepId' of workflow '$workflowId' is already claimed; skipping execution." }
            return false
        }
        progress.statuses[stepId] = WorkflowStatuses.RUNNING
        progress.startedAt[stepId] = startedAt
        progress.waitingQuestions.remove(stepId)
        // The successful CAS owns execution; publish the authoritative RUNNING
        // projection before any remote case creation/message dispatch occurs.
        persistProgressProjection(scope, namespaceId, workflowId, progress)
        sseHub.publish(scope, namespaceId, mapOf("workflowId" to workflowId, "namespaceId" to namespaceId))
        return true
    }

    private fun projectAgentObservation(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        stepId: String,
        progress: SessionProgress,
        update: AgentObservationUpdate,
    ) {
        when (update.status) {
            WorkflowStatuses.WAITING_HUMAN -> {
                progress.statuses[stepId] = WorkflowStatuses.WAITING_HUMAN
                progress.waitingQuestions[stepId] = buildMap {
                    update.questionRef?.let { put("questionRef", it) }
                    update.text?.let { put("text", it) }
                    update.type?.let { put("type", it) }
                    if (update.options.isNotEmpty()) put("options", update.options)
                    put("attemptId", CapabilityExecutionService.stableAttemptId(workflowId, stepId))
                    put("caseId", CapabilityExecutionService.stableCaseId(workflowId, stepId))
                }
            }
            WorkflowStatuses.RUNNING -> {
                progress.statuses[stepId] = WorkflowStatuses.RUNNING
                progress.waitingQuestions.remove(stepId)
            }
            else -> return
        }
        repository.upsertStepState(
            scope,
            WorkflowStepStateRecord(namespaceId, workflowId, stepId, 1, progress.statuses.getValue(stepId), progress.payload(stepId)),
        )
        persistProgressProjection(scope, namespaceId, workflowId, progress)
        sseHub.publish(scope, namespaceId, mapOf("workflowId" to workflowId, "namespaceId" to namespaceId))
    }

    /**
     * Emergency failure recovery: the failure evidence, the FAILED transition and
     * the step status are written in ONE fresh short transaction so they succeed
     * even when the prior query/operation failed (e.g. the external execution had
     * outlived a transaction). The original cause is never masked by an unhandled
     * 500.
     */
    private fun recordStepFailure(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        step: WorkflowStepDefinition,
        error: Exception,
        expectedRevision: Int,
        statuses: SessionProgress,
    ) {
        logger.warn(error) { "Step '${step.id}' of workflow '$workflowId' failed: ${error.message ?: error.toString()}" }
        runCatching {
            newTransaction {
                recordFailureEvidence(scope, namespaceId, workflowId, step, error)
                transition(scope, namespaceId, workflowId, step, WorkflowStatuses.FAILED, expectedRevision)
                setStatus(scope, namespaceId, workflowId, step.id, WorkflowStatuses.FAILED, statuses)
            }
        }.onFailure { recoveryError ->
            logger.error(recoveryError) {
                "Failure recovery for step '${step.id}' of workflow '$workflowId' could not be persisted: " +
                    (error.message ?: error.toString())
            }
            // Keep the fail-closed in-memory classification even if the recovery
            // write itself could not be persisted.
            statuses.statuses[step.id] = WorkflowStatuses.FAILED
            statuses.completedAt.putIfAbsent(step.id, nowIso())
        }
    }

    private fun transition(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        step: WorkflowStepDefinition,
        status: String,
        expectedRevision: Int,
        evidenceIds: List<String> = emptyList(),
    ) {
        repository.appendTransition(
            scope,
            namespaceId,
            workflowId,
            transitionId = UUID.randomUUID().toString(),
            request = WorkflowTransitionRequest(
                requestId = UUID.randomUUID().toString(),
                workflowId = workflowId,
                stepId = step.id,
                expectedRevision = expectedRevision,
                requestedStatus = status,
                evidenceIds = evidenceIds,
            ),
            fromStepId = step.id,
            toStepId = step.id,
            payload = buildMap {
                put("kind", "session-step")
                put("status", status)
                if (evidenceIds.isNotEmpty()) put("evidenceIds", evidenceIds)
            },
        )
    }

    private fun recordStepEvidence(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        step: WorkflowStepDefinition,
        status: String,
        execution: CapabilityExecution,
    ) {
        evidenceRepository.append(
            scope,
            namespaceId,
            workflowId,
            WorkflowEvidenceItem(
                evidenceId = UUID.randomUUID().toString(),
                namespaceId = namespaceId,
                workflowId = workflowId,
                stepId = step.id,
                kind = "session-step",
                outcome = status,
                source = mapOf(
                    "kind" to "factory-session-sequencer",
                    "responsibility" to step.responsibility.kind.wire,
                    "name" to step.responsibility.name,
                ),
                facts = mapOf(
                    "stepId" to step.id,
                    "status" to status,
                    "attemptId" to execution.attemptId,
                    "evidenceId" to execution.evidenceId,
                ),
                idempotencyKey = null,
                createdAt = null,
            ),
        )
    }

    private fun recordFailureEvidence(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        step: WorkflowStepDefinition,
        error: Exception,
    ) {
        evidenceRepository.append(
            scope,
            namespaceId,
            workflowId,
            WorkflowEvidenceItem(
                evidenceId = UUID.randomUUID().toString(),
                namespaceId = namespaceId,
                workflowId = workflowId,
                stepId = step.id,
                kind = "session-step",
                outcome = WorkflowStatuses.FAILED,
                source = mapOf("kind" to "factory-session-sequencer"),
                facts = mapOf(
                    "stepId" to step.id,
                    "status" to WorkflowStatuses.FAILED,
                    "code" to "STEP_EXECUTION_ERROR",
                    "message" to (error.message ?: error.toString()),
                ),
                idempotencyKey = null,
                createdAt = null,
            ),
        )
    }

    private fun recordHumanResolutionEvidence(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        step: WorkflowStepDefinition,
        status: String,
        interaction: HumanInteractionRecord,
    ) {
        evidenceRepository.append(
            scope,
            namespaceId,
            workflowId,
            WorkflowEvidenceItem(
                evidenceId = UUID.randomUUID().toString(),
                namespaceId = namespaceId,
                workflowId = workflowId,
                stepId = step.id,
                kind = "human-decision",
                outcome = if (status == WorkflowStatuses.COMPLETED) "pass" else "fail",
                source = mapOf("kind" to "factory-human", "interactionId" to interaction.interactionId),
                facts = mapOf("stepId" to step.id, "status" to status, "interactionId" to interaction.interactionId),
                idempotencyKey = "human:$workflowId:${interaction.interactionId}",
                createdAt = null,
            ),
        )
    }

    private fun persistProgressProjection(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        progress: SessionProgress,
    ) {
        val instance = repository.findInstance(scope, namespaceId, workflowId) ?: return
        val projection = instance.projection.toMutableMap()
        val projectedSteps = (projection["steps"] as? List<*>)?.mapNotNull { raw ->
            val step = (raw as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value }?.toMutableMap()
                ?: return@mapNotNull null
            val stepId = step["id"] as? String ?: return@mapNotNull step
            val status = progress.statuses[stepId] ?: return@mapNotNull step
            step["status"] = status
            progress.startedAt[stepId]?.let { step["startedAt"] = it }
            progress.completedAt[stepId]?.let { step["completedAt"] = it }
            elapsedMs(progress.startedAt[stepId], progress.completedAt[stepId])?.let { step["durationMs"] = it }
            progress.waitingQuestions[stepId]?.let { step["waitingQuestion"] = it } ?: step.remove("waitingQuestion")
            step
        } ?: return
        projection["steps"] = projectedSteps
        projection["status"] = when {
            progress.statuses.values.any { it == WorkflowStatuses.WAITING_HUMAN } -> WorkflowStatuses.WAITING_HUMAN
            progress.statuses.values.any { it == WorkflowStatuses.RUNNING } -> WorkflowStatuses.RUNNING
            else -> projection["status"] ?: WorkflowStatuses.PENDING
        }
        val nextInstance = instance.instance.toMutableMap()
        nextInstance["steps"] = projectedSteps.map { mapOf("id" to it["id"], "status" to it["status"]) }
        nextInstance["status"] = projection["status"]
        nextInstance["updatedAt"] = nowIso()
        val next = instance.copy(
            revision = instance.revision + 1,
            instance = nextInstance,
            projection = projection,
        )
        nextInstance["revision"] = next.revision
        if (!repository.updateInstance(scope, namespaceId, workflowId, instance.revision, next)) {
            throw workflowException(WorkflowErrorCodes.REVISION_CONFLICT, "Intermediate projection revision conflict.")
        }
        val existingProjection = repository.findProjection(scope, namespaceId, workflowId)
        repository.publishProjection(
            scope,
            WorkflowProjectionRecord(
                namespaceId = namespaceId,
                workflowId = workflowId,
                schemaVersion = (projection["schemaVersion"] as? String) ?: "2",
                revision = 0,
                projectionHash = CanonicalHash.workflowProjectionHash(projection),
                status = projection["status"] as? String ?: WorkflowStatuses.PENDING,
                projection = projection,
                instance = nextInstance,
                governanceMode = existingProjection?.governanceMode ?: "governed",
                definitionVersion = existingProjection?.definitionVersion,
                definitionHash = existingProjection?.definitionHash,
                relations = existingProjection?.relations,
                controllerExecution = existingProjection?.controllerExecution,
                lifecycleState = "active",
            ),
            expectedRevision = null,
        )
    }

    private fun persistProjection(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        steps: List<WorkflowStepDefinition>,
        progress: SessionProgress,
        sessionStatus: String,
        ticket: String?,
    ): Int? {
        val instance = repository.findInstance(scope, namespaceId, workflowId) ?: return null
        val nextInstance = instance.instance.toMutableMap()
        nextInstance["status"] = sessionStatus
        nextInstance["steps"] = steps.map { mapOf("id" to it.id, "status" to (progress.statuses[it.id] ?: WorkflowStatuses.PENDING)) }
        nextInstance["updatedAt"] = nowIso()
        val projection = instance.projection.toMutableMap()
        projection["status"] = sessionStatus
        // Persist the ticket so a later resume (which only carries the workflowId)
        // and the branch-naming context keep reaching it.
        if (ticket != null) {
            nextInstance["ticket"] = ticket
            projection["ticket"] = ticket
        }
        // Multi-lane timeline projection: one entry per step carrying its lane
        // (agent|code|human) derived from the responsibility kind, the actor
        // name, the status and the optional execution window.
        projection["steps"] = steps.map { step ->
            val status = progress.statuses[step.id] ?: WorkflowStatuses.PENDING
            val startedAt = progress.startedAt[step.id]
            val completedAt = progress.completedAt[step.id]
            linkedMapOf<String, Any?>(
                "id" to step.id,
                "name" to step.name,
                "status" to status,
                "lane" to step.responsibility.kind.wire,
                "dependsOn" to step.dependsOn,
                "responsibility" to step.responsibility.toJson(),
                "startedAt" to startedAt,
                "completedAt" to completedAt,
                "durationMs" to elapsedMs(startedAt, completedAt),
                "waitingQuestion" to progress.waitingQuestions[step.id],
            ).filterValues { it != null }
        }
        val next = instance.copy(revision = instance.revision + 1, instance = nextInstance, projection = projection)
        // The instance document mirrors the row revision (the transition policy
        // asserts `instance["revision"] == record.revision`), so keep the two in
        // step on every projection refresh.
        nextInstance["revision"] = next.revision
        // Strict CAS verification: the instance projection refresh must win its
        // compare-and-swap. A stale revision means another writer (or a stale run)
        // changed the instance concurrently; silently ignoring it would publish a
        // projection that does not reflect the durable state.
        if (!repository.updateInstance(scope, namespaceId, workflowId, instance.revision, next)) {
            throw workflowException(WorkflowErrorCodes.REVISION_CONFLICT, "Instance projection revision conflict.")
        }

        runCatching {
            val existingProjection = repository.findProjection(scope, namespaceId, workflowId)
            repository.publishProjection(
                scope,
                WorkflowProjectionRecord(
                    namespaceId = namespaceId,
                    workflowId = workflowId,
                    schemaVersion = (projection["schemaVersion"] as? String) ?: "2",
                    revision = 0,
                    projectionHash = CanonicalHash.workflowProjectionHash(projection),
                    status = sessionStatus,
                    projection = projection,
                    instance = nextInstance,
                    governanceMode = existingProjection?.governanceMode ?: "governed",
                    definitionVersion = existingProjection?.definitionVersion
                        ?: instance.instance["definitionVersion"] as? String,
                    definitionHash = existingProjection?.definitionHash
                        ?: instance.instance["definitionHash"] as? String,
                    relations = existingProjection?.relations,
                    controllerExecution = existingProjection?.controllerExecution,
                    lifecycleState = "active",
                ),
                expectedRevision = null,
            )
        }
        return next.revision
    }

    private fun activeInstance(scope: TenantScope, namespaceId: String, workflowId: String): WorkflowInstanceRecord {
        val instance = repository.findInstance(scope, namespaceId, workflowId)
            ?: throw workflowException(WorkflowErrorCodes.WORKFLOW_NOT_FOUND)
        if (instance.status != "active") throw workflowException(WorkflowErrorCodes.WORKFLOW_REMOVED)
        return instance
    }

    private fun resolveDefinition(scope: TenantScope, instance: WorkflowInstanceRecord) =
        run {
            val workflowType = instance.instance["workflowType"] as? String
            val version = instance.instance["definitionVersion"] as? String
            val record = if (workflowType != null && version != null) {
                repository.findDefinition(scope, workflowType, version)
            } else {
                null
            } ?: throw workflowException(WorkflowErrorCodes.WORKFLOW_DEFINITION_NOT_FOUND)
            if (record.definitionHash != instance.instance["definitionHash"]) {
                throw workflowException(WorkflowErrorCodes.WORKFLOW_DEFINITION_MISMATCH)
            }
            record.toPolicyDefinition()
        }

    /** Elapsed milliseconds between two ISO-8601 instants, or null when incomplete. */
    private fun elapsedMs(startedAt: String?, completedAt: String?): Long? {
        if (startedAt == null || completedAt == null) return null
        return runCatching { Duration.between(Instant.parse(startedAt), Instant.parse(completedAt)).toMillis() }
            .getOrNull()
    }

    private companion object {
        /**
         * Serialises the runs of one workflow in this process (the embedded
         * engine is single-process): the in-process sequencer is the only owner
         * of a step's `running` state, so two racing runs must not both execute
         * the same ready step.
         */
        val runLocks = ConcurrentHashMap<String, ReentrantLock>()
    }
}
