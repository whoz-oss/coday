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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
 * 5. Collect the runtime's [CaseRuntime.statusFlow] until the Case reaches IDLE or a
 *    terminal status, then close the UserRun.
 *
 * A [Semaphore] caps concurrent in-flight coroutines to
 * [SchedulerProperties.maxConcurrentExecutions]. Unlike `java.util.concurrent.Semaphore`,
 * [Semaphore.acquire] **suspends** instead of blocking an OS thread.
 * A [delay] of [SchedulerProperties.staggerDelayMs] ms separates each launch.
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
@ConditionalOnProperty(name = ["scheduler.enabled"], havingValue = "true")
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
     * Delegates to a single Cypher INSERT-SELECT that traverses the deployment graph
     * (AgentConfig -[:DEPLOYED_TO]-> UserGroup <-[:MEMBER|ADMIN]- User) and MERGEs one
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
     * A crash between the insert and this call leaves an orphaned CLAIMED Run, which
     * [SchedulerScanner.recoverOrphanedClaimedRuns] detects and marks FAILED on the next tick.
     */
    @Transactional
    fun materialize(run: ScheduledPromptRun, scheduledPrompt: ScheduledPrompt) {
        logger.info {
            "[Executor] Phase A: materialising run=${run.id} sp=${scheduledPrompt.id}"
        }

        val namespaceId = scheduledPrompt.namespaceId
        val count = when {
            namespaceId == null -> {
                logger.debug {
                    "[Executor] Platform-scope sp=${scheduledPrompt.id} — no users to materialise"
                }
                0
            }
            else -> runCatching {
                userRunRepository.materialize(run.id, scheduledPrompt.agentConfigId, namespaceId)
            }.onFailure { e ->
                logger.error(e) {
                    "[Executor] materialize failed for run=${run.id} sp=${scheduledPrompt.id}"
                }
            }.getOrDefault(0)
        }

        // Transition based on whether any UserRuns were created:
        // - RUNNING when count > 0 — Phase B will consume the UserRuns and checkCompletion
        //   will close the Run once all are terminal.
        // - DONE when count == 0 — no UserRuns to consume (platform-scope ScheduledPrompt
        //   with no target users, or namespace with no deployed users). Transitioning to
        //   RUNNING would leave the Run stuck forever since checkCompletion requires at
        //   least one UserRun closure to trigger.
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
     * caller ([SchedulerScanner.tickConsume] via `runBlocking`) is truly blocked for
     * the full duration. Spring `fixedDelay` on [SchedulerScanner.tickConsume] then
     * prevents a new consume tick from starting before this one completes.
     *
     * Each batch is claimed via [ScheduledPromptUserRunRepository.claimBatch], which
     * uses SDN `@Version` optimistic locking so concurrent instances cannot double-claim
     * the same UserRun. Within a batch, each UserRun is launched as a structured
     * coroutine inside [coroutineScope]; the scope suspends until all launched children
     * finish before the next batch is claimed.
     *
     * A [Semaphore] caps concurrent in-flight coroutines to
     * [SchedulerProperties.maxConcurrentExecutions]. [Semaphore.withPermit] acquires
     * before the block and releases in a finally — exception-safe by construction.
     *
     * ### Delivery guarantee: at-least-once
     *
     * If an instance crashes after creating a Case but before [markTerminal], the
     * UserRun's [ScheduledPromptUserRun.leaseUntil] will expire and a subsequent
     * [claimBatch] will reclaim it, creating a **second** Case for the same user.
     * This is acceptable for the scheduled-prompt use case (duplicate conversation,
     * no data corruption). Exactly-once would require an idempotence key on Case
     * creation (e.g. a UNIQUE constraint on `runId|userId` carried by the Case).
     */
    suspend fun consumeAvailable() {
        val semaphore = Semaphore(properties.maxConcurrentExecutions)
        val leaseDuration = Duration.ofMinutes(properties.leaseMinutes)

        var batch: List<ScheduledPromptUserRun>
        do {
            batch = userRunRepository.claimBatch(leaseDuration, properties.maxConcurrentExecutions)
            val runIds = batch.map { it.runId }.toSet()

            coroutineScope {
                for (userRun in batch) {
                    logger.info {
                        "[Executor] Phase B: claimed UserRun=${userRun.id} runId=${userRun.runId} userId=${userRun.userId}"
                    }

                    launch {
                        semaphore.withPermit {
                            executeUserRun(userRun)
                        }
                    }

                    delay(properties.staggerDelayMs)
                }
            }

            // All UserRuns in this batch have finished — check completion once per Run.
            // The sweep recoverOrphanedRunningRuns covers crashes between markTerminal
            // and this point.
            runIds.forEach { checkCompletion(it) }
        } while (batch.isNotEmpty())
    }

    // -------------------------------------------------------------------------
    // Single UserRun execution
    // -------------------------------------------------------------------------

    private suspend fun executeUserRun(userRun: ScheduledPromptUserRun) {
        val now = Instant.now(clock)
        val runId = userRun.runId
        val userId = userRun.userId

        // --- Resolve context ---

        val run = runRepository.findById(runId)
        if (run == null) {
            logger.warn { "[Executor] Run $runId not found for UserRun=${userRun.id} — marking FAILED" }
            markFailed(userRun.id, now, "Parent Run $runId not found")
            return
        }

        val scheduledPrompt = scheduledPromptRepository.findByIds(listOf(run.scheduledPromptId))
            .firstOrNull()
        if (scheduledPrompt == null) {
            logger.warn { "[Executor] ScheduledPrompt ${run.scheduledPromptId} not found — marking UserRun=${userRun.id} FAILED" }
            markFailed(userRun.id, now, "ScheduledPrompt ${run.scheduledPromptId} not found")
            return
        }

        val namespaceId = scheduledPrompt.namespaceId
        if (namespaceId == null) {
            logger.warn { "[Executor] Platform-scope ScheduledPrompt ${scheduledPrompt.id} — skipping UserRun=${userRun.id}" }
            markFailed(userRun.id, now, "Platform-scope ScheduledPrompt cannot be executed per-user")
            return
        }

        val user = userService.findById(userId)
        if (user == null) {
            logger.warn { "[Executor] User $userId not found — marking UserRun=${userRun.id} FAILED" }
            markFailed(userRun.id, now, "User $userId not found")
            return
        }

        // Resolve the prompt content directly — the Executor is the consumer of the prompt
        // and has the full context (userId, namespaceId, scheduling metadata) needed to
        // resolve it. Injecting a /slash-command would fail because addMessage's
        // PromptCommandParser requires text to start with '/' but the @mention prefix
        // prevents that.
        val prompt = promptService.findById(scheduledPrompt.promptTemplateId)
        if (prompt == null) {
            logger.warn {
                "[Executor] PromptTemplate ${scheduledPrompt.promptTemplateId} not found " +
                    "— marking UserRun=${userRun.id} FAILED"
            }
            markFailed(userRun.id, now, "PromptTemplate ${scheduledPrompt.promptTemplateId} not found")
            return
        }

        // Mono-line content (enforced by ScheduledPromptService validation).
        // Future: resolve {{placeholders}} here with execution context (user name, date, etc.)
        val promptContent = prompt.content.firstOrNull()
        if (promptContent.isNullOrBlank()) {
            logger.warn {
                "[Executor] PromptTemplate ${scheduledPrompt.promptTemplateId} has empty content " +
                    "— marking UserRun=${userRun.id} FAILED"
            }
            markFailed(userRun.id, now, "PromptTemplate ${scheduledPrompt.promptTemplateId} has empty content")
            return
        }

        // Resolve the agent name — prepended as @mention so selectAgent picks it up
        // via the normal @mention resolution path (no special-casing in the runtime).
        val agentName = agentConfigService.findById(scheduledPrompt.agentConfigId)?.name
        if (agentName == null) {
            logger.warn {
                "[Executor] AgentConfig ${scheduledPrompt.agentConfigId} not found " +
                    "— marking UserRun=${userRun.id} FAILED"
            }
            markFailed(userRun.id, now, "AgentConfig ${scheduledPrompt.agentConfigId} not found")
            return
        }

        // Inject resolved content with @mention — selectAgent resolves the agent,
        // PromptCommandParser sees no /command and passes text through unchanged.
        val message = "@$agentName $promptContent"

        // --- Execute ---

        try {
            // Step 1: Create the Case.
            // No idempotence key links this Case to the UserRun — if the instance crashes
            // after this point but before markTerminal(), the lease expires and another
            // instance will create a second Case for the same user (at-least-once).
            // Exactly-once would require a UNIQUE constraint on Case keyed by userRunKey.
            val case = caseService.create(
                Case(
                    namespaceId = namespaceId,
                    title = scheduledPrompt.name,
                ),
            )
            val caseId = case.id

            // Step 2: Grant ADMIN to the target user.
            permissionService.grantPermission(
                userId.toString(),
                EntityType.CASE,
                caseId.toString(),
                PermissionRelation.ADMIN,
            )

            // Step 3: Build Actor.
            val actor = Actor(
                id = userId.toString(),
                displayName = user.displayName(),
                role = ActorRole.USER,
            )

            // Step 4: Inject the prompt message.
            // addMessage internally launches runtime.run() in CaseServiceImpl.scope.
            caseService.addMessage(
                caseId = caseId,
                actor = actor,
                content = listOf(MessageContent.Text(message)),
            )

            logger.info {
                "[Executor] UserRun=${userRun.id} — Case $caseId created and message injected for user=$userId"
            }

            // Step 5: Collect the runtime's statusFlow until IDLE or terminal.
            // The runtime is guaranteed to exist in activeRuntimes right after create().
            val runtime = caseService.findActiveRuntime(caseId)
            if (runtime == null) {
                // Runtime already evicted (terminal status reached before we got here)
                closeUserRun(userRun.id, caseService.findById(caseId)?.status, caseId)
            } else {
                awaitCaseCompletion(userRun.id, caseId, runtime)
            }
        } catch (e: Exception) {
            logger.error(e) {
                "[Executor] UserRun=${userRun.id} failed during Case creation/launch for user=$userId"
            }
            markFailed(userRun.id, now, e.message ?: "Unknown error")
        }
    }

    // -------------------------------------------------------------------------
    // Case completion
    // -------------------------------------------------------------------------

    /**
     * Collect the runtime's [CaseRuntime.statusFlow] until the Case reaches IDLE or a terminal status.
     *
     * [kotlinx.coroutines.flow.StateFlow.first] suspends reactively until the predicate
     * matches — zero polling, zero delay. The lease duration is enforced via
     * [withTimeoutOrNull]; on timeout the UserRun is marked FAILED and the next tick's
     * [claimBatch] will reclaim expired RUNNING UserRuns.
     */
    private suspend fun awaitCaseCompletion(
        userRunId: UUID,
        caseId: UUID,
        runtime: CaseRuntime,
    ) {
        val leaseMs = Duration.ofMinutes(properties.leaseMinutes).toMillis()

        val finalStatus = withTimeoutOrNull(leaseMs) {
            runtime.statusFlow.first { it == CaseStatus.IDLE || it.isTerminal() }
        }

        if (finalStatus == null) {
            logger.warn {
                "[Executor] UserRun=$userRunId lease expired while waiting for Case $caseId — marking FAILED"
            }
            markFailed(userRunId, Instant.now(clock), "Lease expired waiting for Case $caseId")
        } else {
            closeUserRun(userRunId, finalStatus, caseId)
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
        if (run.status != RunStatus.RUNNING && run.status != RunStatus.CLAIMED) return

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
