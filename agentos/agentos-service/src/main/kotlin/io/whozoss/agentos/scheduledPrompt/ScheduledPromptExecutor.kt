package io.whozoss.agentos.scheduledPrompt

import io.whozoss.agentos.agentConfig.AgentConfigService
import io.whozoss.agentos.caseFlow.Case
import io.whozoss.agentos.caseFlow.CaseRuntime
import io.whozoss.agentos.caseFlow.CaseService
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.permissions.PermissionRelation
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.prompt.PromptService
import io.whozoss.agentos.sdk.actor.Actor
import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.caseEvent.MessageContent
import io.whozoss.agentos.sdk.caseFlow.CaseStatus
import io.whozoss.agentos.user.UserService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import mu.KLogging
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Executes [ScheduledPromptRun]s in two phases.
 *
 * ### Phase A — Materialisation (fast, no throttle)
 *
 * [materialize] is called by [SchedulerScanner.claim] after the Run has been inserted.
 * It resolves all target users and creates PENDING [ScheduledPromptUserRun]s via a single
 * Cypher INSERT-SELECT, then transitions the Run to RUNNING (when UserRuns were created)
 * or directly to DONE (when no target users exist) — all in a single `@Transactional` boundary.
 *
 * If the service crashes between the Run insert in [SchedulerScanner.claim] and the
 * [materialize] call, the Run remains CLAIMED forever. [SchedulerScanner.recoverOrphanedClaimedRuns]
 * detects such orphans on the next tick and marks them FAILED, unblocking the overlap guard.
 *
 * ### Phase B — Consumption (throttled)
 *
 * Called by [SchedulerScanner.tickConsume] on every consume tick.
 * Loops over [ScheduledPromptUserRunRepository.claimBatch] until the queue is empty.
 * Each claimed [ScheduledPromptUserRun] is executed in a background coroutine:
 * 1. Create a [Case] in the prompt's namespace.
 * 2. Grant ADMIN on the Case to the target user via [PermissionService.grantPermission].
 * 3. Build an [Actor] with `role = USER`.
 * 4. Resolve the prompt content directly and inject `"@agentName <content>"` via
 *    [CaseService.addMessage]. The Executor resolves content itself rather than using
 *    a `/slash-command` because PromptCommandParser requires the text to start with `/`,
 *    which is incompatible with the leading `@mention`.
 * 5. Await the Case launch: inspect the runtime's [CaseRuntime.statusFlow] current value,
 *    then observe future transitions for up to [SchedulerProperties.launchTimeoutSeconds]
 *    seconds to detect immediate failures. If the Case is IDLE (turn finished) it is closed
 *    as DONE. If still RUNNING after the timeout, monitoring is released (Case continues
 *    independently) and the UserRun is closed as TIMEOUT. An immediate terminal status
 *    (ERROR/KILLED) marks the UserRun as FAILED.
 *
 * Concurrency is bounded by [SchedulerProperties.batchSize]: at most that many UserRuns
 * are claimed per batch. Within a batch, UserRuns are grouped by their parent Run and each
 * group is processed concurrently under a [supervisorScope] so a failure in one group does
 * not cancel the others. Further burst control is delegated to Spring AI's `RetryTemplate`
 * on each `ChatModel` (exponential backoff on 429 rate-limit responses).
 *
 * ### Completion check
 *
 * After each batch of UserRuns finishes, the distinct Runs touched in that batch are
 * checked via [checkCompletion] — one call per Run, not per UserRun. Each check uses
 * two LIMIT 1 queries:
 * 1. [ScheduledPromptUserRunRepository.hasAnyActive] — fast-path exit when work is still in flight.
 * 2. [ScheduledPromptUserRunRepository.hasAnyFailed] — only reached when all UserRuns are terminal.
 * Result: FAILED if any UserRun failed, DONE otherwise.
 * Only RUNNING Runs are inspected — the CLAIMED→RUNNING transition in Phase A acts as
 * the guard ensuring all UserRuns have been materialised before completion is evaluated.
 */
