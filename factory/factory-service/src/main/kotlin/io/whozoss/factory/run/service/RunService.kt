package io.whozoss.factory.run.service

import io.whozoss.factory.persistence.OrganizationScope
import io.whozoss.factory.persistence.TenantScope
import io.whozoss.factory.proxy.AgentOsProxyClient
import io.whozoss.factory.run.domain.RunDecisionRecord
import io.whozoss.factory.run.domain.RunEscalationRecord
import io.whozoss.factory.run.domain.RunFragmentRecord
import io.whozoss.factory.run.domain.RunRecord
import io.whozoss.factory.run.domain.RunStatuses
import io.whozoss.factory.run.persistence.RunRepository
import io.whozoss.factory.sdk.spi.FactoryFlowSelector
import io.whozoss.factory.sdk.spi.FactoryRunLifecycleObserver
import io.whozoss.factory.sdk.spi.FlowDecision
import io.whozoss.factory.sdk.spi.FragmentOutcome
import io.whozoss.factory.sdk.spi.RunUpdate
import io.whozoss.factory.sdk.spi.StepOutcomeView
import io.whozoss.factory.workflow.domain.CanonicalHash
import io.whozoss.factory.workflow.domain.ControllerExecutionInput
import io.whozoss.factory.workflow.domain.WorkflowDefinitionInput
import io.whozoss.factory.workflow.domain.WorkflowDefinitionRecord
import io.whozoss.factory.workflow.domain.WorkflowErrorCodes
import io.whozoss.factory.workflow.domain.WorkflowEvidenceItem
import io.whozoss.factory.workflow.domain.WorkflowInstanceRecord
import io.whozoss.factory.workflow.domain.WorkflowProjectionRecord
import io.whozoss.factory.workflow.domain.WorkflowStartCommand
import io.whozoss.factory.workflow.domain.WorkflowStatuses
import io.whozoss.factory.workflow.domain.createWorkflowInstance
import io.whozoss.factory.workflow.domain.executionPolicyOf
import io.whozoss.factory.workflow.domain.nowIso
import io.whozoss.factory.workflow.domain.workflowException
import io.whozoss.factory.workflow.persistence.HumanInteractionRepository
import io.whozoss.factory.workflow.persistence.WorkflowEvidenceRepository
import io.whozoss.factory.workflow.persistence.WorkflowRepository
import io.whozoss.factory.workflow.service.SessionDefinitionCatalog
import io.whozoss.factory.workflow.service.SessionRunResult
import io.whozoss.factory.workflow.service.SessionRunService
import io.whozoss.factory.workflow.service.SessionRunSubmissionService
import io.whozoss.factory.workflow.service.toPolicyDefinition
import io.whozoss.factory.workflow.sse.WorkflowSseHub
import mu.KotlinLogging
import org.pf4j.PluginManager
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

/** Command starting a parent Run. */
data class RunStartCommand(
    val runId: String = UUID.randomUUID().toString(),
    val runType: String,
    val title: String,
    val ticket: String? = null,
    val initialDomainState: Map<String, Any?> = emptyMap(),
    val maxTotalFragments: Int = RunRecord.DEFAULT_MAX_TOTAL_FRAGMENTS,
)

/**
 * Driving loop and orchestration service of parent Runs (Lot 1, §4.1–§4.4).
 *
 * Responsibilities:
 * - Resolves the [FactoryFlowSelector] handling `runType` (from PF4J extensions
 *   or registered selector beans).
 * - Creates the parent [RunRecord] with a deterministic `rootCaseId` shared across
 *   every fragment of the run, plus a per-run write lock (D7).
 * - Executes the atomic, idempotent transition at the end of each fragment:
 *   `reduce(run, outcome) -> RunUpdate` then `next(updatedRun) -> FlowDecision`,
 *   enforces loop bounds (`loopKey` / `maxIterations` and `maxTotalFragments`)
 *   and catches selector exceptions, turning both into [FlowDecision.EscalateHuman].
 * - Persists the state update, findings, decision (`decisionKey = fragmentId`),
 *   next fragment `WorkflowInstanceRecord` and outbox submission in a single
 *   atomic transaction.
 * - Handles both fragment-level human steps and run-level human escalations (§4.4).
 */
