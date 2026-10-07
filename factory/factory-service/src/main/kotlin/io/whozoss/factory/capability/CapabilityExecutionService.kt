package io.whozoss.factory.capability

import io.whozoss.factory.adapter.agentos.AgentOsAdapterProperties
import io.whozoss.factory.adapter.agentos.AgentOsExecutionAdapter
import io.whozoss.factory.adapter.agentos.AgentOsExecutionVerdict
import io.whozoss.factory.adapter.agentos.ObservationEscalationPolicy
import io.whozoss.factory.adapter.agentos.TrustedCaseBinding
import io.whozoss.factory.agentattempt.domain.AgentAttemptStatus
import io.whozoss.factory.agentattempt.domain.AgentStepAttemptRecord
import io.whozoss.factory.agentattempt.domain.AgentStepResultCapabilityIdentity
import io.whozoss.factory.agentattempt.domain.AttemptClaimConflictException
import io.whozoss.factory.agentattempt.domain.CanonicalJsonHash
import io.whozoss.factory.agentattempt.domain.DurableAgentAttempt
import io.whozoss.factory.agentattempt.domain.IdempotencyKeyCollisionException
import io.whozoss.factory.agentattempt.persistence.AgentStepAttemptRepository
import io.whozoss.factory.agentattempt.service.AgentStepResultService
import io.whozoss.factory.agentattempt.service.DurableAgentAttemptService
import io.whozoss.factory.environment.persistence.WorkEnvironmentRepository
import io.whozoss.factory.persistence.TenantScope
import io.whozoss.factory.workflow.domain.HumanInteractionEventRecord
import io.whozoss.factory.workflow.domain.HumanInteractionRecord
import io.whozoss.factory.workflow.domain.ResponsibilityKind
import io.whozoss.factory.workflow.domain.WorkflowCodeTransitionRecord
import io.whozoss.factory.workflow.domain.WorkflowEvidenceItem
import io.whozoss.factory.workflow.domain.WorkflowStepDefinition
import io.whozoss.factory.workflow.domain.WorkflowStepResponsibility
import io.whozoss.factory.workflow.persistence.HumanInteractionRepository
import io.whozoss.factory.workflow.persistence.WorkflowEvidenceRepository
import io.whozoss.factory.workflow.persistence.WorkflowRepository
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import mu.KotlinLogging
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

