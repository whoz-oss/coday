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
import io.whozoss.agentos.sdk.scheduledPrompt.UserContextProvider
import io.whozoss.agentos.user.UserService
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
 * ### Phase A — Materialisation (fast, called by [SchedulerScanner])
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
 * ### Phase B — Continuous consumption (producer + channel + worker pool)
 *
 * This bean implements [SmartLifecycle]. On [start], a [CoroutineScope] is created and two
 * structured children are launched inside it:
 *
 * **Producer** (`Dispatchers.IO`): loops continuously, calling
 * [ScheduledPromptUserRunRepository.claimBatch] and sending each claimed [ScheduledPromptUserRun]
 * into the channel. When the batch is empty the producer delays [SchedulerProperties.emptyPollDelayMs]
 * before polling again, avoiding a busy-loop. On database errors it applies exponential
 * backoff (capped at 60 s). The producer respects [consumePaused]: when paused it delays
 * 2 s per iteration without touching the database.
 * The channel is closed in the producer's `finally` block, guaranteeing that workers
 * exit their `for (userRun in channel)` loop cleanly regardless of how the producer stops.
 *
 * **Worker pool** ([SchedulerProperties.workerCount] coroutines, `Dispatchers.IO`): each worker
 * receives [ScheduledPromptUserRun]s from the channel and calls [processUserRun] followed by
 * [checkCompletion]. A failure in one worker does not affect its siblings — exceptions are
 * caught per-item; [CancellationException] is always re-thrown to honour cooperative cancellation.
 *
 * **Channel capacity**: [SchedulerProperties.channelCapacity] (default 50 = 2 x batchSize).
 * Double-buffering: the producer can fill a second batch into the channel while workers drain
 * the first, keeping all workers continuously fed without pre-claiming an excessive number of
 * leased UserRuns. The channel capacity should be at least [SchedulerProperties.batchSize]
 * so the producer never suspends mid-batch.
 *
 * **Shutdown**: [stop] cancels the scope. The [CancellationException] propagates
 * immediately to all coroutines at their next suspension point — no graceful drain.
 * The producer's `finally` block closes the channel, unblocking workers suspended on receive.
 * UserRuns that were in-flight remain RUNNING; their leases expire and another instance
 * (or the restarted instance) reclaims them via [claimBatch] (at-least-once delivery).
 *
 * ### Per-UserRun processing
 *
 * 1. Create a [Case] in the prompt’s namespace.
 * 2. Grant ADMIN on the Case to the target user via [PermissionService.grantPermission].
 * 3. Build an [Actor] with `role = USER`.
 * 4. Resolve the prompt content directly and inject `"@agentName <content>"` via
 *    [CaseService.addMessage]. The Executor resolves content itself rather than using
 *    a `/slash-command` because PromptCommandParser requires the text to start with `/`,
 *    which is incompatible with the leading `@mention`.
 * 5. Await the Case launch: inspect the runtime’s [CaseRuntime.statusFlow] current value,
 *    then observe future transitions for up to [SchedulerProperties.launchTimeoutSeconds]
 *    seconds to detect immediate failures. If the Case is IDLE (turn finished) it is closed
 *    as DONE. If still RUNNING after the timeout, monitoring is released (Case continues
 *    independently) and the UserRun is closed as TIMEOUT. An immediate terminal status
 *    (ERROR/KILLED) marks the UserRun as FAILED.
 *
 * ### Run (parent) completion
 *
 * Workers do **not** update the parent [ScheduledPromptRun] status. That responsibility
 * belongs entirely to [SchedulerScanner.recoverOrphanedRunningRuns], which runs on every
 * [SchedulerScanner.tickClaim] (approximately every 60 s). It finds all RUNNING Runs whose
 * UserRuns are all terminal and closes them as DONE or FAILED. This avoids N concurrent
 * completion checks from parallel workers racing on the same `runId`.
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
    private val userContextProvider: UserContextProvider? = null,
) {

    /** When true, the producer skips claimBatch and delays instead. */
    private val consumePaused = AtomicBoolean(false)

    fun isConsumePaused(): Boolean = consumePaused.get()

    fun pauseConsume() {
        consumePaused.set(true)
        logger.warn { "[Executor] consume PAUSED by operator" }
    }

    fun resumeConsume() {
        consumePaused.set(false)
        logger.warn { "[Executor] consume RESUMED by operator" }
    }

    // No synchronisation needed: start() writes scope then immediately launches the coroutine
    // that reads it — the launch itself establishes a happens-before edge so runConsumerLoop()
    // always sees the written value. stop() is called by Spring @PreDestroy, sequentially after
    // start() has returned, never concurrently with it. If this invariant ever changes (e.g. an
    // admin endpoint calling start/stop concurrently), replace with AtomicReference + compareAndSet.
    private var scope: CoroutineScope? = null

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    @PostConstruct
    fun start() {
        val newScope = CoroutineScope(SupervisorJob())
        scope = newScope
        newScope.launch(Dispatchers.IO) { runConsumerLoop() }
        logger.info { "[Executor] consumer loop started (workers=${properties.workerCount}, batchSize=${properties.batchSize}, channelCapacity=${properties.channelCapacity})" }
    }

    @PreDestroy
    fun stop() {
        scope?.cancel()
        scope = null
        logger.info { "[Executor] consumer loop stopped" }
    }

    // -------------------------------------------------------------------------
    // Consumer loop (producer + channel + worker pool)
    // -------------------------------------------------------------------------

    /**
     * Runs the producer and worker pool inside the bean’s [CoroutineScope].
     *
     * The channel is closed in the producer's `finally` block, guaranteeing that workers
     * iterating `for (userRun in channel)` exit their loop cleanly whether the producer
     * stops normally, is cancelled, or throws an unexpected exception.
     *
     * The producer and all workers share the same scope. Cancelling the scope (on [stop])
     * propagates [CancellationException] to all of them at their next suspension point.
     * The producer's `finally` block then closes the channel, which unblocks any worker
     * suspended on `channel.receive()`.
     */
    private suspend fun runConsumerLoop() {
        val channel = Channel<ScheduledPromptUserRun>(capacity = properties.channelCapacity)
        val currentScope = checkNotNull(scope)

        // Producer
        currentScope.launch(Dispatchers.IO) {
            val leaseDuration = Duration.ofMinutes(properties.leaseMinutes)
            var consecutiveErrors = 0
            try {
                while (isActive) {
                    when {
                        consumePaused.get() -> delay(properties.pausedPollDelayMs)
                        else -> {
                            try {
                                val batch = userRunRepository.claimBatch(leaseDuration, properties.batchSize)
                                when {
                                    batch.isEmpty() -> delay(properties.emptyPollDelayMs)
                                    else -> batch.forEach { channel.send(it) }
                                }
                                consecutiveErrors = 0
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                consecutiveErrors++
                                val backoffMs = exponentialBackoffMs(consecutiveErrors)
                                logger.error(e) {
                                    "[Executor] producer error (attempt=$consecutiveErrors), backing off ${backoffMs}ms"
                                }
                                delay(backoffMs)
                            }
                        }
                    }
                }
            } finally {
                channel.close()
            }
        }

        // Worker pool — Run (parent) completion is NOT checked here.
        // SchedulerScanner.recoverOrphanedRunningRuns handles RUNNING → DONE/FAILED on each tickClaim.
        repeat(properties.workerCount) { workerId ->
            currentScope.launch(Dispatchers.IO) {
                for (userRun in channel) {
                    try {
                        logger.info {
                            "[Executor] worker=$workerId processing UserRun=${userRun.id} runId=${userRun.runId} userId=${userRun.userId}"
                        }
                        processUserRun(userRun)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logger.error(e) {
                            "[Executor] worker=$workerId failed on UserRun=${userRun.id} — lease expires, will be reclaimed"
                        }
                    }
                }
            }
        }
    }

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
    // Single UserRun processing
    // -------------------------------------------------------------------------

    /**
     * Execute a single [ScheduledPromptUserRun]: resolve context, create a Case,
     * inject the prompt message, and await the launch outcome.
     *
     * Throws on any unrecoverable error (missing entity, Case creation failure, etc.).
     * The worker catches all non-[CancellationException] exceptions and logs them;
     * the UserRun stays RUNNING until its lease expires and is reclaimed.
     *
     * On a [CancellationException] (scope cancelled) the exception is re-thrown so the
     * worker exits its channel loop and the coroutine terminates cooperatively.
     *
     * Exposed as `internal` so unit tests can invoke it directly without starting the
     * full [SmartLifecycle] loop.
     */
    internal suspend fun processUserRun(userRun: ScheduledPromptUserRun) {
        try {
            val context = resolveContext(userRun)
            val caseId = createAndInjectCase(userRun, context)
            awaitLaunch(userRun.id, caseId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error(e) { "[Executor] UserRun=${userRun.id} failed for user=${userRun.userId} — marking FAILED" }
            markFailed(userRun.id, Instant.now(clock), e.message ?: "Unknown error")
        }
    }

    /**
     * Resolve all data required to execute a [ScheduledPromptUserRun].
     * Throws [IllegalStateException] on any missing entity — propagates to the
     * try/catch in [processUserRun] which marks the UserRun FAILED.
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
        val promptContent = prompt.content.firstOrNull()?.takeIf { it.isNotBlank() }
            ?: error("PromptTemplate ${scheduledPrompt.promptTemplateId} has empty content")
        val agentName = checkNotNull(agentConfigService.findById(scheduledPrompt.agentConfigId)?.name) {
            "AgentConfig ${scheduledPrompt.agentConfigId} not found"
        }
        val sessionContext = runCatching {
            userContextProvider?.provideUserContext(
                userExternalId = user.externalId,
                namespaceId = namespaceId,
            )
        }.onFailure { e ->
            logger.warn(e) {
                "[Executor] Context enrichment failed for UserRun=${userRun.id} userId=${userRun.userId} — continuing without sessionContext"
            }
        }.getOrNull()
        return UserRunContext(
            namespaceId = namespaceId,
            caseTitle = "${scheduledPrompt.name} ${run.scheduledFor}",
            actor = Actor(id = userRun.userId.toString(), displayName = user.displayName(), role = ActorRole.USER),
            message = "@$agentName $promptContent",
            scheduledPromptId = scheduledPrompt.id,
            sessionContext = sessionContext,
        )
    }

    /**
     * Create a [Case], grant ADMIN to the target user, and inject the prompt message.
     * Returns the created [Case] id.
     */
    private fun createAndInjectCase(userRun: ScheduledPromptUserRun, context: UserRunContext): UUID {
        val case = caseService.create(
            Case(
                namespaceId = context.namespaceId,
                title = context.caseTitle,
                scheduledPromptId = context.scheduledPromptId,
            ),
        )
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
            sessionContext = context.sessionContext,
        )
        logger.info {
            "[Executor] UserRun=${userRun.id} — Case ${case.id} created and message injected for user=${userRun.userId}"
        }
        return case.id
    }

    /** Resolved execution context for a single [ScheduledPromptUserRun]. */
    private data class UserRunContext(
        val namespaceId: UUID,
        val caseTitle: String,
        val actor: Actor,
        val message: String,
        val scheduledPromptId: UUID,
        val sessionContext: Map<String, Any?>? = null,
    )

    // -------------------------------------------------------------------------
    // Case completion
    // -------------------------------------------------------------------------

    /**
     * Await the Case launch and close the UserRun based on the observed [CaseStatus].
     * If no active runtime is found, the Case already reached a terminal status.
     */
    private suspend fun awaitLaunch(userRunId: UUID, caseId: UUID) {
        val runtime = caseService.findActiveRuntime(caseId)
        when {
            runtime == null -> closeUserRun(userRunId, caseService.findById(caseId)?.status, caseId)
            else -> monitorLaunch(userRunId, caseId, runtime)
        }
    }

    /**
     * Await the Case launch and detect immediate failures.
     *
     * Collects [CaseRuntime.statusFlow] for up to [SchedulerProperties.launchTimeoutSeconds]
     * seconds. This is a **health check**, not a completion wait:
     * - IDLE → agent finished its turn quickly → DONE
     * - Timeout (still RUNNING) → Case is healthy but slow; monitoring released → TIMEOUT
     * - ERROR/KILLED → immediate crash → FAILED
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
     */
    private suspend fun monitorLaunch(
        userRunId: UUID,
        caseId: UUID,
        runtime: CaseRuntime,
    ) {
        val timeoutMs = Duration.ofSeconds(properties.launchTimeoutSeconds).toMillis()
        val currentStatus = runtime.statusFlow.value

        val observedStatus = when {
            currentStatus == CaseStatus.IDLE || currentStatus.isTerminal() -> currentStatus
            else -> withTimeoutOrNull(timeoutMs) {
                runtime.statusFlow.first { it == CaseStatus.IDLE || it.isTerminal() }
            }
        }

        when {
            observedStatus != null -> closeUserRun(userRunId, observedStatus, caseId)
            else -> {
                userRunRepository.markTerminal(userRunId, UserRunStatus.TIMEOUT, Instant.now(clock))
                logger.info {
                    "[Executor] UserRun=$userRunId timed out (caseId=$caseId still running) — monitoring released"
                }
            }
        }
    }

    /**
     * Close a UserRun based on the final [CaseStatus].
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
    // Helpers
    // -------------------------------------------------------------------------

    private fun markFailed(userRunId: UUID, now: Instant, error: String) {
        runCatching {
            userRunRepository.markTerminal(userRunId, UserRunStatus.FAILED, now, error)
        }.onFailure { e ->
            logger.error(e) { "[Executor] Could not mark UserRun=$userRunId as FAILED" }
        }
    }

    companion object : KLogging() {
        private fun CaseStatus?.toUserRunOutcome(): Pair<UserRunStatus, String?> = when (this) {
            CaseStatus.KILLED, CaseStatus.ERROR ->
                UserRunStatus.FAILED to "Case reached terminal status $this"
            else ->
                UserRunStatus.DONE to null
        }
    }
}
