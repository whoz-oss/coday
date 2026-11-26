package io.whozoss.factory.workflow.service

import io.whozoss.factory.agentattempt.domain.AgentStepResultStatus
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
import io.whozoss.factory.verification.redaction.SecretRedaction
import io.whozoss.factory.workflow.domain.CanonicalHash
import io.whozoss.factory.workflow.domain.HumanInteractionRecord
import io.whozoss.factory.workflow.domain.ResponsibilityKind
import io.whozoss.factory.workflow.domain.SessionSequencer
import io.whozoss.factory.workflow.domain.WorkflowRouting
import io.whozoss.factory.workflow.domain.routingJson
import io.whozoss.factory.workflow.domain.WorkflowErrorCodes
import io.whozoss.factory.workflow.domain.WorkflowEvidenceItem
import io.whozoss.factory.workflow.domain.WorkflowInstanceRecord
import io.whozoss.factory.workflow.domain.WorkflowProjectionRecord
import io.whozoss.factory.workflow.domain.WorkflowStatuses
import io.whozoss.factory.workflow.domain.WorkflowStepDefinition
import io.whozoss.factory.workflow.domain.WorkflowStepStateRecord
import io.whozoss.factory.workflow.domain.WorkflowTransitionRequest
import io.whozoss.factory.workflow.config.ExternalPollingProperties
import io.whozoss.factory.workflow.domain.nowIso
import io.whozoss.factory.workflow.domain.workflowException
import io.whozoss.factory.workflow.persistence.HumanInteractionRepository
import io.whozoss.factory.workflow.persistence.WorkflowEvidenceRepository
import io.whozoss.factory.workflow.persistence.WorkflowRepository
import io.whozoss.factory.workflow.sse.WorkflowSseHub
import io.whozoss.factory.workspace.RunWorktreeResolver
import io.whozoss.factory.workspace.WorkspacePrecheckService
import io.whozoss.factory.run.service.RunService
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Lazy
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
    val routing = HashMap<String, Map<String, Any?>>()
    val executions = HashMap<String, Map<String, Any?>>()

    /** Poll state of a step parked in `waiting_external` (scoped to its current execution). */
    val polls = HashMap<String, Map<String, Any?>>()

    /** Persisted payload of a step state: timings plus bounded intermediate question. */
    fun payload(stepId: String): Map<String, Any?> = buildMap {
        startedAt[stepId]?.let { put("startedAt", it) }
        completedAt[stepId]?.let { put("completedAt", it) }
        waitingQuestions[stepId]?.let { put("waitingQuestion", it) }
        routing[stepId]?.let { putAll(it) }
        executions[stepId]?.let { put("execution", it) }
        polls[stepId]?.let { put("poll", it) }
    }

    companion object {
        /** Rebuilds a progress from the durable per-step states (resume / read). */
        fun fromStates(states: Map<String, WorkflowStepStateRecord>): SessionProgress {
            val progress = SessionProgress()
            for ((stepId, state) in states) {
                (state.payload["execution"] as? Map<*, *>)?.let { execution ->
                    progress.executions[stepId] = execution.entries.associate { it.key.toString() to it.value }
                }
                (state.payload["poll"] as? Map<*, *>)?.let { poll ->
                    progress.polls[stepId] = poll.entries.associate { it.key.toString() to it.value }
                }
                val route = state.payload.filterKeys { it in setOf("routeDecision", "executed", "activation") }
                if (route.isNotEmpty()) progress.routing[stepId] = route
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
    /**
     * Optional read-only workspace pre-check (Lot F). When present it runs BEFORE
     * the run's root case is created, refusing a run whose namespace/Git/workstream
     * prerequisites are missing with an actionable error (never a repair). Pure
     * unit tests may omit it.
     */
    private val workspacePrecheckService: WorkspacePrecheckService? = null,
    /**
     * Optional run-worktree resolver (Lot F). When present, every `code` step's
     * `repoRoot` is explicitly bound to the run's validated Git worktree (proven
     * usable by the waiter) instead of the namespace repo root. Pure unit tests
     * may omit it and keep the caller-provided root.
     */
    private val runWorktreeResolver: RunWorktreeResolver? = null,
    @param:Lazy
    @param:Autowired(required = false)
    private val runService: RunService? = null,
    private val outboxDrainService: io.whozoss.factory.agentattempt.service.OutboxDrainService? = null,
    private val outcomeTransitionService: OutcomeTransitionService? = null,
    /** Polling policy of the steps parked in `waiting_external`. */
    private val pollingProperties: ExternalPollingProperties = ExternalPollingProperties(),
    /** Clock of every poll timestamp; injectable so the 30-minute window is testable. */
    private val clock: Clock = Clock.systemUTC(),
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

    /**
     * Runs (or resumes) the session DAG to a terminal state or to a human suspension.
     * Call after caller transaction cleanup or from an independent execution context:
     * NOT_SUPPORTED does not guarantee safe SDN resource restoration when invoked
     * under an active caller transaction, including inline from afterCommit.
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    fun runSession(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        repoRoot: Path,
        ticket: String? = null,
    ): SessionRunResult = withWorkflowLock(scope, namespaceId, workflowId) {
        try {
            runSessionLocked(scope, namespaceId, workflowId, repoRoot, ticket)
        } catch (transient: io.whozoss.factory.workspace.TransientWorkspaceAdoptionException) {
            // Covers synchronous human resumptions after caller transaction cleanup,
            // independent-context continuations after commit, and the worker.
            // A claimed/pending continuation is left untouched; only its fenced owner defers it.
            outboxDrainService?.adoptionDeferred(scope, namespaceId, workflowId, repoRoot, ticket)
            throw transient
        }
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
        val expectedRevision = { repository.findInstance(scope, namespaceId, workflowId)?.revision ?: instance.revision }
        // The ticket may be supplied by the run request, or have travelled with
        // the start command / instance relations. Either way it must reach the
        // step execution context (agent brief) and be persisted on the instance.
        val effectiveTicket = ticket?.takeIf { it.isNotBlank() } ?: instanceTicket(instance)
        val workflowType = instance.instance["workflowType"] as? String
        val steps = resolveDefinition(scope, instance).steps
        if (steps.isEmpty()) {
            throw workflowException(WorkflowErrorCodes.INVALID_START_REQUEST, "The session has no steps.")
        }
        // Lot F read-only pre-check: BEFORE the run's root case is created, refuse
        // a run whose namespace/workstream/agents prerequisites are missing.
        // Git worktree association is required only for Forge/plugin-governed runs
        // (`execution.plugin == "forge"`, parent `runId`, or a `code` step); a
        // classical agent/human workflow run can execute in a namespace without a
        // Git repository association.
        val requiresGitWorkspace = requiresGitWorkspace(instance, steps)
        val callerExternalUserId = instanceActorId(instance)
        workspacePrecheckService?.precheck(
            scope = scope,
            namespaceId = namespaceId,
            callerExternalUserId = callerExternalUserId,
            requiredAgents = steps
                .filter { it.responsibility.kind == ResponsibilityKind.AGENT }
                .mapNotNull { it.responsibility.name },
            requireGitWorkspace = requiresGitWorkspace,
        )
        check(steps.none { it.outcomeTransitions != null } || outcomeTransitionService != null) { "OUTCOME_ROUTER_UNAVAILABLE" }
        outcomeTransitionService?.initialize(scope, namespaceId, workflowId, steps)
        val statuses = loadProgress(scope, namespaceId, workflowId, steps)
        // RUNNING steps retain the terminal-only fence; waiting steps can also resume an answered turn/successor.
        for (step in steps.filter { it.responsibility.kind == ResponsibilityKind.AGENT }) {
            val currentStatus = statuses.statuses[step.id]
            val canReplayOrResume = (currentStatus == WorkflowStatuses.RUNNING &&
                capabilityExecutionService.hasFinalizedAttempt(scope, namespaceId, workflowId, step.id)) ||
                (currentStatus == WorkflowStatuses.WAITING_HUMAN &&
                    capabilityExecutionService.canResumeWaitingAgentStep(scope, namespaceId, workflowId, step.id))
            if (canReplayOrResume) setStatus(scope, namespaceId, workflowId, step.id, WorkflowStatuses.READY, statuses)
        }
        var suspended = false
        // Lot F: the repo root every `code` step runs against. It starts as the
        // caller-provided root and is re-bound to the run's validated worktree
        // before the first code step (once the root case exists).
        var effectiveRepoRoot = repoRoot
        val guard = steps.size * (if (steps.any { it.outcomeTransitions != null }) 48 else 4) + 8
        var iterations = 0
        run {
            while (iterations++ < guard) {
                outcomeTransitionService?.advance(scope, namespaceId, workflowId, steps, effectiveRepoRoot.toString(), effectiveTicket)
                val correction = repository.findInstance(scope, namespaceId, workflowId)?.projection?.get("correction") as? Map<*, *>
                if (correction?.get("status") == "waiting_human") {
                    suspended = true
                    return@run
                }
                reloadAuthoritativeProgress(scope, namespaceId, workflowId, steps, statuses)
                applyEvaluation(scope, namespaceId, workflowId, steps, statuses)
                // A step whose 30-minute polling window elapsed fails now; it needs no execution, so it is
                // handled even while another branch waits for a human.
                if (failExpiredExternalWaits(scope, namespaceId, workflowId, steps, statuses, expectedRevision())) continue
                if (statuses.statuses.values.any { it == WorkflowStatuses.WAITING_HUMAN }) {
                    if (!resolveWaitingHuman(scope, namespaceId, workflowId, steps, statuses, expectedRevision())) {
                        suspended = true
                        return@run
                    }
                    continue
                }
                val readyId = SessionSequencer.readySteps(steps, statuses.statuses).firstOrNull()
                    ?: dueExternalWait(steps, statuses)
                    ?: steps.firstOrNull { step ->
                        statuses.statuses[step.id] == WorkflowStatuses.NEEDS_RESEARCH &&
                            capabilityExecutionService.hasAcceptedFinalResult(scope, namespaceId, workflowId, step.id)
                    }?.id
                    ?: return@run
                val step = steps.first { it.id == readyId }
                if (step.responsibility.kind == ResponsibilityKind.AGENT && steps.any { it.responsibility.kind == ResponsibilityKind.CODE }) {
                    try {
                        // Durable READY state remains replayable if the process dies here, before
                        // claimStep. There is no RUNNING-without-attempt provisioning window.
                        capabilityExecutionService.prepareAgentWorkspace(scope, namespaceId, workflowId, step)
                    } catch (interrupted: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw interrupted
                    } catch (transient: io.whozoss.factory.workspace.TransientWorkspaceAdoptionException) {
                        // Leave READY; the outbox owns bounded durable resumption, not an agent retry.
                        evidenceRepository.append(scope, namespaceId, workflowId, WorkflowEvidenceItem(
                            evidenceId = UUID.randomUUID().toString(), namespaceId = namespaceId,
                            workflowId = workflowId, stepId = step.id, kind = "workspace-adoption",
                            outcome = "deferred", source = mapOf("kind" to "factory-session-sequencer"),
                            facts = mapOf("code" to "WORKSPACE_ADOPTION_DEFERRED", "executed" to false,
                                "message" to "Workspace adoption temporarily unavailable; intent retained for bounded durable continuation."),
                            idempotencyKey = "workspace-adoption-deferred:$workflowId:${step.id}", createdAt = null,
                        ))
                        sseHub.publish(scope, namespaceId, mapOf("workflowId" to workflowId, "namespaceId" to namespaceId))
                        throw transient
                    } catch (error: Exception) {
                        if (claimStep(scope, namespaceId, workflowId, step.id, statuses)) {
                            recordStepFailure(scope, namespaceId, workflowId, step, error, expectedRevision(), statuses)
                        }
                        continue
                    }
                }
                // Lot F: bind a `code` step's repoRoot to the run's worktree
                // explicitly. Resolved for each ready code step, right before it executes
                // (after the entry agent step created the root case) and never
                // inside a transaction; the waiter either proves the exact
                // worktree or throws — it never silently uses another directory.
                var workspaceFacts: Map<String, Any?> = emptyMap()
                if (step.responsibility.kind == ResponsibilityKind.CODE) {
                    val resolvedRoot = try {
                        // The entry agent may have reserved rootCaseId since this session began.
                        val currentInstance = activeInstance(scope, namespaceId, workflowId)
                        val worktreeOwnerId = parentRunId(currentInstance) ?: workflowId
                        val binding = runWorktreeResolver?.resolveBinding(
                            scope, worktreeOwnerId, repoRoot,
                            namespaceId = namespaceId,
                            requireBranch = step.responsibility.name != io.whozoss.factory.capability.PREPARE_WORK_BRANCH,
                            expectedCaseId = currentInstance.rootCaseId,
                            branchExpectation = { environmentId ->
                                // Only Factory control-plane preparation is an expectation, not
                                // provisioning-time branch observations or agent result prose.
                                // Owner/current fragment only: prior fragments are not searched.
                                // Without such a proof the resolver requires an attached branch.
                                (evidenceRepository.list(scope, namespaceId, worktreeOwnerId) +
                                    if (worktreeOwnerId != workflowId) evidenceRepository.list(scope, namespaceId, workflowId) else emptyList())
                                    .lastOrNull { it.kind == "branch-preparation" && it.outcome == "pass" &&
                                        it.source?.get("kind") == "factory-control-plane" &&
                                        environmentId != null && it.facts["environmentId"] == environmentId }
                                    ?.facts?.get("branch") as? String
                            },
                        )
                        workspaceFacts = binding?.facts().orEmpty()
                        binding?.root ?: effectiveRepoRoot
                    } catch (error: Exception) {
                        // Root resolution is part of the code step, not runner infrastructure.
                        // Claim first so the failure has one authoritative owner, then persist the
                        // same diagnostic shape as any other pre-execution failure. No command ran.
                        if (claimStep(scope, namespaceId, workflowId, step.id, statuses)) {
                            recordCodePreparationFailure(
                                scope, namespaceId, workflowId, step, effectiveRepoRoot,
                                error, expectedRevision(), statuses,
                            )
                        }
                        continue
                    }
                    effectiveRepoRoot = resolvedRoot
                }
                executeStep(
                    scope,
                    namespaceId,
                    workflowId,
                    steps,
                    statuses,
                    step,
                    effectiveRepoRoot,
                    effectiveTicket,
                    expectedRevision(),
                    workflowType,
                    workspaceFacts,
                )?.let { terminal ->
                    if (terminal == WorkflowStatuses.WAITING_HUMAN) {
                        suspended = true
                        return@run
                    }
                }
            }
        }
        val evaluatedStatus = SessionSequencer.terminalStatus(steps, statuses.statuses)
        val sessionStatus = if (suspended) WorkflowStatuses.WAITING_HUMAN else evaluatedStatus
        val committedRevision = persistProjection(scope, namespaceId, workflowId, steps, statuses, sessionStatus, effectiveTicket)
        scheduleNextPoll(scope, namespaceId, workflowId, steps, statuses, effectiveRepoRoot, effectiveTicket)
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
        val sessionResult = SessionRunResult(namespaceId, workflowId, sessionStatus, statusesOf(steps, statuses.statuses))
        runService?.onFragmentSessionUpdated(
            scope = scope,
            namespaceId = namespaceId,
            fragmentWorkflowId = workflowId,
            sessionResult = sessionResult,
            repoRoot = effectiveRepoRoot,
            sessionRunService = this,
        )
        return sessionResult
    }

    /** The parent Run id carried by a fragment workflow instance, or null for standalone workflows. */
    private fun parentRunId(instance: WorkflowInstanceRecord): String? {
        (instance.instance["runId"] as? String)?.takeIf { it.isNotBlank() }?.let { return it }
        val relations = instance.instance["relations"] as? Map<*, *>
        return (relations?.get("runId") as? String)?.takeIf { it.isNotBlank() }
    }

    /**
     * True when the session requires a provisioned Git worktree:
     * - it belongs to a parent Run (`runId` present), OR
     * - it declares an execution plugin (`execution.plugin`, e.g. `"forge"`), OR
     * - it contains at least one `code` step that executes in a worktree.
     * Classical agent/human runs without a Git repo association do not require one.
     */
    private fun requiresGitWorkspace(
        instance: WorkflowInstanceRecord,
        steps: List<WorkflowStepDefinition>,
    ): Boolean {
        if (parentRunId(instance) != null) return true
        val executionPlugin = (instance.instance["execution"] as? Map<*, *>)?.get("plugin") as? String
        if (!executionPlugin.isNullOrBlank()) return true
        return steps.any { it.responsibility.kind == ResponsibilityKind.CODE }
    }

    /** The ticket carried by a persisted instance (top-level or in its relations). */
    private fun instanceTicket(instance: WorkflowInstanceRecord): String? {
        (instance.instance["ticket"] as? String)?.takeIf { it.isNotBlank() }?.let { return it }
        val relations = instance.instance["relations"] as? Map<*, *>
        return (relations?.get("ticket") as? String)?.takeIf { it.isNotBlank() }
    }

    /** The authenticated actor identity persisted on the workflow instance. */
    private fun instanceActorId(instance: WorkflowInstanceRecord): String? {
        val controllerRequest = instance.instance["controllerRequest"] as? Map<*, *>
        return (controllerRequest?.get("actorId") as? String)?.takeIf { it.isNotBlank() }
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
        progress.routing.putAll(restored.routing)
        progress.executions.putAll(restored.executions)
        progress.polls.putAll(restored.polls)
        return progress
    }

    private fun reloadAuthoritativeProgress(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        steps: List<WorkflowStepDefinition>,
        progress: SessionProgress,
    ) {
        val durable = repository.findStepStates(scope, namespaceId, workflowId).associateBy { it.stepId }
        for (step in steps) {
            val state = durable[step.id] ?: continue
            progress.statuses[step.id] = state.status
            val execution = (state.payload["execution"] as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value }
            if (progress.executions[step.id]?.get("executionId") != execution?.get("executionId")) {
                progress.startedAt.remove(step.id)
                progress.completedAt.remove(step.id)
                progress.routing.remove(step.id)
                progress.waitingQuestions.remove(step.id)
                progress.polls.remove(step.id)
            }
            if (execution != null) progress.executions[step.id] = execution
            // A poll window belongs to ONE step execution: a poll of another execution is never reused.
            (state.payload["poll"] as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value }
                ?.takeIf { it["executionId"] == execution?.get("executionId") }
                ?.let { progress.polls[step.id] = it } ?: progress.polls.remove(step.id)
            val route = state.payload.filterKeys { it in setOf("routeDecision", "executed", "activation") }
            if (route.isNotEmpty()) progress.routing[step.id] = route
            (state.payload["startedAt"] as? String)?.let { progress.startedAt[step.id] = it }
            (state.payload["completedAt"] as? String)?.let { progress.completedAt[step.id] = it }
        }
    }

    private fun applyEvaluation(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        steps: List<WorkflowStepDefinition>,
        progress: SessionProgress,
    ) {
        // Revalidate durable activation on every replay; only explicitly unselected targets may be skipped.
        for (step in steps.filter { it.decision != null && progress.statuses[it.id] == WorkflowStatuses.COMPLETED }) {
            val accepted = progress.routing[step.id]?.get("routeDecision") as? Map<*, *>
                ?: error("ROUTING_STATE_MISSING: completed decision '${step.id}' has no durable activation record")
            val selected = (accepted["selectedRoutes"] as? List<*>)?.map { it as? String ?: error("ROUTING_SELECTION_INVALID") }
            val decision = requireNotNull(step.decision)
            check(decision.accepts(selected)) { "ROUTING_SELECTION_INVALID" }
            val result = capabilityExecutionService.acceptedStepResult(scope, namespaceId, workflowId, step.id,
                accepted["attemptId"] as? String ?: error("ROUTING_ATTEMPT_MISSING"))
            check(result != null && result.resultId == accepted["resultId"] && result.resultHash == accepted["resultHash"] &&
                result.status == AgentStepResultStatus.PASS &&
                result.selectedRoutes?.toSet() == selected?.toSet()) { "ROUTING_RESULT_CONFLICT" }
            for (route in decision.routes) {
                val active = route.name in selected.orEmpty()
                check(if (active) progress.statuses[route.target] != WorkflowStatuses.SKIPPED else progress.statuses[route.target] == WorkflowStatuses.SKIPPED) { "ROUTING_ACTIVATION_CONFLICT" }
            }
        }
        val inactiveTargets = steps.filter { it.decision != null }.flatMap { step ->
            val accepted = progress.routing[step.id]?.get("routeDecision") as? Map<*, *>
            val selection = accepted?.get("selectedRoutes") as? List<*>
            if (progress.statuses[step.id] != WorkflowStatuses.COMPLETED || selection == null) emptyList()
            else step.decision!!.routes.filter { it.name !in selection }.map { it.target }
        }.toSet()
        check(progress.statuses.none { (id, status) -> status == WorkflowStatuses.SKIPPED && id !in inactiveTargets }) { "ROUTING_UNAUTHORIZED_SKIP" }
        val evaluation = SessionSequencer.evaluate(steps, progress.statuses)
        for (stepId in evaluation.blocked) {
            val rootStepId = blockingRootStepId(steps, progress.statuses, stepId)
            setBlockedStatus(scope, namespaceId, workflowId, stepId, rootStepId, progress)
        }
        for (stepId in evaluation.ready) setStatus(scope, namespaceId, workflowId, stepId, WorkflowStatuses.READY, progress)
        for (step in steps) {
            if (progress.statuses[step.id] == WorkflowStatuses.BLOCKED &&
                step.dependsOn.isNotEmpty() &&
                WorkflowRouting.dependenciesSatisfied(step, steps, progress.statuses)
            ) {
                setStatus(scope, namespaceId, workflowId, step.id, WorkflowStatuses.READY, progress)
            }
        }
    }

    private fun blockingRootStepId(
        steps: List<WorkflowStepDefinition>, statuses: Map<String, String>, stepId: String,
    ): String {
        val byId = steps.associateBy { it.id }
        val visited = mutableSetOf<String>()
        fun root(id: String): String {
            if (!visited.add(id)) return id
            val step = byId[id] ?: return id
            val directFailure = step.dependsOn.firstOrNull { statuses[it] in setOf(WorkflowStatuses.FAILED, WorkflowStatuses.CANCELLED, WorkflowStatuses.NEEDS_RESEARCH) }
            if (directFailure != null) return directFailure
            val blocked = step.dependsOn.firstOrNull { statuses[it] == WorkflowStatuses.BLOCKED }
            return if (blocked == null) id else root(blocked)
        }
        return root(stepId)
    }

    private fun setBlockedStatus(
        scope: TenantScope, namespaceId: String, workflowId: String, stepId: String, rootStepId: String,
        progress: SessionProgress,
    ) {
        if (progress.statuses[stepId] == WorkflowStatuses.BLOCKED) return
        progress.statuses[stepId] = WorkflowStatuses.BLOCKED
        repository.upsertStepState(
            scope,
            WorkflowStepStateRecord(
                namespaceId, workflowId, stepId, 1, WorkflowStatuses.BLOCKED,
                progress.payload(stepId) + mapOf("cause" to "dependency_non_success", "rootStepId" to rootStepId, "executed" to false),
            ),
        )
        evidenceRepository.append(
            scope, namespaceId, workflowId,
            WorkflowEvidenceItem(
                UUID.randomUUID().toString(), namespaceId, workflowId, stepId, "session-step", WorkflowStatuses.BLOCKED,
                mapOf("kind" to "factory-session-sequencer"),
                mapOf("stepId" to stepId, "status" to WorkflowStatuses.BLOCKED, "cause" to "dependency_non_success", "rootStepId" to rootStepId, "executed" to false),
                "session-step-blocked:$workflowId:$stepId:$rootStepId", null,
            ),
        )
        outcomeTransitionService?.record(scope, namespaceId, workflowId, stepId,
            progress.executions[stepId]?.get("executionId") as? String, WorkflowStatuses.BLOCKED)
        persistProgressProjection(scope, namespaceId, workflowId, progress)
        sseHub.publish(scope, namespaceId, mapOf("workflowId" to workflowId, "namespaceId" to namespaceId))
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
        workspaceFacts: Map<String, Any?> = emptyMap(),
    ): String? {
        // Atomic claim: only the run that transitions the step `ready -> running`
        // owns its external execution. A concurrent or stale run gets `false` and
        // leaves the step alone. The claim is a short, isolated write.
        if (!claimStep(scope, namespaceId, workflowId, step.id, statuses)) return null
        val executionId = statuses.executions[step.id]?.get("executionId") as? String
        outcomeTransitionService?.record(scope, namespaceId, workflowId, step.id, executionId, WorkflowStatuses.RUNNING)
        reloadAuthoritativeProgress(scope, namespaceId, workflowId, steps, statuses)
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
        // External polling: a pending CI or an agent IN_PROGRESS parks the step (non-terminal, no worker held,
        // no correction budget consumed) or, once its window elapsed, fails it with POLL_TIMEOUT_EXCEEDED.
        if (execution.outcome is CapabilityOutcome.ExternalPending || execution.outcome is CapabilityOutcome.AgentInProgress) {
            return try {
                parkExternalWait(scope, namespaceId, workflowId, step, execution, statuses, executionId, expectedRevision, repoRoot, ticket)
            } catch (schedulingFailure: ExternalWaitSchedulingException) {
                // The park and its wake commit together: nothing was parked, so fail loud (technical failure,
                // no return, no budget) instead of leaving a step that nobody will ever wake.
                reloadAuthoritativeProgress(scope, namespaceId, workflowId, steps, statuses)
                recordStepFailure(scope, namespaceId, workflowId, step, schedulingFailure, expectedRevision, statuses)
                WorkflowStatuses.FAILED
            }
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
            outcomeTransitionService?.record(scope, namespaceId, workflowId, step.id, executionId,
                WorkflowStatuses.NEEDS_RESEARCH, execution.attemptId, execution.evidenceId)
            recordStepEvidence(scope, namespaceId, workflowId, step, WorkflowStatuses.NEEDS_RESEARCH, execution, statuses)
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
        if (terminal == WorkflowStatuses.COMPLETED && step.decision != null) {
            try {
                acceptRouting(scope, namespaceId, workflowId, step, execution, statuses)
            } catch (error: Exception) {
                // A transaction rollback must not leak uncommitted activation through emergency projection writes.
                statuses.routing.clear()
                val restored = SessionProgress.fromStates(repository.findStepStates(scope, namespaceId, workflowId).associateBy { it.stepId })
                statuses.routing.putAll(restored.routing)
                reloadAuthoritativeProgress(scope, namespaceId, workflowId, steps, statuses)
                recordStepFailure(scope, namespaceId, workflowId, step, error, expectedRevision, statuses)
                return WorkflowStatuses.FAILED
            }
        }
        if (executionId != null) {
            val durableExecution = repository.findStepStates(scope, namespaceId, workflowId).single { it.stepId == step.id }
                .payload["execution"] as? Map<*, *>
            check(durableExecution?.get("executionId") == executionId) { "EXECUTION_GENERATION_FENCED" }
        }
        val finalizeStep = {
            setStatus(scope, namespaceId, workflowId, step.id, terminal, statuses)
            outcomeTransitionService?.record(scope, namespaceId, workflowId, step.id, executionId, terminal, execution.attemptId, execution.evidenceId)
            transition(scope, namespaceId, workflowId, step, terminal, expectedRevision, oracleEvidenceIds)
            recordStepEvidence(scope, namespaceId, workflowId, step, terminal, execution, statuses, workspaceFacts)
        }
        // Opted-in ledger + terminal state/evidence commit together. Preserve legacy transaction behavior.
        if (executionId != null) newTransaction { finalizeStep() } else finalizeStep()
        return terminal
    }

    /** Commit decision/result/attempt association and every activation before any branch can run. */
    private fun acceptRouting(
        scope: TenantScope, namespaceId: String, workflowId: String, step: WorkflowStepDefinition,
        execution: CapabilityExecution, progress: SessionProgress,
    ) {
        val decision = requireNotNull(step.decision)
        val attemptId = requireNotNull(execution.attemptId) { "ROUTING_ATTEMPT_MISSING" }
        val result = requireNotNull(capabilityExecutionService.acceptedStepResult(scope, namespaceId, workflowId, step.id, attemptId)) { "ROUTING_RESULT_MISSING" }
        check(result.status == AgentStepResultStatus.PASS && decision.accepts(result.selectedRoutes)) { "ROUTING_SELECTION_INVALID" }
        val proof = evidenceRepository.list(scope, namespaceId, workflowId, step.id)
            .lastOrNull { it.evidenceId == execution.evidenceId && it.facts["attemptId"] == attemptId && it.outcome == "pass" }
        check(proof?.facts?.get("validatorsAccepted") == true) { "ROUTING_VALIDATION_PROOF_MISSING: recovered decision requires verified validator evidence" }
        val selected = decision.routes.map { it.name }.filter { it in result.selectedRoutes.orEmpty() }
        val accepted = mapOf("attemptId" to attemptId, "resultId" to result.resultId, "resultHash" to result.resultHash, "selectedRoutes" to selected)
        val prior = progress.routing[step.id]?.get("routeDecision")
        check(prior == null || prior == accepted) { "ROUTING_DECISION_IMMUTABLE" }
        newTransaction {
            progress.routing[step.id] = mapOf("routeDecision" to accepted)
            // Do not publish intermediate snapshots: the decision and its inactive targets commit together.
            for (route in decision.routes) {
                val active = route.name in selected
                val status = if (active) WorkflowStatuses.PENDING else WorkflowStatuses.SKIPPED
                check(progress.statuses[route.target] in setOf(WorkflowStatuses.PENDING, WorkflowStatuses.SKIPPED, WorkflowStatuses.BLOCKED)) { "ROUTING_BRANCH_ALREADY_STARTED" }
                progress.statuses[route.target] = status
                progress.routing[route.target] = mapOf("executed" to false, "activation" to if (active) "selected" else "not_selected")
                repository.upsertStepState(scope, WorkflowStepStateRecord(namespaceId, workflowId, route.target, 1, status, progress.payload(route.target)))
                if (!active) outcomeTransitionService?.record(scope, namespaceId, workflowId, route.target,
                    progress.executions[route.target]?.get("executionId") as? String, WorkflowStatuses.SKIPPED)
            }
            progress.statuses[step.id] = WorkflowStatuses.COMPLETED
            progress.completedAt[step.id] = nowIso()
            repository.upsertStepState(scope, WorkflowStepStateRecord(namespaceId, workflowId, step.id, 1, WorkflowStatuses.COMPLETED, progress.payload(step.id)))
            persistProgressProjection(scope, namespaceId, workflowId, progress)
        }
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
        val matching = runCatching { registry.list() }
            .onFailure { logger.warn(it) { "Oracle registry listing failed; skipping auto-oracles for step '$stepId'" } }
            .getOrElse { return OracleEvaluation.EMPTY }
            .filter { appliesTo(it, workflowType, stepId) }
        if (matching.isEmpty()) return OracleEvaluation.EMPTY
        val service = oracleExecutionService ?: return OracleEvaluation(false, emptyList())

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
                        idempotencyKey = "dag-oracle:$workflowId:$stepId:${definition.id}" +
                            ((repository.findStepStates(scope, namespaceId, workflowId).firstOrNull { it.stepId == stepId }
                                ?.payload?.get("execution") as? Map<*, *>)?.get("executionId")?.let { ":$it" } ?: ""),
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
        is CapabilityOutcome.BranchPrepared -> WorkflowStatuses.COMPLETED
        is CapabilityOutcome.CodeExecuted -> if (outcome.verdict) WorkflowStatuses.COMPLETED else WorkflowStatuses.FAILED
        is CapabilityOutcome.CodeRefused -> WorkflowStatuses.FAILED
        is CapabilityOutcome.AgentCompleted -> WorkflowStatuses.COMPLETED
        is CapabilityOutcome.AgentFailed -> WorkflowStatuses.FAILED
        is CapabilityOutcome.AgentDeferred -> WorkflowStatuses.FAILED
        is CapabilityOutcome.AgentNeedsResearch -> WorkflowStatuses.NEEDS_RESEARCH
        is CapabilityOutcome.HumanCheckpointRequired -> WorkflowStatuses.WAITING_HUMAN
        is CapabilityOutcome.CiChecksCompleted -> if (outcome.passed) WorkflowStatuses.COMPLETED else WorkflowStatuses.FAILED
        is CapabilityOutcome.ExternalPending -> WorkflowStatuses.WAITING_EXTERNAL
        is CapabilityOutcome.AgentInProgress -> WorkflowStatuses.WAITING_EXTERNAL
    }

    /**
     * Parks a step whose external condition is still pending in `waiting_external`, or fails it with
     * `POLL_TIMEOUT_EXCEEDED` when its polling window (counted from the first poll of THIS execution) elapsed.
     * Nothing here records a FAILED result of the capability: a poll never consumes `maxCycles`.
     */
    private fun parkExternalWait(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        step: WorkflowStepDefinition,
        execution: CapabilityExecution,
        statuses: SessionProgress,
        executionId: String?,
        expectedRevision: Int,
        repoRoot: Path,
        ticket: String?,
    ): String {
        val now = clock.instant()
        val (source, reason, requestedInterval, attemptId) = when (val outcome = execution.outcome) {
            is CapabilityOutcome.ExternalPending ->
                PendingWait(outcome.source, outcome.reason, outcome.retryAfterSeconds, execution.attemptId)
            is CapabilityOutcome.AgentInProgress ->
                PendingWait("agent", outcome.summary, outcome.retryAfterSeconds, outcome.attemptId ?: execution.attemptId)
            else -> error("Not a pending outcome")
        }
        val previous = statuses.polls[step.id]
        val firstPollStartedAt = (previous?.get("firstPollStartedAt") as? String)?.let(Instant::parse) ?: now
        val deadline = firstPollStartedAt.plusSeconds(pollingProperties.effectiveMaxWaitSeconds.toLong())
        val pollCount = ((previous?.get("pollCount") as? Number)?.toInt() ?: 0) + 1
        val interval = pollingProperties.clampInterval(requestedInterval)
        if (!now.isBefore(deadline)) {
            recordPollTimeout(
                scope, namespaceId, workflowId, step, statuses, expectedRevision,
                pollCount = pollCount, firstPollStartedAt = firstPollStartedAt, deadline = deadline, lastReason = reason,
            )
            return WorkflowStatuses.FAILED
        }
        // The next poll never lands after the deadline: the timeout is enforced at the deadline itself.
        val nextPollAt = minOf(now.plusSeconds(interval.toLong()), deadline)
        val poll = linkedMapOf<String, Any?>(
            "executionId" to executionId,
            "source" to source,
            "firstPollStartedAt" to firstPollStartedAt.toString(),
            "lastPollAt" to now.toString(),
            "nextPollAt" to nextPollAt.toString(),
            "deadlineAt" to deadline.toString(),
            "pollCount" to pollCount,
            "intervalSeconds" to interval,
            "reason" to reason.take(MAX_POLL_REASON_CHARS),
            "attemptId" to attemptId,
        ).filterValues { it != null }
        val park = {
            statuses.polls[step.id] = poll
            setStatus(scope, namespaceId, workflowId, step.id, WorkflowStatuses.WAITING_EXTERNAL, statuses)
            outcomeTransitionService?.record(scope, namespaceId, workflowId, step.id, executionId,
                WorkflowStatuses.WAITING_EXTERNAL, execution.attemptId, execution.evidenceId)
            transition(scope, namespaceId, workflowId, step, WorkflowStatuses.WAITING_EXTERNAL, expectedRevision)
            recordStepEvidence(scope, namespaceId, workflowId, step, WorkflowStatuses.WAITING_EXTERNAL, execution, statuses, mapOf("poll" to poll))
            // Same transaction as the park: the step is `waiting_external` if and only if a delayed wake exists.
            outboxDrainService?.scheduleWakeInCurrentTransaction(scope, namespaceId, workflowId, repoRoot, ticket, nextPollAt)
            Unit
        }
        try {
            newTransaction { park() }
        } catch (error: Exception) {
            // The in-memory view was ahead of the rolled back store.
            statuses.polls.remove(step.id)
            throw ExternalWaitSchedulingException(error)
        }
        return WorkflowStatuses.WAITING_EXTERNAL
    }

    /** The park of a polling step could not be committed together with its wake: nothing was parked. */
    private class ExternalWaitSchedulingException(cause: Throwable) :
        RuntimeException("EXTERNAL_WAIT_SCHEDULING_FAILED: ${cause.message ?: cause.javaClass.simpleName}", cause)

    private data class PendingWait(val source: String, val reason: String, val retryAfterSeconds: Int?, val attemptId: String?)

    /** The first parked step whose next poll is due (a parked step without poll state is polled at once). */
    private fun dueExternalWait(steps: List<WorkflowStepDefinition>, statuses: SessionProgress): String? {
        val now = clock.instant()
        return steps.firstOrNull { step ->
            statuses.statuses[step.id] == WorkflowStatuses.WAITING_EXTERNAL &&
                (statuses.polls[step.id]?.get("nextPollAt") as? String)?.let { !Instant.parse(it).isAfter(now) } != false
        }?.id
    }

    /** Fails every parked step whose polling window elapsed; true when at least one step was failed. */
    private fun failExpiredExternalWaits(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        steps: List<WorkflowStepDefinition>,
        statuses: SessionProgress,
        expectedRevision: Int,
    ): Boolean {
        val now = clock.instant()
        var failed = false
        for (step in steps) {
            if (statuses.statuses[step.id] != WorkflowStatuses.WAITING_EXTERNAL) continue
            val poll = statuses.polls[step.id] ?: continue
            val deadline = (poll["deadlineAt"] as? String)?.let(Instant::parse) ?: continue
            if (now.isBefore(deadline)) continue
            recordPollTimeout(
                scope, namespaceId, workflowId, step, statuses, expectedRevision,
                pollCount = (poll["pollCount"] as? Number)?.toInt() ?: 0,
                firstPollStartedAt = (poll["firstPollStartedAt"] as? String)?.let(Instant::parse),
                deadline = deadline, lastReason = poll["reason"] as? String,
            )
            failed = true
        }
        return failed
    }

    /**
     * Terminal technical failure of a step that polled for its whole window. It is evidenced as a `session-step`
     * FAILED with the explicit code `POLL_TIMEOUT_EXCEEDED`; no CI verdict or accepted agent FAIL backs it, so the
     * outcome router can never take a `return` on it and no correction budget is consumed.
     */
    private fun recordPollTimeout(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        step: WorkflowStepDefinition,
        statuses: SessionProgress,
        expectedRevision: Int,
        pollCount: Int,
        firstPollStartedAt: Instant?,
        deadline: Instant,
        lastReason: String?,
    ) {
        val executionId = statuses.executions[step.id]?.get("executionId") as? String
        val write = {
            evidenceRepository.append(
                scope, namespaceId, workflowId,
                WorkflowEvidenceItem(
                    evidenceId = UUID.randomUUID().toString(), namespaceId = namespaceId, workflowId = workflowId,
                    stepId = step.id, kind = "session-step", outcome = WorkflowStatuses.FAILED,
                    source = mapOf("kind" to "factory-session-sequencer"),
                    facts = mapOf(
                        "stepId" to step.id,
                        "status" to WorkflowStatuses.FAILED,
                        "code" to POLL_TIMEOUT_EXCEEDED,
                        "executed" to true,
                        "pollCount" to pollCount,
                        "firstPollStartedAt" to firstPollStartedAt?.toString(),
                        "deadlineAt" to deadline.toString(),
                        "lastReason" to lastReason,
                        "executionId" to executionId,
                        "message" to "The external condition was still pending after ${pollingProperties.effectiveMaxWaitSeconds} seconds.",
                    ).filterValues { it != null },
                    idempotencyKey = "poll-timeout:$workflowId:${step.id}:${executionId ?: "legacy"}",
                    createdAt = null,
                ),
            )
            transition(scope, namespaceId, workflowId, step, WorkflowStatuses.FAILED, expectedRevision)
            setStatus(scope, namespaceId, workflowId, step.id, WorkflowStatuses.FAILED, statuses)
        }
        runCatching { newTransaction { write() } }.onFailure { error ->
            logger.error(error) { "Poll timeout of step '${step.id}' of workflow '$workflowId' could not be persisted" }
            statuses.statuses[step.id] = WorkflowStatuses.FAILED
            statuses.completedAt.putIfAbsent(step.id, nowIso())
        }
    }

    /** Requests the delayed wake that re-polls the earliest parked step, without holding a worker slot meanwhile. */
    private fun scheduleNextPoll(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        steps: List<WorkflowStepDefinition>,
        statuses: SessionProgress,
        repoRoot: Path,
        ticket: String?,
    ) {
        val drain = outboxDrainService ?: return
        val next = steps.filter { statuses.statuses[it.id] == WorkflowStatuses.WAITING_EXTERNAL }
            .mapNotNull { (statuses.polls[it.id]?.get("nextPollAt") as? String)?.let(Instant::parse) }
            .minOrNull() ?: return
        runCatching { drain.scheduleWake(scope, namespaceId, workflowId, repoRoot, ticket, next) }
            // The park already requested this wake atomically; a failure here only loses the idempotent
            // re-assertion, but it is still loud because the sweeper is then the only safety net.
            .onFailure { logger.error(it) { "The next external poll of workflow '$workflowId' could not be re-asserted" } }
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
            val executionId = statuses.executions[step.id]?.get("executionId")
            val interaction = interactions.filter { it.stepId == step.id &&
                (executionId == null || it.payload["executionId"] == executionId) }.maxByOrNull { it.revision }
            // A human answer resumes agent execution; it never approves the agent's result.
            if (step.responsibility.kind == ResponsibilityKind.AGENT) {
                if (capabilityExecutionService.canResumeWaitingAgentStep(scope, namespaceId, workflowId, step.id)) {
                    transition(scope, namespaceId, workflowId, step, WorkflowStatuses.READY, expectedRevision)
                    setStatus(scope, namespaceId, workflowId, step.id, WorkflowStatuses.READY, statuses)
                } else {
                    allResolved = false
                }
                continue
            }
            if (step.responsibility.kind != ResponsibilityKind.HUMAN) {
                allResolved = false
                continue
            }
            val terminal = humanDecision(interaction?.takeIf { it.interactionType == "checkpoint" })
            if (terminal == null || interaction == null) {
                allResolved = false
                continue
            }
            transition(scope, namespaceId, workflowId, step, terminal, expectedRevision)
            setStatus(scope, namespaceId, workflowId, step.id, terminal, statuses)
            recordHumanResolutionEvidence(scope, namespaceId, workflowId, step, terminal, interaction)
            outcomeTransitionService?.record(scope, namespaceId, workflowId, step.id, executionId as? String, terminal)
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
                // A settled step no longer polls: its poll window belonged to the execution that just ended.
                progress.polls.remove(stepId)
            }
        }
        // Preserve ledger metadata written since the last in-memory projection (attempt binding,
        // terminal evidence and timestamps); never overwrite it with a stale payload copy.
        val durableExecution = repository.findStepStates(scope, namespaceId, workflowId)
            .firstOrNull { it.stepId == stepId }?.payload?.get("execution") as? Map<*, *>
        if (progress.executions.containsKey(stepId)) {
            check(durableExecution?.get("executionId") == progress.executions[stepId]?.get("executionId")) { "EXECUTION_GENERATION_FENCED" }
            progress.executions[stepId] = durableExecution!!.entries.associate { it.key.toString() to it.value }
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
        outcomeTransitionService?.record(scope, namespaceId, workflowId, stepId,
            progress.executions[stepId]?.get("executionId") as? String, status)
        // Persist and announce every meaningful step transition. The cockpit
        // treats SSE as an invalidation hint and reloads this authoritative
        // projection; it never infers progress from elapsed time or silence.
        persistProgressProjection(scope, namespaceId, workflowId, progress)
        sseHub.publish(scope, namespaceId, mapOf("workflowId" to workflowId, "namespaceId" to namespaceId))
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
        val expectedExecution = progress.executions[stepId]?.get("executionId")
        val current = if (expectedExecution != null) repository.findStepStates(scope, namespaceId, workflowId).single { it.stepId == stepId } else null
        val claimPayload = progress.payload(stepId) + mapOf("startedAt" to startedAt) +
            (if (progress.routing.containsKey(stepId)) mapOf("executed" to true) else emptyMap())
        val claimed = if (current != null) {
            check((current.payload["execution"] as? Map<*, *>)?.get("executionId") == expectedExecution) { "EXECUTION_GENERATION_FENCED" }
            current.status in setOf(WorkflowStatuses.READY, WorkflowStatuses.PENDING, WorkflowStatuses.NEEDS_RESEARCH, WorkflowStatuses.WAITING_EXTERNAL) &&
                repository.updateStepStatus(scope, namespaceId, workflowId, stepId, current.revision, WorkflowStatuses.RUNNING, claimPayload)
        } else repository.claimStep(
            scope,
            namespaceId,
            workflowId,
            stepId,
            fromStatuses = listOf(WorkflowStatuses.READY, WorkflowStatuses.PENDING, WorkflowStatuses.NEEDS_RESEARCH, WorkflowStatuses.WAITING_EXTERNAL),
            payload = progress.payload(stepId) + mapOf("startedAt" to startedAt) +
                (if (progress.routing.containsKey(stepId)) mapOf("executed" to true) else emptyMap()),
        )
        if (!claimed) {
            logger.warn { "Step '$stepId' of workflow '$workflowId' is already claimed; skipping execution." }
            return false
        }
        progress.statuses[stepId] = WorkflowStatuses.RUNNING
        if (progress.routing.containsKey(stepId)) {
            progress.routing[stepId] = progress.routing.getValue(stepId) + ("executed" to true)
        }
        progress.startedAt[stepId] = startedAt
        progress.waitingQuestions.remove(stepId)
        recordRunningStepEvidence(scope, namespaceId, workflowId, stepId, startedAt)
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
        val executionId = progress.executions[stepId]?.get("executionId") as? String
        if (executionId != null) {
            val durable = repository.findStepStates(scope, namespaceId, workflowId).single { it.stepId == stepId }
                .payload["execution"] as? Map<*, *>
            check(durable?.get("executionId") == executionId) { "EXECUTION_GENERATION_FENCED" }
            progress.executions[stepId] = durable!!.entries.associate { it.key.toString() to it.value }
        }
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
        outcomeTransitionService?.record(scope, namespaceId, workflowId, stepId, executionId, update.status)
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

    private fun recordRunningStepEvidence(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        stepId: String,
        startedAt: String,
    ) {
        evidenceRepository.append(
            scope, namespaceId, workflowId,
            WorkflowEvidenceItem(
                evidenceId = UUID.randomUUID().toString(),
                namespaceId = namespaceId,
                workflowId = workflowId,
                stepId = stepId,
                kind = "session-step",
                outcome = WorkflowStatuses.RUNNING,
                source = mapOf("kind" to "factory-session-sequencer", "clock" to "factory"),
                facts = mapOf("stepId" to stepId, "status" to WorkflowStatuses.RUNNING, "startedAt" to startedAt) +
                    ((repository.findStepStates(scope, namespaceId, workflowId).firstOrNull { it.stepId == stepId }
                        ?.payload?.get("execution") as? Map<*, *>)?.get("executionId")?.let { mapOf("executionId" to it) } ?: emptyMap()),
                idempotencyKey = "session-step-running:$workflowId:$stepId:$startedAt",
                createdAt = null,
            ),
        )
    }

    private fun recordStepEvidence(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        step: WorkflowStepDefinition,
        status: String,
        execution: CapabilityExecution,
        progress: SessionProgress,
        workspaceFacts: Map<String, Any?> = emptyMap(),
    ) {
        val startedAt = progress.startedAt[step.id]
        val completedAt = progress.completedAt[step.id]
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
                    "executionId" to progress.executions[step.id]?.get("executionId"),
                    "attemptId" to execution.attemptId,
                    "evidenceId" to execution.evidenceId,
                    "startedAt" to startedAt,
                    "completedAt" to completedAt,
                    "durationMs" to elapsedMs(startedAt, completedAt),
                ).filterValues { it != null } + codeVerificationFacts(execution.outcome) +
                    workspaceFacts.filterKeys { it != "observedHead" },
                idempotencyKey = null,
                createdAt = null,
            ),
        )
    }

    private fun recordCodePreparationFailure(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        step: WorkflowStepDefinition,
        repoRoot: Path,
        error: Exception,
        expectedRevision: Int,
        statuses: SessionProgress,
    ) {
        logger.warn(error) { "Code step '${step.id}' could not resolve its execution root." }
        runCatching {
            newTransaction {
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
                            "verification" to step.responsibility.name,
                            "executed" to false,
                            "code" to "CODE_WORKTREE_RESOLUTION_FAILED",
                            "message" to safeDiagnostic(error),
                            "cwd" to repoRoot.toAbsolutePath().normalize().toString(),
                            "workspaceProvenance" to "resolution-failed",
                        ),
                        idempotencyKey = null,
                        createdAt = null,
                    ),
                )
                transition(scope, namespaceId, workflowId, step, WorkflowStatuses.FAILED, expectedRevision)
                setStatus(scope, namespaceId, workflowId, step.id, WorkflowStatuses.FAILED, statuses)
            }
        }.onFailure { recoveryError ->
            logger.error(recoveryError) { "Could not persist code preparation failure for step '${step.id}'." }
            statuses.statuses[step.id] = WorkflowStatuses.FAILED
            statuses.completedAt.putIfAbsent(step.id, nowIso())
        }
    }

    private fun safeDiagnostic(error: Exception): String {
        val raw = error.message ?: error.javaClass.simpleName
        return SecretRedaction.redact(raw).take(4_096)
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
                    "code" to if (error is ExternalWaitSchedulingException) "EXTERNAL_WAIT_SCHEDULING_FAILED" else "STEP_EXECUTION_ERROR",
                    "message" to SecretRedaction.redact(error.message ?: error.toString()).take(4_096),
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
                facts = mapOf("stepId" to step.id, "status" to status, "interactionId" to interaction.interactionId,
                    "executionId" to interaction.payload["executionId"]),
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
            progress.executions[stepId]?.get("executionId")?.let { step["executionId"] = it }
            progress.routing[stepId]?.let { step.putAll(it) }
            progress.startedAt[stepId]?.let { step["startedAt"] = it } ?: step.remove("startedAt")
            progress.completedAt[stepId]?.let { step["completedAt"] = it } ?: step.remove("completedAt")
            elapsedMs(progress.startedAt[stepId], progress.completedAt[stepId])?.let { step["durationMs"] = it } ?: step.remove("durationMs")
            progress.waitingQuestions[stepId]?.let { step["waitingQuestion"] = it } ?: step.remove("waitingQuestion")
            progress.polls[stepId]?.let { step["poll"] = it } ?: step.remove("poll")
            step
        } ?: return
        projection["steps"] = projectedSteps
        projection["status"] = when {
            (projection["correction"] as? Map<*, *>)?.get("status") == "waiting_human" -> WorkflowStatuses.WAITING_HUMAN
            // A configured failure is evaluated by the durable router before the run can be sealed.
            progress.executions.isNotEmpty() && progress.statuses.values.any { it == WorkflowStatuses.FAILED } -> WorkflowStatuses.RUNNING
            progress.statuses.values.any { it == WorkflowStatuses.NEEDS_RESEARCH } &&
                progress.statuses.values.none {
                    it == WorkflowStatuses.FAILED || it == WorkflowStatuses.CANCELLED ||
                        it == WorkflowStatuses.RUNNING || it == WorkflowStatuses.WAITING_HUMAN
                } -> WorkflowStatuses.NEEDS_RESEARCH
            progress.statuses.values.any { it == WorkflowStatuses.FAILED } -> WorkflowStatuses.FAILED
            progress.statuses.values.any { it == WorkflowStatuses.WAITING_HUMAN } -> WorkflowStatuses.WAITING_HUMAN
            progress.statuses.values.any { it == WorkflowStatuses.WAITING_EXTERNAL } &&
                progress.statuses.values.none { it == WorkflowStatuses.RUNNING } -> WorkflowStatuses.WAITING_EXTERNAL
            progress.statuses.values.any { it == WorkflowStatuses.RUNNING } -> WorkflowStatuses.RUNNING
            progress.statuses.values.all { it == WorkflowStatuses.COMPLETED || it == WorkflowStatuses.SKIPPED } -> WorkflowStatuses.COMPLETED
            progress.statuses.values.any { it == WorkflowStatuses.BLOCKED } -> WorkflowStatuses.FAILED
            else -> WorkflowStatuses.PENDING
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
        if (sessionStatus == WorkflowStatuses.COMPLETED) {
            (projection["correction"] as? Map<*, *>)?.let { correction ->
                projection["correction"] = correction.entries.associate { it.key.toString() to it.value } + ("status" to "completed")
            }
        }
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
                "poll" to progress.polls[step.id],
                "executionId" to progress.executions[step.id]?.get("executionId"),
            ).apply {
                step.contract?.let { putAll(it.toJson()) }
                putAll(step.routingJson())
                progress.routing[step.id]?.let { putAll(it) }
            }.filterValues { it != null }
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

        /** Failure code of a step that polled an external condition for its whole window. */
        const val POLL_TIMEOUT_EXCEEDED = "POLL_TIMEOUT_EXCEEDED"
        const val MAX_POLL_REASON_CHARS = 1_000
    }
}