/** Bounded intermediate state emitted synchronously by an AgentOS observation. */
data class AgentObservationUpdate(
    val status: String,
    val questionRef: String? = null,
    val text: String? = null,
    val type: String? = null,
    val options: List<String> = emptyList(),
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
     * Durable-attempt service (Lot C) — **mandatory primary dependency** since
     * the final cutover. Together with [agentOsExecutionAdapter] it drives `agent`
     * steps through the durable AgentOS bridge: a short claim transaction, the
     * untransacted remote turn and a short finalize transaction.
     */
    private val durableAgentAttemptService: DurableAgentAttemptService,
    /**
     * Explicit Factory -> AgentOS execution boundary (SSE/reconcile) —
     * **mandatory primary dependency** since the final cutover. It is the primary
     * observation mechanism for `agent` steps.
     */
    private val agentOsExecutionAdapter: AgentOsExecutionAdapter,
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
    /** Wall-clock observation budget handed to the adapter turn observer. */
    private val agentObservationTimeoutMs: Long = 600_000L,
    /** Lease TTL of the durable attempt claim (a live lease fences a competing worker). */
    private val agentLeaseTtlMs: Long? = 3_600_000L,
    /**
     * Escalation chain applied to an indeterminate observation (REST snapshot ->
     * bounded SSE reconnect -> kill -> post-kill reconcile). Defaults to the
     * frozen Lot H policy; injectable so tests can pin the kill decision.
     */
    private val observationEscalation: ObservationEscalationPolicy = ObservationEscalationPolicy(),
    /**
     * Driver selection for `agent` steps. The durable SSE bridge is the primary,
     * non-optional path: [agentOsExecutionAdapter] is always required and used
     * whenever this flag is `true` (the default, bound from
     * `factory.adapter.agentos.enabled`). Setting it to `false` explicitly demotes
     * execution to the legacy polling turn driver ([resolveAgentViaPolling]) — a
     * troubleshooting fallback only.
     */
    private val agentOsAdapterProperties: AgentOsAdapterProperties = AgentOsAdapterProperties(),
    /**
     * Optional work-environment read port used to bind a freshly reserved
     * attempt to the environment it runs against (`environmentRef` +
     * `expectedEnvironmentRevision` captured at reservation). Injected in
     * production; pure unit tests may leave it `null` and attempts then carry
     * no environment link (same nullable-dependency pattern as
     * [agentStepResultService]).
     */
    private val workEnvironmentRepository: WorkEnvironmentRepository? = null,
) {

    private val logger = KotlinLogging.logger {}

    /** Guards the one-shot WARN logged when no capability issuer is wired. */
    private val issuerAbsenceLogged = AtomicBoolean(false)

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
        onAgentObservation: (AgentObservationUpdate) -> Unit = {},
    ): CapabilityExecution {
        return when (step.responsibility.kind) {
            ResponsibilityKind.AGENT -> resolveAgent(scope, namespaceId, workflowId, step, repoRoot, ticket, onAgentObservation)

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
    /**
     * Computes the set of ancestor step ids reachable from [startIds] by
     * following `dependsOn` edges in [stepIndex]. The result excludes [startIds]
     * themselves and is used to propagate run-brief handoffs transitively.
     */
    private fun transitiveAncestors(
        startIds: List<String>,
        stepIndex: Map<String, WorkflowStepDefinition>,
    ): Set<String> {
        val visited = LinkedHashSet<String>()
        val queue = ArrayDeque(startIds)
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            val step = stepIndex[id] ?: continue
            for (dep in step.dependsOn) {
                if (visited.add(dep)) queue.add(dep)
            }
        }
        return visited
    }

    private fun buildBrief(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        step: WorkflowStepDefinition,
        ticket: String?,
    ): String {
        val ticketInstruction = briefFromTicket(ticket)
        val entryStep = step.dependsOn.isEmpty()
        val evidence = evidenceRepository.list(scope, namespaceId, workflowId)
        val inputs = LinkedHashMap<String, Any?>()
        if (step.dependsOn.isNotEmpty()) {
            for (dependency in step.dependsOn) {
                val item = evidence.lastOrNull {
                    it.stepId == dependency && it.kind == AGENT_RESULT_EVIDENCE_KIND && it.outcome == "pass"
                } ?: continue
                inputs[dependency] = item.facts["outputs"] ?: item.facts
            }
        }
        // Search for a run-brief first in the direct dependency outputs, then
        // transitively in the evidence of every ancestor reachable via dependsOn.
        // This ensures a run-brief produced by an entry step (e.g. A) is still
        // visible to a downstream step (e.g. C) even when the intermediate step
        // (B) succeeded without re-emitting the artifact.
        // Only ancestors reachable from this step's own dependsOn chain are
        // considered: run-briefs from independent parallel branches are never
        // injected.
        // Fetch the instance once: used both for the transitive run-brief search
        // and for the controller-request text.
        val workflowInstance = workflowRepository.findInstance(scope, namespaceId, workflowId)

        val runBrief: Map<String, Any?>? = inputs.values.firstNotNullOfOrNull(::findRunBrief)
            ?: run {
                if (entryStep) return@run null
                // Resolve the workflow definition to compute transitive ancestors.
                val projSteps = (workflowInstance?.projection?.get("steps") as? List<*>)
                    ?.filterIsInstance<Map<*, *>>()
                val definitionSteps: Map<String, WorkflowStepDefinition>? = projSteps?.mapNotNull { s ->
                    val id = s["id"] as? String ?: return@mapNotNull null
                    val deps = (s["dependsOn"] as? List<*>)?.filterIsInstance<String>() ?: emptyList()
                    val respMap = s["responsibility"] as? Map<*, *>
                    val kindWire = respMap?.get("kind") as? String
                    val kind = ResponsibilityKind.fromWire(kindWire) ?: ResponsibilityKind.AGENT
                    val name = s["name"] as? String ?: id
                    val respName = respMap?.get("name") as? String
                    id to WorkflowStepDefinition(id, name, WorkflowStepResponsibility(kind, respName), deps)
                }?.toMap()
                if (definitionSteps == null) return@run null
                val ancestors = transitiveAncestors(step.dependsOn, definitionSteps)
                // Search ancestor evidence in reverse-insertion order so the most
                // recent entry step's run-brief wins when multiple exist.
                evidence.lastOrNull {
                    it.stepId != null &&
                        it.stepId in ancestors &&
                        it.kind == AGENT_RESULT_EVIDENCE_KIND &&
                        it.outcome == "pass" &&
                        findRunBrief(it.facts["outputs"] ?: it.facts) != null
                }?.let { item -> findRunBrief(item.facts["outputs"] ?: item.facts) }
            }
        val controllerRequest = workflowInstance
            ?.instance
            ?.get("controllerRequest")
            .let { it as? Map<*, *> }
            ?.get("text")
            .let { it as? String }
            ?.takeIf { it.isNotBlank() }

        return buildString {
            controllerRequest?.let {
                appendLine("## User request")
                appendLine(it)
                appendLine()
            }
            appendLine("## Current step")
            appendLine("Id: ${step.id}")
            appendLine("Name: ${step.name}")
            appendLine("Role: ${step.responsibility.name ?: step.responsibility.kind.wire}")
            appendLine("Scope: Work only on this step.")
            ticketInstruction?.let { appendLine("Ticket context: $it") }
            if (inputs.isNotEmpty()) appendLine("Inputs: ${renderValue(inputs)}")
            if (!entryStep && runBrief != null) appendLine("Run brief handoff: ${renderValue(runBrief)}")
            appendLine()
            appendLine("## Execution contract")
            if (entryStep) {
                appendLine("- This is an entry step. Frame the handoff before completing it: objective, scope and exclusions, criteria explicitly supplied by the user or workflow, constraints, and open questions.")
                appendLine("- If the initial intention is absent or ambiguous, call queryUser and wait for the answer. Do not submit FAIL merely because clarification is needed.")
                appendLine("- If queryUser is unavailable, do not bypass clarification by guessing or silently widening scope; report the clarification blocker only as a justified terminal failure when no supported continuation exists.")
                appendLine("- Include one durable Markdown artifact in the normal artifacts list with kind 'run-brief', encoding 'markdown', and content sections: Objectif, Périmètre et exclusions, Critères, Contraintes, Questions ouvertes.")
                appendLine("- Do not invent criteria or turn a request for analysis into an implementation task.")
            } else {
                appendLine("- Consume the structured dependency inputs and the run-brief handoff above while following this step's own instructions; the original user request remains the governing intention.")
                appendLine("- Preserve every run-brief artifact received from direct dependencies unchanged in this step's artifacts so later steps can relay the correct branch framing. Add separate artifacts for this step's own work; never rewrite or merge independent framings.")
            }
            appendLine("- Choose deliverables from the work actually requested when the definition has no explicit deliverable metadata:")
            appendLine("  - Analysis or design: provide reasoned findings, decisions/options, constraints, open questions, and a durable Markdown artifact; do not implement unless requested.")
            appendLine("  - Implementation: provide the scoped changes and concrete verification evidence available under the run policy; do not claim checks that were not run.")
            appendLine("  - Review: provide prioritized findings with locations/evidence and residual risks; do not modify code unless requested.")
            appendLine("- If clarification is required, call queryUser, wait for the answer, then continue the same step. If that tool is unavailable, do not guess or bypass the clarification requirement.")
            appendLine("- Call FACTORY_WORKER__submit_step_result only when the analysis, editing, or review work is complete, or when a justified terminal failure prevents completion.")
            appendLine("- Use PASS or FAIL according to this step's criteria. Do not invent criteria.")
            appendLine("- Call the tool with its structured arguments. Do not return free-form JSON as the result.")
            appendLine("- If Factory rejects the result schema, correct the tool payload and retry; that rejection is not a step verdict.")
            append("- Once Factory accepts the result, stop working on this step.")
        }
    }

    /** Finds the first standard run-brief artifact in a structured result value. */
    private fun findRunBrief(value: Any?): Map<String, Any?>? = when (value) {
        is Map<*, *> -> {
            val normalized = value.entries.associate { it.key.toString() to it.value }
            if (normalized["kind"] == RUN_BRIEF_KIND && normalized["encoding"] == "markdown") {
                normalized
            } else {
                normalized.values.firstNotNullOfOrNull(::findRunBrief)
            }
        }
        is Iterable<*> -> value.firstNotNullOfOrNull(::findRunBrief)
        is Array<*> -> value.firstNotNullOfOrNull(::findRunBrief)
        else -> null
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
     * Agent capability dispatcher. The durable SSE bridge (see
     * [resolveAgentViaAdapter]) is the primary, non-optional path: it always runs
     * unless the operator explicitly disabled it (`factory.adapter.agentos.enabled=false`),
     * in which case the legacy polling turn driver ([resolveAgentViaPolling]) is
     * the explicit fallback.
     */
    private fun resolveAgent(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        step: WorkflowStepDefinition,
        repoRoot: Path,
        ticket: String?,
        onAgentObservation: (AgentObservationUpdate) -> Unit,
    ): CapabilityExecution {
        return if (agentOsAdapterProperties.enabled) {
            resolveAgentViaAdapter(scope, namespaceId, workflowId, step, ticket, durableAgentAttemptService, agentOsExecutionAdapter, onAgentObservation)
        } else {
            resolveAgentViaPolling(scope, namespaceId, workflowId, step, repoRoot, ticket)
        }
    }

    /** The durable attempt ids produced by the claim phase of the bridge. */
    private data class AgentReservation(
        val attemptId: String,
        val caseId: String,
        val ownerToken: String,
        val capabilityToken: String?,
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
        onAgentObservation: (AgentObservationUpdate) -> Unit,
    ): CapabilityExecution {
        val agentId = step.responsibility.name ?: "agent"
        // Attempt #1 has a stable id, but a human answer supersedes it and
        // registers N+1. The DAG must follow that authoritative successor;
        // otherwise only the recovery scanner can drive N+1 while this path
        // remains anchored to the terminal predecessor.
        val attemptId = attempts.findLatestForStep(scope, namespaceId, workflowId, step.id)
            ?.attemptId
            ?: stableAttemptId(workflowId, step.id)
        val ownerToken = UUID.randomUUID().toString()
        val brief = buildBrief(scope, namespaceId, workflowId, step, ticket)

        // Phase 1 - short transactions: reserve the attempt (register + atomic,
        // lease-fenced claim). Each durable-attempt operation owns its own short
        // transaction: the claim is itself a `REQUIRES_NEW` compare-and-set, so it
        // must not be nested in an outer transaction that holds the uncommitted
        // registration.
        val reservation = withReservationLock("$workflowId#${step.id}") {
            reserveAgentAttempt(attempts, scope, namespaceId, workflowId, step, attemptId, ownerToken, agentId, brief)
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
            adapter, attempts, scope, namespaceId, workflowId, step, reservation, agentId, brief, onAgentObservation,
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
        brief: String,
    ): AgentReservation {
        val existing = attempts.find(scope, namespaceId, workflowId, step.id, attemptId)
        if (existing != null && existing.status.terminal) {
            return AgentReservation(
                attemptId = attemptId,
                caseId = existing.caseId,
                ownerToken = existing.ownerToken ?: ownerToken,
                capabilityToken = existing.capabilityToken,
                turnStarted = true,
                terminalStatus = existing.status,
            )
        }
        // Command idempotency: the same `attemptId` replayed with a DIFFERENT
        // command payload is an explicit collision, never a silent reuse. A null
        // stored brief is a legacy record and is tolerated.
        if (existing != null && existing.brief != null && existing.brief != brief) {
            throw IdempotencyKeyCollisionException(
                "Attempt '$attemptId' was registered with a different command payload",
                details = mapOf(
                    "attemptId" to attemptId,
                    "workflowId" to workflowId,
                    "stepId" to step.id,
                ),
            )
        }
        val newAttempt = existing == null
        if (newAttempt) {
            // Bind the attempt to the environment it runs against, captured at
            // reservation time (Req 8). Null when no environment exists yet.
            val caseId = stableCaseId(workflowId, step.id)
            val environment = workEnvironmentRepository?.findLatestByWorkflowId(scope, workflowId)
            val capabilityToken = issueDurableCapability(
                scope, namespaceId, workflowId, step, attemptId, caseId, agentId, brief,
            )
            attempts.register(
                scope,
                DurableAgentAttempt(
                    attemptId = attemptId,
                    caseId = caseId,
                    namespaceId = namespaceId,
                    workflowId = workflowId,
                    stepId = step.id,
                    attemptNumber = 1,
                    agentName = agentId,
                    capabilityToken = capabilityToken,
                    brief = brief,
                    environmentRef = environment?.environmentId,
                    expectedEnvironmentRevision = environment?.revision,
                ),
            )
        }
        val turnStarted = existing?.status in setOf(
            AgentAttemptStatus.STARTING,
            AgentAttemptStatus.RUNNING,
            AgentAttemptStatus.WAITING_HUMAN,
        )
        val persisted = attempts.find(scope, namespaceId, workflowId, step.id, attemptId)
            ?: error("Durable attempt '$attemptId' was not persisted before AgentOS case creation")
        val resolvedCaseId = persisted.caseId.takeIf { it.isNotBlank() } ?: stableCaseId(workflowId, step.id)
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
            capabilityToken = persisted.capabilityToken,
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
        onAgentObservation: (AgentObservationUpdate) -> Unit,
    ): AgentOsExecutionVerdict {
        val binding = TrustedCaseBinding(
            caseId = reservation.caseId,
            namespaceId = namespaceId,
            attemptId = reservation.attemptId,
            capabilityToken = reservation.capabilityToken,
            agentName = agentId,
        )
        val handle = try {
            adapter.createOrRecoverExecution(binding, workflowId, step.id)
        } catch (error: Exception) {
            // The attempt already exists durably but no usable AgentOS execution
            // was obtained. Terminalize it now so the step-level recovery can
            // converge to FAILED instead of leaving a RUNNING projection forever.
            return AgentOsExecutionVerdict.Indeterminate(
                reason = "AGENT_CASE_CREATION_ERROR: ${error.message ?: error.toString()}",
                evidence = mapOf("caseId" to reservation.caseId, "attemptId" to reservation.attemptId),
            )
        }
        val caseId = handle.caseId
        // Mark the attempt `starting` BEFORE dispatching the message: a crash
        // after the message is accepted therefore records that the turn was
        // started and a replay never sends a second turn (idempotence by attemptId).
        newTransaction {
            attempts.transition(scope, namespaceId, workflowId, step.id, reservation.attemptId, reservation.ownerToken, AgentAttemptStatus.STARTING)
        }
        if (!reservation.turnStarted) {
            try {
                adapter.startTurn(binding.copy(caseId = caseId), agentId, brief)
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
        var waitingQuestion: String? = null
        val observed = try {
            adapter.observeTurn(
                caseId,
                reservation.attemptId,
                agentObservationTimeoutMs,
                { waiting ->
                if (waitingQuestion != waiting.questionRef) {
                    waitingQuestion = waiting.questionRef
                    newTransaction {
                        attempts.transition(
                            scope, namespaceId, workflowId, step.id, reservation.attemptId,
                            reservation.ownerToken, AgentAttemptStatus.WAITING_HUMAN,
                        )
                    }
                    onAgentObservation(
                        AgentObservationUpdate(
                            status = "waiting_human",
                            questionRef = waiting.questionRef,
                            text = waiting.questionText?.take(MAX_QUESTION_TEXT),
                            type = waiting.evidence["questionType"]?.toString()?.take(MAX_QUESTION_TYPE),
                            options = (waiting.evidence["options"] as? List<*>)
                                .orEmpty().mapNotNull { it?.toString()?.take(MAX_QUESTION_OPTION) }.take(MAX_QUESTION_OPTIONS),
                        ),
                    )
                }
                },
                { answer ->
                    val question = waitingQuestion
                    if (question != null && answer.answeredQuestionId == question) {
                        newTransaction {
                            val current = attempts.find(scope, namespaceId, workflowId, step.id, reservation.attemptId)
                            if (current?.status == AgentAttemptStatus.WAITING_HUMAN) {
                                attempts.transition(
                                    scope, namespaceId, workflowId, step.id, reservation.attemptId,
                                    reservation.ownerToken, AgentAttemptStatus.RUNNING,
                                    lastObservedEventId = answer.eventId,
                                )
                                onAgentObservation(AgentObservationUpdate(status = "running"))
                            }
                        }
                    }
                },
            )
        } catch (error: Exception) {
            runCatching { adapter.reconcile(caseId) }.getOrElse {
                AgentOsExecutionVerdict.Indeterminate(
                    reason = "AGENT_OBSERVATION_ERROR: ${error.message ?: error.toString()}",
                    evidence = mapOf("caseId" to caseId, "attemptId" to reservation.attemptId),
                )
            }
        }
        if (waitingQuestion != null && observed !is AgentOsExecutionVerdict.WaitingHuman) {
            newTransaction {
                val current = attempts.find(scope, namespaceId, workflowId, step.id, reservation.attemptId)
                if (current?.status == AgentAttemptStatus.WAITING_HUMAN) {
                    val persistedAnswer = adapter.persistedEvents(caseId).lastOrNull {
                        it.type == io.whozoss.factory.adapter.agentos.CaseEventView.ANSWER_EVENT &&
                            it.answeredQuestionId == waitingQuestion
                    }
                    if (persistedAnswer != null) {
                        attempts.transition(
                            scope, namespaceId, workflowId, step.id, reservation.attemptId,
                            reservation.ownerToken, AgentAttemptStatus.RUNNING,
                            lastObservedEventId = persistedAnswer.eventId,
                        )
                        onAgentObservation(AgentObservationUpdate(status = "running"))
                    }
                }
            }
        }
        // A structured result accepted by Factory is the terminal authority.
        // AgentOS commonly becomes IDLE after the submit tool, so consult the
        // durable result before any reconnect/kill escalation.
        val acceptedResult = agentStepResultService?.acceptedResult(
            scope, namespaceId, workflowId, step.id, reservation.attemptId,
        )
        if (acceptedResult != null) {
            return when (acceptedResult.status) {
                io.whozoss.factory.agentattempt.domain.AgentStepResultStatus.PASS ->
                    AgentOsExecutionVerdict.Succeeded(
                        outputs = mapOf(
                            "summary" to acceptedResult.summary,
                            "claims" to acceptedResult.claims,
                            "findings" to acceptedResult.findings,
                            "artifacts" to acceptedResult.artifacts,
                        ),
                        evidence = mapOf("resultId" to acceptedResult.resultId, "source" to "factory-step-result"),
                    )
                io.whozoss.factory.agentattempt.domain.AgentStepResultStatus.FAIL ->
                    AgentOsExecutionVerdict.Failed(
                        code = "FACTORY_STEP_RESULT_FAILED",
                        message = acceptedResult.summary,
                        evidence = mapOf("resultId" to acceptedResult.resultId, "source" to "factory-step-result"),
                    )
            }
        }
        // Without an accepted result, an indeterminate observation starts the
        // normal reconnect/kill escalation chain.
        return if (observed is AgentOsExecutionVerdict.Indeterminate) {
            observationEscalation.escalate(adapter, caseId, reservation.attemptId, observed).verdict
        } else {
            observed
        }
    }

    /**
     * Phase 3 of the bridge: validates the verdict, persists the durable outputs as
     * `agent-result` evidence and finalizes the attempt (fenced on the owner token).
     *
     * Authority note: a `pass` evidence is persisted here only on
     * [AgentOsExecutionVerdict.Succeeded]. The SSE verdict itself never derives
     * `Succeeded` from a free-text agent message (see
     * [io.whozoss.factory.adapter.agentos.VerdictDeriver]): the authoritative
     * success of an agent step is a structured result submitted through the
     * `agent-step-results` capability channel; the SSE verdict only observes
     * the lifecycle, explicit failures and human checkpoints.
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
                durableAgentAttemptService.finalize(
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
                durableAgentAttemptService.finalize(
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
                durableAgentAttemptService.finalize(
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
                durableAgentAttemptService.finalize(
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
                durableAgentAttemptService.transition(
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
        val attemptId = UUID.randomUUID().toString()
        publishActiveAgentCase(scope, namespaceId, workflowId, step.id, attemptId, agentId, caseId)
        val claim = newTransaction {
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

    /** Publishes the current worker case as trusted Factory projection metadata. */
    private fun publishActiveAgentCase(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        stepId: String,
        attemptId: String,
        agentId: String,
        caseId: String,
    ) {
        val instance = workflowRepository.findInstance(scope, namespaceId, workflowId) ?: return
        val nextInstance = instance.instance.toMutableMap()
        val controllerExecution = linkedMapOf<String, Any?>(
            "kind" to "agentos",
            "runtimeId" to "agentos-primary",
            "agentId" to agentId,
            "caseId" to caseId,
            "stepId" to stepId,
            "attemptId" to attemptId,
        )
        nextInstance["controllerExecution"] = controllerExecution
        nextInstance["activeAgentCase"] = controllerExecution
        val next = instance.copy(
            revision = instance.revision + 1,
            instance = nextInstance,
        )
        nextInstance["revision"] = next.revision
        workflowRepository.updateInstance(scope, namespaceId, workflowId, instance.revision, next)
    }

    /**
     * Mint the single-use submission capability for [attemptId]. Issuance is
     * best-effort: a non-safe persona or a missing issuer must not fail the DAG
     * step, it only means the worker cannot submit through the capability
     * channel. Failures are swallowed (the step still runs and is recorded).
     */
    /**
     * Creates the result-channel attempt and capability before the AgentOS case request.
     * A configured issuer is authoritative: any issuance failure aborts the run before
     * the first message, so a governed worker can never start without its binding.
     * The null issuer remains supported only for source-level/unit compositions.
     */
    private fun issueDurableCapability(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        step: WorkflowStepDefinition,
        attemptId: String,
        caseId: String,
        agentName: String,
        brief: String,
    ): String? {
        val issuer = agentStepResultService ?: return null
        attemptRepository.insert(
            scope,
            AgentStepAttemptRecord(namespaceId, workflowId, step.id, attemptId, agentName, "running", 1, "{}"),
        )
        return issuer.issue(
            scope,
            AgentStepResultCapabilityIdentity(
                attemptId = attemptId,
                workflowId = workflowId,
                stepId = step.id,
                namespaceId = namespaceId,
                caseId = caseId,
                agentName = agentName,
                briefHash = CanonicalJsonHash.sha256(brief),
            ),
        ).token
    }

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
        val issuer = agentStepResultService
        if (issuer == null) {
            // An absent issuer is an explicit fact, never a silent skip: only
            // pure unit tests may construct this service without the shared
            // AgentStepResultService. In production a missing issuer means a
            // broken composition root and must be observable.
            if (issuerAbsenceLogged.compareAndSet(false, true)) {
                logger.warn {
                    "RESULT_CAPABILITY_ISSUER_ABSENT: no AgentStepResultService is wired — " +
                        "agent steps run without a structured result-submission capability"
                }
            }
            return null
        }
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
        private const val RUN_BRIEF_KIND = "run-brief"

        /** Failure code returned when another execution holds a live lease on the attempt. */
        const val AGENT_ATTEMPT_CONFLICT = "AGENT_ATTEMPT_CONFLICT"

        /**
         * Deterministic, safe-identifier-compatible durable attempt id of a step
         * execution. Keying the UUID on `(workflowId, stepId)` makes the bridge replayable:
         * a retried run recovers the very same attempt (and its AgentOS case)
         * instead of creating a duplicate.
         */
        fun stableAttemptId(workflowId: String, stepId: String): String =
            UUID.nameUUIDFromBytes("factory-attempt|$workflowId|$stepId".toByteArray(Charsets.UTF_8)).toString()

        /**
         * Deterministic durable attempt id of the [attemptNumber]-th execution
         * of a step, used by the retry path. Attempt #1 keeps the historical
         * [stableAttemptId] form (so existing replay/idempotence behaviour is
         * unchanged); a retry allocates
         * [io.whozoss.factory.agentattempt.service.DurableAgentAttemptService.nextAttemptNumber]
         * (>= 2) and registers a brand-new attempt under this id — the prior
         * terminal attempt stays an immutable record of the earlier try.
         */
        fun retryAttemptId(workflowId: String, stepId: String, attemptNumber: Int): String =
            if (attemptNumber <= 1) {
                stableAttemptId(workflowId, stepId)
            } else {
                UUID.nameUUIDFromBytes(
                    "factory-attempt|$workflowId|$stepId|$attemptNumber".toByteArray(Charsets.UTF_8),
                ).toString()
            }

        /**
         * Deterministic AgentOS case UUID bound to a workflow step.
         *
         * AgentOS models case ids as UUIDs. UUID.nameUUIDFromBytes gives us a
         * stable, standards-compliant UUID while preserving replay idempotency.
         */
        fun stableCaseId(workflowId: String, stepId: String): String =
            UUID.nameUUIDFromBytes("$workflowId#$stepId".toByteArray(Charsets.UTF_8)).toString()

        /** Process-local locks serialising the register+claim of one attempt. */
        private val reservationLocks = ConcurrentHashMap<String, ReentrantLock>()
        private const val MAX_QUESTION_TEXT = 2_000
        private const val MAX_QUESTION_TYPE = 100
        private const val MAX_QUESTION_OPTIONS = 20
        private const val MAX_QUESTION_OPTION = 500
    }
}