@Component
@ConditionalOnProperty(name = ["agentos.prompt.scheduler.enabled"], havingValue = "true")
class ScheduledPromptExecutor(
    private val scheduledPromptRepository: ScheduledPromptRepository,
    private val runRepository: ScheduledPromptRunRepository,
    private val userRunRepository: ScheduledPromptUserRunRepository,
    private val promptService: PromptService,
    private val agentConfigService: AgentConfigService,
    private val caseService: CaseService,
    private val permissionService: PermissionService,
    private val userService: UserService,
    private val properties: SchedulerProperties,
    private val clock: Clock,
) {

    // -------------------------------------------------------------------------
    // Phase A — Materialisation
    // -------------------------------------------------------------------------

    /**
     * Materialise [ScheduledPromptUserRun]s for all target users of [run].
     *
     * Delegates to a single Cypher INSERT-SELECT that resolves all deployment-target
     * users via two paths (UserGroup deployment, Namespace deployment) and MERGEs one
     * PENDING UserRun per distinct user — entirely inside Neo4j, no JVM heap pressure.
     *
     * Safe to call multiple times for the same Run (idempotent via MERGE).
     * Transitions the Run to [RunStatus.RUNNING] when UserRuns are created, or directly
     * to [RunStatus.DONE] when no target users exist (e.g. platform-scope ScheduledPrompt).
     *
     * The MERGE and the CLAIMED→RUNNING transition run in a single `@Transactional`:
     * if the service crashes between the two, neither orphaned PENDING UserRuns (never
     * consumed) nor a RUNNING Run with no UserRuns can result.
     *
     * Note: the Run insert happens in [SchedulerScanner.claim], **outside** this transaction.
     * A crash between the insert and this call, or an exception thrown by
     * [ScheduledPromptUserRunRepository.materialize], leaves an orphaned CLAIMED Run —
     * Spring rolls back the transaction so no partial UserRuns are written.
     * [SchedulerScanner.recoverOrphanedClaimedRuns] detects such orphans and marks them
     * FAILED on the next tick. The caller ([SchedulerScanner.claim]) is protected by
     * `runCatching` so one failure does not block other prompts in the same tick.
     */
    @Transactional
    fun materialize(run: ScheduledPromptRun, scheduledPrompt: ScheduledPrompt) {
        logger.info {
            "[Executor] Phase A: materialising run=${run.id} sp=${scheduledPrompt.id}"
        }

        val namespaceId = scheduledPrompt.namespaceId
        // Transition based on whether any UserRuns were created:
        // - RUNNING when count > 0 — Phase B will consume the UserRuns and checkCompletion
        //   will close the Run once all are terminal.
        // - DONE when count == 0 — no UserRuns to consume (platform-scope ScheduledPrompt
        //   with no target users, or namespace with no deployed users). Transitioning to
        //   RUNNING would leave the Run stuck forever since checkCompletion requires at
        //   least one UserRun closure to trigger.
        // - On exception from userRunRepository.materialize: the exception propagates,
        //   Spring rolls back the transaction, and the Run stays CLAIMED. The sweep
        //   recoverOrphanedClaimedRuns marks it FAILED on the next tick. The caller
        //   (SchedulerScanner.claim) is protected by runCatching so one failure does
        //   not block the other prompts in the same tick.
        val count = when {
            namespaceId == null -> {
                logger.debug {
                    "[Executor] Platform-scope sp=${scheduledPrompt.id} — no users to materialise"
                }
                0
            }
            else -> userRunRepository.materialize(run.id, scheduledPrompt.agentConfigId, namespaceId)
        }

        val finalStatus = if (count > 0) RunStatus.RUNNING else RunStatus.DONE
        runRepository.updateStatus(run.id, finalStatus, if (count == 0) Instant.now(clock) else null)

        logger.info {
            "[Executor] Phase A complete: run=${run.id} — $count UserRun(s) created, status=$finalStatus"
        }
    }

    // -------------------------------------------------------------------------
    // Phase B — Consumption
    // -------------------------------------------------------------------------

    /**
     * Claim and execute all currently available [ScheduledPromptUserRun]s.
     *
     * Suspends until ALL UserRuns from ALL batches have finished executing, so the
     * caller ([SchedulerScanner.tickConsume]) suspends for the full duration.
     * Spring `fixedDelay` on [SchedulerScanner.tickConsume] prevents a new consume
     * tick from starting before this one completes.
     *
     * Each batch is claimed via [ScheduledPromptUserRunRepository.claimBatch], which
     * uses SDN `@Version` optimistic locking so concurrent instances cannot double-claim
     * the same UserRun. Within a batch, each UserRun is launched as a structured
     * coroutine inside [supervisorScope]; the scope suspends until all launched children
     * finish before the next batch is claimed. Using [supervisorScope] instead of
     * [coroutineScope] ensures that a failure in one UserRun coroutine does not cancel
     * the sibling coroutines processing other UserRuns in the same batch.
     *
     * Runs on [Dispatchers.IO] so the coroutines launched inside [supervisorScope] execute on
     * the IO thread pool (default 64 threads) rather than being serialised on the caller's
     * single thread. This is required because [processUserRun] calls blocking Spring services
     * (Neo4j, CaseService, PermissionService) that would otherwise starve the coroutine dispatcher.
     *
     * Concurrency is bounded by [SchedulerProperties.batchSize]: at most that many UserRuns
     * are claimed per batch. Within a batch, UserRuns are grouped by their parent Run and
     * each group is processed concurrently under a [supervisorScope] so a failure in one
     * group does not cancel the others. Further burst control is delegated to Spring AI's
     * `RetryTemplate` (exponential backoff on 429 responses).
     *
     * ### Delivery guarantee: at-least-once
     *
     * If an instance crashes after creating a Case but before [markTerminal], the
     * UserRun's [ScheduledPromptUserRun.leaseUntil] will expire and a subsequent
     * [claimBatch] will reclaim it, creating a **second** Case for the same user.
     * This is acceptable for the scheduled-prompt use case (duplicate conversation,
     * no data corruption). Exactly-once would require an idempotence key on Case
     * creation (e.g. a UNIQUE constraint on `runId|userId` carried by the Case).
     *
     * ### Completion sweep
     *
     * UserRuns are grouped by their parent Run so the completion check fires once per Run
     * at the end of each group. [SchedulerScanner.recoverOrphanedRunningRuns] covers the
     * crash window between the last [markTerminal] and the completion check.
     */
    suspend fun consumeAvailable() = withContext(Dispatchers.IO) {
        val leaseDuration = Duration.ofMinutes(properties.leaseMinutes)

        // Group by parent Run so the completion check fires once per Run rather than
        // once per UserRun. Each group runs concurrently under supervisorScope.
        var batch: List<ScheduledPromptUserRun>
        do {
            batch = userRunRepository.claimBatch(leaseDuration, properties.batchSize)
            supervisorScope {
                // Group by parent Run so the completion check fires once per Run rather than
                // once per UserRun. Each group runs concurrently; a failure in one group does
                // not cancel the others (supervisorScope semantics).
                batch.groupBy { it.runId }.forEach { (runId, userRuns) ->
                    launch { processUserRunGroup(runId, userRuns) }
                }
            }
        } while (batch.isNotEmpty())
    }

    // -------------------------------------------------------------------------
    // Per-Run processing
    // -------------------------------------------------------------------------

    /**
     * Process all [userRuns] belonging to the same [runId] concurrently, then check
     * completion of the parent Run.
     *
     * Each UserRun is launched under a [supervisorScope] so an individual failure does
     * not cancel its siblings. Exceptions are caught at the launch site: [CancellationException]
     * is re-thrown to preserve cooperative cancellation, all other exceptions mark the
     * UserRun as FAILED. Once all UserRuns have settled, [checkCompletion] transitions
     * the parent Run to DONE or FAILED.
     */
    private suspend fun processUserRunGroup(runId: UUID, userRuns: List<ScheduledPromptUserRun>) {
        supervisorScope {
            userRuns.forEach { userRun ->
                logger.info {
                    "[Executor] Phase B: claimed UserRun=${userRun.id} runId=${userRun.runId} userId=${userRun.userId}"
                }
                launch {
                    try {
                        val context = resolveContext(userRun)
                        val caseId = createAndInjectCase(userRun, context)
                        awaitLaunch(userRun.id, caseId)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logger.error(e) { "[Executor] UserRun=${userRun.id} failed for user=${userRun.userId}" }
                        markFailed(userRun.id, Instant.now(clock), e.message ?: "Unknown error")
                    }
                }
            }
        }
        // All UserRuns for this Run have finished — check completion immediately.
        // The sweep recoverOrphanedRunningRuns covers crashes between markTerminal and this point.
        runCatching { checkCompletion(runId) }.onFailure { e ->
            logger.error(e) { "[Executor] checkCompletion failed for run=$runId" }
        }
    }

    // -------------------------------------------------------------------------
    // Single UserRun processing
    // -------------------------------------------------------------------------

    /**
     * Resolve all data required to execute a [ScheduledPromptUserRun].
     * Throws [IllegalStateException] on any missing entity — propagates to the
     * try/catch in [processUserRunGroup] which marks the UserRun FAILED.
     */
    private fun resolveContext(userRun: ScheduledPromptUserRun): UserRunContext {
        val run = checkNotNull(runRepository.findById(userRun.runId)) {
            "Parent Run ${userRun.runId} not found"
        }
        val scheduledPrompt = scheduledPromptRepository.findByIds(listOf(run.scheduledPromptId))
            .firstOrNull()
            ?: error("ScheduledPrompt ${run.scheduledPromptId} not found")
        val namespaceId = checkNotNull(scheduledPrompt.namespaceId) {
            "Platform-scope ScheduledPrompt cannot be executed per-user"
        }
        val user = checkNotNull(userService.findById(userRun.userId)) {
            "User ${userRun.userId} not found"
        }
        val prompt = checkNotNull(promptService.findById(scheduledPrompt.promptTemplateId)) {
            "PromptTemplate ${scheduledPrompt.promptTemplateId} not found"
        }
        // Mono-line content (enforced by ScheduledPromptService validation).
        // Future: resolve {{placeholders}} here with execution context (user name, date, etc.)
        val promptContent = prompt.content.firstOrNull()?.takeIf { it.isNotBlank() }
            ?: error("PromptTemplate ${scheduledPrompt.promptTemplateId} has empty content")
        // Resolve the agent name — prepended as @mention so selectAgent picks it up
        // via the normal @mention resolution path (no special-casing in the runtime).
        val agentName = checkNotNull(agentConfigService.findById(scheduledPrompt.agentConfigId)?.name) {
            "AgentConfig ${scheduledPrompt.agentConfigId} not found"
        }
        return UserRunContext(
            namespaceId = namespaceId,
            caseTitle = "${scheduledPrompt.name} ${run.scheduledFor}",
            actor = Actor(id = userRun.userId.toString(), displayName = user.displayName(), role = ActorRole.USER),
            // Inject resolved content with @mention — selectAgent resolves the agent,
            // PromptCommandParser sees no /command and passes text through unchanged.
            message = "@$agentName $promptContent",
        )
    }

    /**
     * Create a [Case], grant ADMIN to the target user, and inject the prompt message.
     * Returns the created [Case] id.
     *
     * No idempotence key links the Case to the UserRun — if the instance crashes after
     * this point but before markTerminal(), the lease expires and another instance will
     * create a second Case for the same user (at-least-once). Exactly-once would require
     * a UNIQUE constraint on Case keyed by (runId, userId).
     */
    private fun createAndInjectCase(userRun: ScheduledPromptUserRun, context: UserRunContext): UUID {
        val case = caseService.create(Case(namespaceId = context.namespaceId, title = context.caseTitle))
        permissionService.grantPermission(
            userRun.userId.toString(),
            EntityType.CASE,
            case.id.toString(),
            PermissionRelation.ADMIN,
        )
        caseService.addMessage(
            caseId = case.id,
            actor = context.actor,
            content = listOf(MessageContent.Text(context.message)),
        )
        logger.info {
            "[Executor] UserRun=${userRun.id} — Case ${case.id} created and message injected for user=${userRun.userId}"
        }
        return case.id
    }

    /**
     * Await the Case launch and close the UserRun based on the observed [CaseStatus].
     * If no active runtime is found, the Case already reached a terminal status.
     */
    private suspend fun awaitLaunch(userRunId: UUID, caseId: UUID) {
        val runtime = caseService.findActiveRuntime(caseId)
        if (runtime == null) {
            closeUserRun(userRunId, caseService.findById(caseId)?.status, caseId)
        } else {
            monitorLaunch(userRunId, caseId, runtime)
        }
    }

    /** Resolved execution context for a single [ScheduledPromptUserRun]. */
    private data class UserRunContext(
        val namespaceId: UUID,
        val caseTitle: String,
        val actor: Actor,
        val message: String,
    )

    // -------------------------------------------------------------------------
    // Case completion
    // -------------------------------------------------------------------------

    /**
     * Await the Case launch and detect immediate failures.
     *
     * Collects [CaseRuntime.statusFlow] for up to [SchedulerProperties.launchTimeoutSeconds]
     * seconds. This is a **health check**, not a completion wait:
     * - IDLE → agent finished its turn quickly → DONE
     * - Timeout (still RUNNING) → Case is healthy but slow; monitoring released → TIMEOUT
     * - ERROR/KILLED → immediate crash → FAILED
     *
     * On timeout the UserRun is closed as [UserRunStatus.TIMEOUT] via a direct
     * [ScheduledPromptUserRunRepository.markTerminal] call, bypassing [closeUserRun] and
     * [toUserRunOutcome] (which only map [CaseStatus] values).
     *
     * The Case continues running independently after this method returns.
     * The lease on the UserRun is only relevant for crash recovery (instance down
     * between Case creation and this point).
     *
     * ### StateFlow current-value guard
     *
     * [CaseRuntime.statusFlow] is a [kotlinx.coroutines.flow.StateFlow]. Calling `.first { predicate }`
     * on a StateFlow always evaluates the **current value** first: if it satisfies the predicate,
     * it is returned immediately without suspending.
     *
     * The status lifecycle in [CaseRuntime.run] is: `PENDING → RUNNING → IDLE/ERROR/KILLED`.
     * `statusFlow` is initialised to `PENDING` at construction. The only status that can cause
     * a false early return is **IDLE**: if a very fast turn completes before `monitorLaunch` is
     * called, `statusFlow.value` is already `IDLE`, and `.first { it == IDLE || it.isTerminal() }`
     * returns it immediately — which is actually the correct behaviour (the turn did finish).
     *
     * The guard below makes the intent explicit and handles the `IDLE` case without relying on
     * the implicit StateFlow current-value semantics:
     * - Already IDLE or terminal → the turn finished before we arrived; act on it directly.
     * - PENDING or RUNNING → neither satisfies the predicate, so `.first { … }` suspends and
     *   waits for the next emission, bounded by the timeout.
     *
     * Contract: [CaseRuntime.statusFlow] is initialised to [CaseStatus.PENDING] at construction
     * and transitions PENDING → RUNNING at the top of [CaseRuntime.run]. It never starts at IDLE.
     * This invariant is asserted by `CaseRuntimeSpec: "statusFlow reflects RUNNING during run()
     * and IDLE after normal completion"`. If that test is changed, revisit this guard.
     */
    private suspend fun monitorLaunch(
        userRunId: UUID,
        caseId: UUID,
        runtime: CaseRuntime,
    ) {
        val timeoutMs = Duration.ofSeconds(properties.launchTimeoutSeconds).toMillis()
        val currentStatus = runtime.statusFlow.value

        val observedStatus = when {
            currentStatus == CaseStatus.IDLE || currentStatus.isTerminal() -> {
                // The turn already completed before we started monitoring — act on it directly.
                currentStatus
            }
            else -> {
                // Status is PENDING or RUNNING — neither satisfies the predicate.
                // .first {} evaluates the current value first (StateFlow semantics), finds it
                // unsatisfying, then suspends until the next emission that does satisfy it.
                // withTimeoutOrNull returns null if no satisfying emission arrives in time.
                withTimeoutOrNull(timeoutMs) {
                    runtime.statusFlow.first { it == CaseStatus.IDLE || it.isTerminal() }
                }
            }
        }

        when {
            observedStatus != null ->
                // Either finished quickly (IDLE) or crashed immediately (ERROR/KILLED).
                closeUserRun(userRunId, observedStatus, caseId)
            else -> {
                // Timeout — Case is still RUNNING; monitoring released, Case continues independently.
                userRunRepository.markTerminal(userRunId, UserRunStatus.TIMEOUT, Instant.now(clock))
                logger.info {
                    "[Executor] UserRun=$userRunId timed out (caseId=$caseId still running) — monitoring released"
                }
            }
        }
    }

    /**
     * Close a UserRun based on the final [CaseStatus].
     *
     * Maps [CaseStatus] to [UserRunStatus] via [toUserRunOutcome]:
     * - IDLE or any non-error terminal → DONE
     * - KILLED, ERROR → FAILED with diagnostic message
     * - null (Case not found) → DONE (defensive, Case was likely cleaned up)
     */
    private fun closeUserRun(userRunId: UUID, caseStatus: CaseStatus?, caseId: UUID) {
        val now = Instant.now(clock)
        val (userRunStatus, error) = caseStatus.toUserRunOutcome()
        userRunRepository.markTerminal(userRunId, userRunStatus, now, error)
        logger.info {
            "[Executor] UserRun=$userRunId closed as $userRunStatus (caseId=$caseId status=$caseStatus)"
        }
    }

    // -------------------------------------------------------------------------
    // Completion check
    // -------------------------------------------------------------------------

    /**
     * Transition the parent [ScheduledPromptRun] to DONE (or FAILED) once all
     * UserRuns are settled.
     *
     * Fast-path: [ScheduledPromptUserRunRepository.hasAnyActive] exits immediately
     * (EXISTS query, stops at first match) when work is still in flight.
     * Only when no active UserRuns remain does it check for failures.
     *
     * Only inspects RUNNING Runs — a Run only reaches RUNNING after [materialize]
     * completes, so the CLAIMED→RUNNING transition acts as the completion guard
     * (a CLAIMED Run whose [materialize] has not yet finished is never prematurely
     * evaluated). Orphaned CLAIMED Runs (crash before [materialize]) are swept to
     * FAILED by [SchedulerScanner.recoverOrphanedClaimedRuns] and never reach this path.
     * The Run is FAILED when at least one UserRun failed; DONE otherwise.
     *
     * ### Crash window
     *
     * If the instance crashes after the last [ScheduledPromptUserRunRepository.markTerminal]
     * but before this method runs, the Run stays RUNNING forever — no subsequent UserRun
     * closure will re-trigger this check. [SchedulerScanner.recoverOrphanedRunningRuns]
     * handles this by re-evaluating the completion condition on every tick.
     */
    private fun checkCompletion(runId: UUID) {
        val run = runRepository.findById(runId) ?: return
        if (run.status != RunStatus.RUNNING) return

        if (userRunRepository.hasAnyActive(runId)) return

        val finalStatus = if (userRunRepository.hasAnyFailed(runId)) RunStatus.FAILED else RunStatus.DONE
        runRepository.updateStatus(runId, finalStatus, Instant.now(clock))
        logger.info { "[Executor] Run=$runId → $finalStatus" }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun markFailed(
        userRunId: UUID,
        now: Instant,
        error: String,
    ) {
        runCatching {
            userRunRepository.markTerminal(userRunId, UserRunStatus.FAILED, now, error)
        }.onFailure { e ->
            logger.error(e) { "[Executor] Could not mark UserRun=$userRunId as FAILED" }
        }
    }

    companion object : KLogging() {
        /**
         * Maps a [CaseStatus] (possibly null when the Case was already cleaned up)
         * to the corresponding [UserRunStatus] and optional error message.
         */
        private fun CaseStatus?.toUserRunOutcome(): Pair<UserRunStatus, String?> = when (this) {
            CaseStatus.KILLED, CaseStatus.ERROR ->
                UserRunStatus.FAILED to "Case reached terminal status $this"
            else ->
                UserRunStatus.DONE to null
        }
    }
}