@Service
class RunService(
    private val runRepository: RunRepository,
    private val workflowRepository: WorkflowRepository,
    private val evidenceRepository: WorkflowEvidenceRepository,
    private val interactionRepository: HumanInteractionRepository,
    private val sessionDefinitionCatalog: SessionDefinitionCatalog,
    private val sessionRunSubmissionService: SessionRunSubmissionService? = null,
    private val agentOsProxyClient: AgentOsProxyClient? = null,
    private val sseHub: WorkflowSseHub? = null,
    private val pluginManager: PluginManager? = null,
    private val registeredSelectors: List<FactoryFlowSelector> = emptyList(),
    private val registeredObservers: List<FactoryRunLifecycleObserver> = emptyList(),
    private val transactionManager: PlatformTransactionManager? = null,
    private val workspaceProvisioning: ParentRunWorkspaceProvisioningService? = null,
) {

    private val logger = KotlinLogging.logger {}

    private val txTemplate: TransactionTemplate? = transactionManager?.let {
        TransactionTemplate(it).apply { propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW }
    }

    private fun <T : Any?> inTransaction(block: () -> T): T {
        val template = txTemplate ?: return block()
        return template.execute { block() } as T
    }

    /**
     * Acquires the per-run exclusive lock (D7: writer agents and code steps on a
     * parent Run's worktree execute sequentially, never two writers concurrently).
     */
    fun <T> withRunWriteLock(
        scope: TenantScope,
        namespaceId: String,
        runId: String,
        block: () -> T,
    ): T {
        val key = "${scope.organizationId}|${scope.workstreamId}|$namespaceId|$runId"
        val lock = runWriteLocks.computeIfAbsent(key) { ReentrantLock() }
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }

    /**
     * Starts (or idempotently replays the start of) a parent [RunRecord] and
     * instantiates its first fragment.
     */
    fun startRun(
        scope: TenantScope,
        namespaceId: String,
        command: RunStartCommand,
        repoRoot: String? = null,
        actorId: String? = null,
    ): RunRecord = withRunWriteLock(scope, namespaceId, command.runId) {
        val provisioning = workspaceProvisioning ?: error("Parent Run workspace provisioning service is required")
        val authenticatedActor = actorId?.takeIf { it.isNotBlank() } ?: error("Authenticated Run launch identity is required")
        val existing = runRepository.find(scope, namespaceId, command.runId)
        if (existing != null && existing.decisions.containsKey(START_DECISION_KEY)) {
            resolveCompatibleSelector(existing)
            verifyPinnedFlowsAvailable(scope, existing)
            provisioning.ensureReady(scope, existing, command, repoRoot, authenticatedActor)
            existing.fragments.lastOrNull()?.let { frag ->
                if (frag.status == WorkflowStatuses.READY || frag.status == WorkflowStatuses.RUNNING) {
                    enqueueFragmentRun(scope, namespaceId, frag.fragmentId, provisioning.readyPath(scope, existing), command.ticket, "start-replay")
                }
            }
            return@withRunWriteLock existing
        }

        val selector = resolveSelector(command.runType)
            ?: throw workflowException(
                WorkflowErrorCodes.WORKFLOW_EXECUTION_PLUGIN_NOT_FOUND,
                "No FactoryFlowSelector found for runType '${command.runType}'.",
                mapOf("runType" to command.runType),
            )

        ensureCatalogDefinitionsSeeded(scope)

        val rootCaseId = existing?.rootCaseId ?: UUID.nameUUIDFromBytes(
            com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(listOf(
                "factory-run-root-case", scope.organizationId, scope.workstreamId, namespaceId, command.runId,
            )),
        ).toString()
        val initialRun = existing ?: RunRecord(
            namespaceId = namespaceId,
            runId = command.runId,
            runType = command.runType,
            selectorVersion = selector.selectorVersion(),
            title = command.title,
            ticket = command.ticket,
            status = RunStatuses.RUNNING,
            revision = 1,
            domainState = command.initialDomainState,
            domainRevision = 0L,
            amendmentSeq = 0L,
            rootCaseId = rootCaseId,
            maxTotalFragments = command.maxTotalFragments,
        )

        val provisionedRun = provisioning.ensureReady(scope, initialRun, command, repoRoot, authenticatedActor)
        val readyRepoRoot = provisioning.readyPath(scope, provisionedRun)
        val (startUpdate, rawDecision) = try {
            selector.start(provisionedRun.toView())
        } catch (error: Exception) {
            logger.warn(error) { "Selector start() failed for run '${command.runId}'; escalating to human" }
            RunUpdate() to FlowDecision.EscalateHuman(
                question = "Selector failed during start: ${error.message ?: error.javaClass.simpleName}",
                options = listOf("retry", "abort"),
                reason = "SELECTOR_EXCEPTION: ${error.message ?: error.javaClass.simpleName}",
            )
        }

        val catalogSnapshot = flowCatalogSnapshot(scope)
        val pinnedInitialRun = provisionedRun.copy(
            flowVersions = catalogSnapshot.mapValues { it.value.version },
            flowDefinitionHashes = catalogSnapshot.mapValues { it.value.definitionHash },
        )
        val updatedRun = pinnedInitialRun.applyUpdate(startUpdate, sourceFragmentId = null)
        val committed = inTransaction {
            val baseRun = runRepository.find(scope, namespaceId, command.runId) ?: error("Run provisioning intent disappeared")
            applyDecisionTransactionally(
                scope = scope,
                namespaceId = namespaceId,
                baseRun = baseRun,
                updatedAfterReduce = updatedRun.copy(
                    revision = baseRun.revision,
                    flowVersions = pinnedInitialRun.flowVersions,
                    flowDefinitionHashes = pinnedInitialRun.flowDefinitionHashes,
                ),
                rawDecision = rawDecision,
                decisionKey = START_DECISION_KEY,
                repoRoot = readyRepoRoot,
            )
        }

        notifyFragmentIfStarted(committed)
        if (RunStatuses.isTerminal(committed.status)) {
            notifyRunFinished(committed)
        }
        return@withRunWriteLock committed
    }

    /**
     * Called by `SessionRunService` when a workflow/fragment reaches a suspended
     * or terminal state. If the workflow belongs to a parent Run, drives the
     * parent Run state machine atomically and idempotently.
     */
    fun onFragmentSessionUpdated(
        scope: TenantScope,
        namespaceId: String,
        fragmentWorkflowId: String,
        sessionResult: SessionRunResult,
        repoRoot: Path,
        sessionRunService: SessionRunService? = null,
    ): RunRecord? {
        val instance = workflowRepository.findInstance(scope, namespaceId, fragmentWorkflowId) ?: return null
        val runId = (instance.instance["runId"] as? String)?.takeIf { it.isNotBlank() }
            ?: ((instance.instance["relations"] as? Map<*, *>)?.get("runId") as? String)?.takeIf { it.isNotBlank() }
            ?: return null

        return withRunWriteLock(scope, namespaceId, runId) {
            val run = runRepository.find(scope, namespaceId, runId) ?: return@withRunWriteLock null

            // If the fragment suspended on a human step inside the DAG (§4.4 level 1),
            // mirror WAITING_HUMAN on the parent Run without invoking reduce/next yet.
            if (sessionResult.status == WorkflowStatuses.WAITING_HUMAN) {
                val updatedFragments = run.fragments.map {
                    if (it.fragmentId == fragmentWorkflowId) it.copy(status = WorkflowStatuses.WAITING_HUMAN) else it
                }
                val nextRun = run.copy(
                    status = RunStatuses.WAITING_HUMAN,
                    revision = run.revision + 1,
                    fragments = updatedFragments,
                )
                runRepository.update(scope, namespaceId, runId, run.revision, nextRun)
                return@withRunWriteLock nextRun
            }

            if (!WorkflowStatuses.isTerminal(sessionResult.status)) {
                return@withRunWriteLock run
            }

            // Idempotency guard (§4.3): if a decision was already sealed for this
            // fragmentId, do not re-run reduce/next or create a duplicate fragment #k+1.
            if (run.decisions.containsKey(fragmentWorkflowId)) {
                logger.debug { "Decision for fragment '$fragmentWorkflowId' of run '$runId' already sealed; skipping duplicate transition." }
                return@withRunWriteLock run
            }

            val fragmentRecord = run.fragments.firstOrNull { it.fragmentId == fragmentWorkflowId }
                ?: return@withRunWriteLock run
            val outcome = buildFragmentOutcome(scope, namespaceId, fragmentRecord, sessionResult)

            notifyFragmentFinished(run, outcome)

            val completedFragments = run.fragments.map {
                if (it.fragmentId == fragmentWorkflowId) {
                    it.copy(status = sessionResult.status, completedAt = nowIso())
                } else {
                    it
                }
            }
            val runWithCompletedFragment = run.copy(fragments = completedFragments)

            val selector = runCatching { resolveCompatibleSelector(run) }.getOrNull()
            val (update, rawDecision) = if (selector == null) {
                RunUpdate() to FlowDecision.Fail(
                    code = WorkflowErrorCodes.WORKFLOW_EXECUTION_PLUGIN_NOT_FOUND,
                    reason = "Selector '${run.runType}@${run.selectorVersion}' is unavailable or incompatible with this resumed run.",
                )
            } else {
                try {
                    val u = selector.reduce(runWithCompletedFragment.toView(), outcome)
                    val previewRun = runWithCompletedFragment.applyUpdate(u, sourceFragmentId = fragmentWorkflowId)
                    val d = selector.next(previewRun.toView())
                    u to d
                } catch (error: Exception) {
                    logger.warn(error) { "Selector reduce/next failed after fragment '$fragmentWorkflowId'; escalating to human" }
                    RunUpdate() to FlowDecision.EscalateHuman(
                        question = "Selector failed after fragment '${fragmentRecord.flowType}': ${error.message ?: error.javaClass.simpleName}",
                        options = listOf("retry", "abort"),
                        reason = "SELECTOR_EXCEPTION: ${error.message ?: error.javaClass.simpleName}",
                    )
                }
            }

            val committed = inTransaction {
                // Re-read inside transaction and derive from that exact revision.
                val fresh = runRepository.find(scope, namespaceId, runId) ?: return@inTransaction run
                if (fresh.decisions.containsKey(fragmentWorkflowId)) return@inTransaction fresh
                val freshCompleted = fresh.copy(fragments = fresh.fragments.map {
                    if (it.fragmentId == fragmentWorkflowId) it.copy(status = sessionResult.status, completedAt = nowIso()) else it
                })
                val compatibleSelector = resolveCompatibleSelector(fresh)
                val freshUpdate = compatibleSelector.reduce(freshCompleted.toView(), outcome)
                val updatedAfterReduce = freshCompleted.applyUpdate(freshUpdate, sourceFragmentId = fragmentWorkflowId)
                val freshDecision = compatibleSelector.next(updatedAfterReduce.toView())
                applyDecisionTransactionally(
                    scope = scope,
                    namespaceId = namespaceId,
                    baseRun = fresh,
                    updatedAfterReduce = updatedAfterReduce,
                    rawDecision = freshDecision,
                    decisionKey = fragmentWorkflowId,
                    repoRoot = repoRoot.toString(),
                )
            }

            notifyFragmentIfStarted(committed)
            if (RunStatuses.isTerminal(committed.status)) {
                notifyRunFinished(committed)
            } else if (committed.status == RunStatuses.RUNNING && sessionRunService != null && sessionRunSubmissionService == null) {
                // Synchronous fallback for unit/integration tests that do not wire
                // the background outbox drain worker: drive the newly created fragment.
                val nextFragment = committed.fragments.lastOrNull()
                if (nextFragment != null && nextFragment.fragmentId != fragmentWorkflowId) {
                    sessionRunService.runSession(scope, namespaceId, nextFragment.fragmentId, repoRoot, committed.ticket)
                }
            }
            return@withRunWriteLock committedRepositoryState(scope, namespaceId, runId) ?: committed
        }
    }

    /**
     * Resolves a run-level human escalation (`WAITING_HUMAN_ESCALATION`, §4.4 level 2).
     *
     * Records the human choice as a synthetic `ESCALATION_RESOLVED` [FragmentOutcome],
     * resets the loop counter when the human chooses `"retry"` (or `"reset_loop"`),
     * runs `selector.reduce` + `selector.next` atomically, and resumes the Run.
     */
    fun replyRunEscalation(
        scope: TenantScope,
        namespaceId: String,
        runId: String,
        escalationId: String,
        selectedOption: String,
        text: String? = null,
        actorId: String,
        repoRoot: Path? = null,
        sessionRunService: SessionRunService? = null,
    ): RunRecord = withRunWriteLock(scope, namespaceId, runId) {
        val run = runRepository.find(scope, namespaceId, runId)
            ?: throw workflowException(WorkflowErrorCodes.WORKFLOW_NOT_FOUND, "Run '$runId' not found.")
        val escalation = run.activeEscalation
        if (run.status != RunStatuses.WAITING_HUMAN_ESCALATION || escalation == null || escalation.escalationId != escalationId) {
            throw workflowException(WorkflowErrorCodes.INTERACTION_STALE, "Run '$runId' has no active escalation '$escalationId'.")
        }
        if (escalation.options.isNotEmpty() && selectedOption !in escalation.options) {
            throw workflowException(WorkflowErrorCodes.ACTION_NOT_ALLOWED, "Option '$selectedOption' is not allowed.")
        }

        val decisionKey = "escalation:$escalationId"
        if (run.decisions.containsKey(decisionKey)) {
            return@withRunWriteLock run
        }

        // If the human chose "abort", fail the run cleanly unless the selector overrides.
        val syntheticOutcome = FragmentOutcome(
            fragmentId = escalationId,
            flowType = "factory.escalation",
            status = "ESCALATION_RESOLVED",
            stepOutcomes = listOf(
                StepOutcomeView(
                    stepId = "escalation",
                    kind = "human",
                    status = if (selectedOption == "abort") WorkflowStatuses.FAILED else WorkflowStatuses.COMPLETED,
                    humanDecision = mapOf(
                        "escalationId" to escalationId,
                        "selectedOption" to selectedOption,
                        "text" to text,
                        "actorId" to actorId,
                    ),
                ),
            ),
        )

        // Reset loop counters if the human approved a retry after a loop-bound escalation
        val adjustedLoopCounts = if (selectedOption in setOf("retry", "reset_loop", "approve")) {
            emptyMap()
        } else {
            run.loopCounts
        }
        val runForSelector = run.copy(
            loopCounts = adjustedLoopCounts,
            activeEscalation = null,
        )

        val selector = resolveCompatibleSelector(run)

        val (update, rawDecision) = try {
                val u = selector.reduce(runForSelector.toView(), syntheticOutcome)
            val preview = runForSelector.applyUpdate(u, sourceFragmentId = escalationId)
            val d = if (selectedOption == "abort") {
                FlowDecision.Fail(code = "HUMAN_ABORTED", reason = text ?: "Aborted by human authority ($actorId)")
            } else {
                // In the preview passed to next(), mark the last fragment status as ESCALATION_RESOLVED
                // so the selector knows it is deciding the continuation of the human escalation
                // rather than re-evaluating the old failed fragment.
                val escalationAdjustedFragments = preview.fragments.mapIndexed { idx, frag ->
                    if (idx == preview.fragments.lastIndex) frag.copy(status = "ESCALATION_RESOLVED") else frag
                }
                selector.next(preview.copy(fragments = escalationAdjustedFragments).toView())
            }
            u to d
        } catch (error: Exception) {
            RunUpdate() to FlowDecision.Fail(
                code = "SELECTOR_ESCALATION_FAILURE",
                reason = error.message ?: error.javaClass.simpleName,
            )
        }

        val updatedAfterReduce = runForSelector.applyUpdate(update, sourceFragmentId = escalationId)
        val effectiveRoot = repoRoot ?: resolveRepoRoot(namespaceId)

        val committed = inTransaction {
            applyDecisionTransactionally(
                scope = scope,
                namespaceId = namespaceId,
                baseRun = run,
                updatedAfterReduce = updatedAfterReduce.copy(revision = run.revision),
                rawDecision = rawDecision,
                decisionKey = decisionKey,
                repoRoot = effectiveRoot.toString(),
            )
        }

        notifyFragmentIfStarted(committed)
        if (RunStatuses.isTerminal(committed.status)) {
            notifyRunFinished(committed)
        } else if (committed.status == RunStatuses.RUNNING && sessionRunService != null && sessionRunSubmissionService == null) {
            val nextFragment = committed.fragments.lastOrNull()
            if (nextFragment != null) {
                sessionRunService.runSession(scope, namespaceId, nextFragment.fragmentId, effectiveRoot, committed.ticket)
            }
        }
        return@withRunWriteLock committedRepositoryState(scope, namespaceId, runId) ?: committed
    }

    fun getRun(scope: TenantScope, namespaceId: String, runId: String): RunRecord? =
        runRepository.find(scope, namespaceId, runId)

    fun listRuns(scope: TenantScope, namespaceId: String? = null): List<RunRecord> =
        runRepository.list(scope, namespaceId)

    /**
     * Applies a synchronous [RunUpdate] emitted by a [io.whozoss.factory.sdk.spi.FactoryStepResultValidator]
     * (`StepValidationVerdict.Accepted`) onto the parent Run under optimistic
     * concurrency control (Lot 2, §5.3). A full domainState replacement must
     * carry the revision observed by the validator; findings-only updates do not.
     */
    fun applyStepValidationUpdate(
        scope: TenantScope,
        namespaceId: String,
        runId: String,
        sourceFragmentId: String,
        update: RunUpdate,
        idempotencyKey: String? = null,
        joinCallerTransaction: Boolean = false,
    ): RunRecord? = withRunWriteLock(scope, namespaceId, runId) {
        if (update.newDomainState == null && update.addedFindings.isEmpty() && update.findingResolutions.isEmpty()) {
            return@withRunWriteLock runRepository.find(scope, namespaceId, runId)
        }
        val persist: () -> RunRecord? = persist@{
            val current = runRepository.find(scope, namespaceId, runId) ?: return@persist null
            if (idempotencyKey != null && idempotencyKey in current.appliedValidationUpdates) {
                return@persist current
            }
            if (update.newDomainState != null && update.expectedDomainRevision != current.domainRevision) {
                throw workflowException(
                    WorkflowErrorCodes.REVISION_CONFLICT,
                    "Stale domainState validation update on Run '$runId': expected ${update.expectedDomainRevision}, actual ${current.domainRevision}.",
                )
            }
            val next = current.applyUpdate(update, sourceFragmentId = sourceFragmentId)
                .copy(
                    revision = current.revision + 1,
                    appliedValidationUpdates = if (idempotencyKey == null) current.appliedValidationUpdates else current.appliedValidationUpdates + idempotencyKey,
                )
            if (!runRepository.update(scope, namespaceId, runId, current.revision, next)) {
                throw workflowException(
                    WorkflowErrorCodes.REVISION_CONFLICT,
                    "Concurrent domainState update on Run '$runId' during step validation.",
                )
            }
            next
        }
        if (joinCallerTransaction && !TransactionSynchronizationManager.isActualTransactionActive()) {
            throw IllegalStateException("A caller transaction is required for atomic validator persistence")
        }
        return@withRunWriteLock if (joinCallerTransaction) persist() else inTransaction(persist)
    }

    /**
     * Evaluates loop bounds on [rawDecision], materializes the next fragment
     * `WorkflowInstanceRecord` if the effective decision is [FlowDecision.Apply],
     * updates the [RunRecord] via CAS, records the decision evidence, and
     * enqueues the outbox submission.
     */
    private fun applyDecisionTransactionally(
        scope: TenantScope,
        namespaceId: String,
        baseRun: RunRecord,
        updatedAfterReduce: RunRecord,
        rawDecision: FlowDecision,
        decisionKey: String,
        repoRoot: String?,
    ): RunRecord {
        val nextLoopCounts = LinkedHashMap(updatedAfterReduce.loopCounts)
        val effectiveDecision: FlowDecision = when (rawDecision) {
            is FlowDecision.Apply -> {
                val nextOrdinal = updatedAfterReduce.fragments.size + 1
                if (nextOrdinal > updatedAfterReduce.maxTotalFragments) {
                    FlowDecision.EscalateHuman(
                        question = "Run '${baseRun.runId}' reached its maximum fragment bound (${updatedAfterReduce.maxTotalFragments}). How do you want to proceed?",
                        options = listOf("retry", "abort"),
                        reason = "MAX_TOTAL_FRAGMENTS_EXCEEDED (${updatedAfterReduce.maxTotalFragments})",
                    )
                } else if (rawDecision.loopKey != null) {
                    val count = (nextLoopCounts[rawDecision.loopKey] ?: 0) + 1
                    nextLoopCounts[rawDecision.loopKey!!] = count
                    val maxIter = rawDecision.maxIterations
                    if (maxIter != null && count > maxIter) {
                        FlowDecision.EscalateHuman(
                            question = "Loop '${rawDecision.loopKey}' exceeded its maximum iteration bound ($maxIter). How do you want to proceed?",
                            options = listOf("retry", "abort"),
                            reason = "LOOP_BOUND_EXCEEDED (${rawDecision.loopKey}: $count > $maxIter)",
                        )
                    } else {
                        rawDecision
                    }
                } else {
                    rawDecision
                }
            }
            else -> rawDecision
        }

        val nextFragments = ArrayList(updatedAfterReduce.fragments)
        var nextStatus = updatedAfterReduce.status
        var nextEscalation: RunEscalationRecord? = null
        var createdFragmentId: String? = null
        var appliedFlowType: String? = null
        var appliedLoopKey: String? = null
        val decisionReason: String

        when (effectiveDecision) {
            is FlowDecision.Apply -> {
                val ordinal = nextFragments.size + 1
                val fragmentId = "${baseRun.runId}__f$ordinal"
                createdFragmentId = fragmentId
                appliedFlowType = effectiveDecision.flowType
                appliedLoopKey = effectiveDecision.loopKey
                decisionReason = effectiveDecision.reason

                materializeFragmentWorkflowInstance(
                    scope = scope,
                    namespaceId = namespaceId,
                    run = updatedAfterReduce,
                    fragmentId = fragmentId,
                    ordinal = ordinal,
                    flowType = effectiveDecision.flowType,
                    inputs = effectiveDecision.inputs,
                )

                nextFragments.add(
                    RunFragmentRecord(
                        fragmentId = fragmentId,
                        ordinal = ordinal,
                        flowType = effectiveDecision.flowType,
                        status = WorkflowStatuses.READY,
                        inputs = effectiveDecision.inputs,
                        decisionReason = effectiveDecision.reason,
                        loopKey = effectiveDecision.loopKey,
                    ),
                )
                nextStatus = RunStatuses.RUNNING
            }

            is FlowDecision.Complete -> {
                decisionReason = effectiveDecision.reason
                nextStatus = RunStatuses.COMPLETED
            }

            is FlowDecision.EscalateHuman -> {
                decisionReason = effectiveDecision.reason
                nextStatus = RunStatuses.WAITING_HUMAN_ESCALATION
                nextEscalation = RunEscalationRecord(
                    escalationId = UUID.randomUUID().toString(),
                    question = effectiveDecision.question,
                    options = effectiveDecision.options.ifEmpty { listOf("retry", "abort") },
                    reason = effectiveDecision.reason,
                )
            }

            is FlowDecision.Fail -> {
                decisionReason = "${effectiveDecision.code}: ${effectiveDecision.reason}"
                nextStatus = RunStatuses.FAILED
            }
        }

        val decisionRecord = RunDecisionRecord(
            decisionKey = decisionKey,
            decisionType = effectiveDecision.javaClass.simpleName.lowercase(),
            flowType = appliedFlowType,
            fragmentId = createdFragmentId,
            reason = decisionReason,
            loopKey = appliedLoopKey,
            domainRevisionAfter = updatedAfterReduce.domainRevision,
        )
        val nextDecisions = LinkedHashMap(updatedAfterReduce.decisions).apply {
            put(decisionKey, decisionRecord)
        }

        val nextRun = updatedAfterReduce.copy(
            status = nextStatus,
            revision = baseRun.revision + 1,
            fragments = nextFragments,
            loopCounts = nextLoopCounts,
            decisions = nextDecisions,
            activeEscalation = nextEscalation,
        )

        if (!runRepository.update(scope, namespaceId, baseRun.runId, baseRun.revision, nextRun)) {
            throw workflowException(WorkflowErrorCodes.REVISION_CONFLICT, "Concurrent update on Run '${baseRun.runId}'.")
        }

        // Audit every FlowDecision as a durable evidence item (§4.3)
        val evidenceTargetWorkflowId = createdFragmentId
            ?: nextFragments.lastOrNull()?.fragmentId
            ?: baseRun.runId
        evidenceRepository.append(
            scope,
            namespaceId,
            evidenceTargetWorkflowId,
            WorkflowEvidenceItem(
                evidenceId = UUID.randomUUID().toString(),
                namespaceId = namespaceId,
                workflowId = evidenceTargetWorkflowId,
                stepId = null,
                kind = "run-flow-decision",
                outcome = decisionRecord.decisionType,
                source = mapOf("kind" to "factory-run-selector", "runId" to baseRun.runId, "selectorVersion" to baseRun.selectorVersion),
                facts = buildMap {
                    put("runId", baseRun.runId)
                    put("decisionKey", decisionKey)
                    put("decisionType", decisionRecord.decisionType)
                    put("reason", decisionReason)
                    put("domainRevision", updatedAfterReduce.domainRevision)
                    if (appliedFlowType != null) put("flowType", appliedFlowType)
                    if (createdFragmentId != null) put("fragmentId", createdFragmentId)
                    if (appliedLoopKey != null) put("loopKey", appliedLoopKey)
                },
                idempotencyKey = "run-decision:${baseRun.runId}:$decisionKey",
                createdAt = null,
            ),
        )

        if (createdFragmentId != null) {
            enqueueFragmentRun(
                scope = scope,
                namespaceId = namespaceId,
                fragmentWorkflowId = createdFragmentId,
                repoRoot = repoRoot,
                ticket = baseRun.ticket,
                operation = "run-fragment:$decisionKey",
            )
        }

        sseHub?.publish(
            scope,
            namespaceId,
            mapOf(
                "runId" to baseRun.runId,
                "workflowId" to (createdFragmentId ?: baseRun.runId),
                "namespaceId" to namespaceId,
                "status" to nextStatus,
                "revision" to nextRun.revision,
            ),
        )

        return nextRun
    }

    private fun materializeFragmentWorkflowInstance(
        scope: TenantScope,
        namespaceId: String,
        run: RunRecord,
        fragmentId: String,
        ordinal: Int,
        flowType: String,
        inputs: Map<String, Any?>,
    ) {
        val definitionRecord = resolveFlowDefinition(scope, flowType, run)
        val executionPolicy = definitionRecord.executionPolicy ?: executionPolicyOf(definitionRecord.definition)
        val definitionInput = WorkflowDefinitionInput(
            workflowType = definitionRecord.workflowType,
            version = definitionRecord.version,
            definitionHash = definitionRecord.definitionHash,
            steps = definitionRecord.toPolicyDefinition().steps,
            executionPolicy = executionPolicy,
        )
        val relations = linkedMapOf<String, Any?>(
            "rootWorkflowId" to run.runId,
            "runId" to run.runId,
            "fragmentOrdinal" to ordinal,
        )
        run.fragments.lastOrNull()?.let { prev ->
            relations["previousWorkflowId"] = prev.fragmentId
        }
        if (!run.ticket.isNullOrBlank()) {
            relations["ticket"] = run.ticket
        }
        val startCommand = WorkflowStartCommand(
            workflowId = fragmentId,
            workflowType = flowType,
            title = "${run.title} [#$ordinal $flowType]",
            relations = relations,
            ticket = run.ticket,
            expectedAmendmentSeq = run.amendmentSeq,
        )
        val controllerExecution = ControllerExecutionInput(
            runtimeId = "factory-run-driver",
            kind = "factory-run",
            agentId = "factory-run-selector",
            caseId = run.rootCaseId,
            namespaceId = namespaceId,
        )
        val created = createWorkflowInstance(startCommand, definitionInput, controllerExecution)
        val instanceMap = LinkedHashMap(created.instance).apply {
            put("runId", run.runId)
            put("fragmentOrdinal", ordinal)
            put("fragmentInputs", inputs)
        }
        val projectionMap = LinkedHashMap(created.projection).apply {
            put("runId", run.runId)
            put("fragmentOrdinal", ordinal)
            put("fragmentInputs", inputs)
        }
        val record = WorkflowInstanceRecord(
            namespaceId = namespaceId,
            workflowId = fragmentId,
            revision = 1,
            status = "active",
            creationCommandHash = created.creationCommandHash,
            instance = instanceMap,
            projection = projectionMap,
            rootCaseId = run.rootCaseId,
            amendmentSeq = run.amendmentSeq,
        )
        workflowRepository.insertInstance(scope, record)
        val projectionRecord = WorkflowProjectionRecord(
            namespaceId = namespaceId,
            workflowId = fragmentId,
            schemaVersion = (projectionMap["schemaVersion"] as? String) ?: "2",
            revision = 0,
            projectionHash = CanonicalHash.workflowProjectionHash(projectionMap),
            status = (projectionMap["status"] as? String) ?: WorkflowStatuses.READY,
            projection = projectionMap,
            instance = instanceMap,
            governanceMode = "governed",
            definitionVersion = definitionRecord.version,
            definitionHash = definitionRecord.definitionHash,
            relations = relations,
            controllerExecution = controllerExecution.toJson(),
            lifecycleState = "active",
        )
        runCatching { workflowRepository.publishProjection(scope, projectionRecord, 0) }
    }

    private fun buildFragmentOutcome(
        scope: TenantScope,
        namespaceId: String,
        fragment: RunFragmentRecord,
        sessionResult: SessionRunResult,
    ): FragmentOutcome {
        val instance = workflowRepository.findInstance(scope, namespaceId, fragment.fragmentId)
        val definitionSteps = instance?.let {
            val wfType = it.instance["workflowType"] as? String
            val wfVer = it.instance["definitionVersion"] as? String
            if (wfType != null && wfVer != null) {
                workflowRepository.findDefinition(scope, wfType, wfVer)?.toPolicyDefinition()?.steps
            } else {
                null
            }
        }.orEmpty().associateBy { it.id }

        val evidence = evidenceRepository.list(scope, namespaceId, fragment.fragmentId)
        val interactions = interactionRepository.list(scope, namespaceId, fragment.fragmentId, openOnly = false)

        val stepOutcomes = sessionResult.steps.map { stepState ->
            val def = definitionSteps[stepState.stepId]
            val kind = def?.responsibility?.kind?.wire ?: "agent"
            val stepEvidence = evidence.filter { it.stepId == stepState.stepId }
            val agentResult = stepEvidence.lastOrNull { it.kind == "agent-result" }
            @Suppress("UNCHECKED_CAST")
            val report = (agentResult?.facts?.get("outputs") as? Map<String, Any?>)
                ?: agentResult?.facts
                ?: emptyMap()
            val oracleResults = stepEvidence
                .filter { it.kind in setOf("code-verification", "oracle-result") }
                .map { it.facts }
            val lastInteraction = interactions.filter { it.stepId == stepState.stepId }.maxByOrNull { it.revision }
            @Suppress("UNCHECKED_CAST")
            val humanDecision = (lastInteraction?.payload?.get("response") as? Map<String, Any?>)
            val failureFact = stepEvidence.lastOrNull { it.outcome in setOf("fail", WorkflowStatuses.FAILED) }?.facts
            StepOutcomeView(
                stepId = stepState.stepId,
                kind = kind,
                status = stepState.status,
                report = report,
                oracleResults = oracleResults,
                humanDecision = humanDecision,
                errorCode = failureFact?.get("code") as? String,
                errorMessage = failureFact?.get("message") as? String,
            )
        }

        return FragmentOutcome(
            fragmentId = fragment.fragmentId,
            flowType = fragment.flowType,
            status = sessionResult.status,
            stepOutcomes = stepOutcomes,
        )
    }

    private fun resolveSelector(runType: String): FactoryFlowSelector? {
        registeredSelectors.firstOrNull { runType in it.runTypes() }?.let { return it }
        val manager = pluginManager ?: return null
        val extensions = runCatching { manager.getExtensions(FactoryFlowSelector::class.java) }.getOrDefault(emptyList())
        return extensions.firstOrNull { runType in it.runTypes() }
    }

    private fun resolveCompatibleSelector(run: RunRecord): FactoryFlowSelector {
        val selector = resolveSelector(run.runType)
            ?: throw workflowException(
                WorkflowErrorCodes.WORKFLOW_EXECUTION_PLUGIN_NOT_FOUND,
                "No FactoryFlowSelector found for runType '${run.runType}'.",
            )
        if (selector.selectorVersion() != run.selectorVersion) {
            throw workflowException(
                WorkflowErrorCodes.ACTION_NOT_ALLOWED,
                "Run '${run.runId}' is pinned to selector '${run.selectorVersion}', but '${selector.selectorVersion()}' is installed.",
                mapOf(
                    "runId" to run.runId,
                    "runType" to run.runType,
                    "expectedSelectorVersion" to run.selectorVersion,
                    "actualSelectorVersion" to selector.selectorVersion(),
                ),
            )
        }
        return selector
    }

    private fun resolveObservers(): List<FactoryRunLifecycleObserver> {
        val pluginObservers = pluginManager?.let {
            runCatching { it.getExtensions(FactoryRunLifecycleObserver::class.java) }.getOrDefault(emptyList())
        }.orEmpty()
        return registeredObservers + pluginObservers
    }

    private fun notifyFragmentIfStarted(run: RunRecord) {
        if (run.status != RunStatuses.RUNNING) return
        val latest = run.fragments.lastOrNull() ?: return
        val runView = run.toView()
        val fragmentView = latest.toView()
        for (observer in resolveObservers()) {
            runCatching { observer.onFragmentStarted(runView, fragmentView) }
                .onFailure { logger.warn(it) { "FactoryRunLifecycleObserver.onFragmentStarted failed (swallowed)" } }
        }
    }

    private fun notifyFragmentFinished(run: RunRecord, outcome: FragmentOutcome) {
        val runView = run.toView()
        for (observer in resolveObservers()) {
            runCatching { observer.onFragmentFinished(runView, outcome) }
                .onFailure { logger.warn(it) { "FactoryRunLifecycleObserver.onFragmentFinished failed (swallowed)" } }
        }
    }

    private fun notifyRunFinished(run: RunRecord) {
        val runView = run.toView()
        for (observer in resolveObservers()) {
            runCatching { observer.onRunFinished(runView, run.status) }
                .onFailure { logger.warn(it) { "FactoryRunLifecycleObserver.onRunFinished failed (swallowed)" } }
        }
    }

    private fun ensureCatalogDefinitionsSeeded(scope: TenantScope) {
        val orgScope = OrganizationScope(scope.organizationId)
        val existing = workflowRepository.listDefinitions(orgScope).map { it.workflowType to it.version }.toSet()
        for (record in sessionDefinitionCatalog.load()) {
            if ((record.workflowType to record.version) !in existing) {
                workflowRepository.saveDefinition(orgScope, record)
            }
        }
    }

    private fun resolveFlowDefinition(scope: TenantScope, flowType: String, run: RunRecord): WorkflowDefinitionRecord {
        val pinnedVersion = run.flowVersions[flowType]
        val matches = workflowRepository.listDefinitions(scope).filter { it.workflowType == flowType }
        if (pinnedVersion != null) {
            val pinned = matches.firstOrNull { it.version == pinnedVersion }
                ?: throw workflowException(
                    WorkflowErrorCodes.WORKFLOW_DEFINITION_NOT_FOUND,
                    "Run '${run.runId}' is pinned to flow '$flowType@$pinnedVersion', which is unavailable.",
                )
            val expectedHash = run.flowDefinitionHashes[flowType]
            if (expectedHash != null && pinned.definitionHash != expectedHash) {
                throw workflowException(
                    WorkflowErrorCodes.ACTION_NOT_ALLOWED,
                    "Run '${run.runId}' is pinned to flow '$flowType@$pinnedVersion' hash '$expectedHash', but '${pinned.definitionHash}' is installed.",
                )
            }
            return pinned
        }
        if (run.decisions.isNotEmpty()) {
            throw workflowException(
                WorkflowErrorCodes.ACTION_NOT_ALLOWED,
                "Legacy run '${run.runId}' has no frozen flow catalog and cannot be resumed safely.",
            )
        }
        if (matches.isNotEmpty()) {
            return matches.maxByOrNull { it.version }!!
        }
        val fromCatalog = sessionDefinitionCatalog.load().filter { it.workflowType == flowType }
        if (fromCatalog.isNotEmpty()) {
            val chosen = fromCatalog.maxByOrNull { it.version }!!
            workflowRepository.saveDefinition(OrganizationScope(scope.organizationId), chosen)
            return chosen
        }
        throw workflowException(
            WorkflowErrorCodes.WORKFLOW_DEFINITION_NOT_FOUND,
            "Flow definition '$flowType' was not found in repository or catalog.",
        )
    }

    private fun flowCatalogSnapshot(scope: TenantScope): Map<String, WorkflowDefinitionRecord> =
        workflowRepository.listDefinitions(scope)
            .groupBy { it.workflowType }
            .mapValues { (_, records) -> records.maxBy { it.version } }

    private fun verifyPinnedFlowsAvailable(scope: TenantScope, run: RunRecord) {
        if (run.flowVersions.isEmpty()) {
            throw workflowException(WorkflowErrorCodes.ACTION_NOT_ALLOWED, "Legacy run '${run.runId}' has no frozen flow catalog and cannot be replayed safely.")
        }
        run.flowVersions.keys.forEach { resolveFlowDefinition(scope, it, run) }
    }

    private fun enqueueFragmentRun(
        scope: TenantScope,
        namespaceId: String,
        fragmentWorkflowId: String,
        repoRoot: String?,
        ticket: String?,
        operation: String,
    ) {
        val submitter = sessionRunSubmissionService ?: return
        val resolvedRoot = repoRoot?.takeIf { it.isNotBlank() }
            ?: resolveRepoRoot(namespaceId).toString()
        submitter.submit(scope, namespaceId, fragmentWorkflowId, resolvedRoot, operation, ticket)
    }

    private fun resolveRepoRoot(namespaceId: String): Path =
        agentOsProxyClient
            ?.let { client ->
                runCatching { client.resolveRepoRoot(namespaceId, null) }
                    .getOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?.let(Path::of)
            }
            ?: Path.of(".").toAbsolutePath().normalize()

    private fun committedRepositoryState(scope: TenantScope, namespaceId: String, runId: String): RunRecord? =
        runRepository.find(scope, namespaceId, runId)

    companion object {
        const val START_DECISION_KEY = "start"
        private val runWriteLocks = ConcurrentHashMap<String, ReentrantLock>()

        /** Deterministic AgentOS root case UUID owned by the parent Run. */
        fun stableRunRootCaseId(runId: String): String =
            UUID.nameUUIDFromBytes("factory-run-root-case|$runId".toByteArray(StandardCharsets.UTF_8)).toString()
    }
}
