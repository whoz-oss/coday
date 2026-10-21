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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
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
 * Executes [ScheduledPromptRun]s via the converged path.
 *
 * ### Converged path lifecycle: CLAIMED → TARGETING → DONE | FAILED
 *
 * [materialize] is called by [SchedulerScanner.claim] after the Run has been inserted.
 * In a single `@Transactional` block it:
 * 1. Resolves the effective loop config (explicit or synthesized).
 * 2. Creates ONE [Case] with `scheduledPromptId` set.
 * 3. Grants the creator ADMIN on that case.
 * 4. Injects `"@<loopAgentName> <configJson>"` as the first message.
 * 5. Transitions the Run from CLAIMED to TARGETING.
 *
 * Monitoring happens AFTER the transaction commits, launched on a plain
 * [CoroutineScope] created at construction. `@Transactional` does not span
 * coroutine suspension, so the transactional setup is a separate synchronous function.
 *
 * ### Monitoring
 *
 * Reuses the [monitorLaunch] pattern, rewired from UserRun to Run:
 * - IDLE → DONE (loop finished its turn)
 * - ERROR / KILLED → FAILED (with status in the error field)
 * - timeout → stays TARGETING (the orphan sweep decides later; mapping a timeout to DONE
 *   would be a false success — the exact failure mode TARGETING exists to prevent)
 *
 * ### Legacy Phase B
 *
 * Phase B (UserRun poller + worker pool) has been removed. [ScheduledPromptUserRun] nodes,
 * repositories, and the schema initializer are retained as historical records — no rows
 * are written on the converged path.
 */
@Component
@ConditionalOnProperty(name = ["agentos.prompt.scheduler.enabled"], havingValue = "true")
class ScheduledPromptExecutor(
    private val scheduledPromptRepository: ScheduledPromptRepository,
    private val runRepository: ScheduledPromptRunRepository,
    @Suppress("UnusedPrivateMember")
    private val userRunRepository: ScheduledPromptUserRunRepository, // retained for DI wiring; no writes on converged path
    private val userService: UserService,
    private val promptService: PromptService,
    private val agentConfigService: AgentConfigService,
    private val permissionService: PermissionService,
    private val caseService: CaseService,
    private val synthesizer: ScheduledPromptLoopConfigSynthesizer,
    private val properties: SchedulerProperties,
    private val clock: Clock,
) {
    /**
     * Coroutine scope for launching post-transaction monitoring.
     * Plain SupervisorJob + IO dispatcher — no lifecycle management needed
     * (no persistent loop to keep alive or restart).
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // -------------------------------------------------------------------------
    // Converged materialise
    // -------------------------------------------------------------------------

    /**
     * Materialise the converged path for [run].
     *
     * The transactional part runs synchronously and commits before monitoring starts.
     * Monitoring is launched as a background coroutine on [scope].
     *
     * @param run the CLAIMED run to materialise
     * @param scheduledPrompt the parent scheduled prompt
     * @param creatorId UUID of the validated creator (resolved in [SchedulerScanner.claim])
     */
    fun materialize(
        run: ScheduledPromptRun,
        scheduledPrompt: ScheduledPrompt,
        creatorId: UUID,
    ) {
        logger.info { "[Executor] Materialising run=${run.id} sp=${scheduledPrompt.id}" }

        val caseId = materializeTransactional(run, scheduledPrompt, creatorId)

        // Monitor AFTER the transaction commits — @Transactional does not span suspension.
        val runtime = caseService.findActiveRuntime(caseId)
        if (runtime != null) {
            scope.launch {
                monitorLaunch(run.id, caseId, runtime)
            }
        } else {
            // Case already reached a terminal status (very fast loop agent).
            val caseStatus = caseService.findById(caseId)?.status
            closeRun(run.id, caseStatus, caseId)
        }
    }

    /**
     * Transactional setup: resolve loop config, create case, grant permission, inject message,
     * transition run to TARGETING.
     *
     * Must NOT be a suspend function — `@Transactional` does not span coroutine suspension.
     */
    @Transactional
    fun materializeTransactional(
        run: ScheduledPromptRun,
        scheduledPrompt: ScheduledPrompt,
        creatorId: UUID,
    ): UUID {
        // Resolve prompt content and agent name for synthesis.
        val prompt = checkNotNull(promptService.findById(scheduledPrompt.promptTemplateId)) {
            "PromptTemplate ${scheduledPrompt.promptTemplateId} not found for run=${run.id}"
        }
        val promptContent = prompt.content.firstOrNull()?.takeIf { it.isNotBlank() }
            ?: error("PromptTemplate ${scheduledPrompt.promptTemplateId} has empty content")
        val agentName = checkNotNull(agentConfigService.findById(scheduledPrompt.agentConfigId)?.name) {
            "AgentConfig ${scheduledPrompt.agentConfigId} not found for run=${run.id}"
        }

        // Branch-free: explicit loopConfig wins, synthesis otherwise.
        val effectiveLoopConfig = scheduledPrompt.loopConfig
            ?: synthesizer.synthesize(
                scheduledPrompt = scheduledPrompt,
                agentName = agentName,
                promptContent = promptContent,
                title = scheduledPrompt.name,
                sourceLanguage = prompt.sourceLanguage,
            )

        // Create one Case for the loop agent.
        val case = caseService.create(
            Case(
                namespaceId = checkNotNull(scheduledPrompt.namespaceId) {
                    "Platform-scope ScheduledPrompt ${scheduledPrompt.id} cannot use the converged path"
                },
                title = scheduledPrompt.name,
                scheduledPromptId = scheduledPrompt.id,
            ),
        )

        // Grant the creator ADMIN on the case.
        permissionService.grantPermission(
            creatorId.toString(),
            EntityType.CASE,
            case.id.toString(),
            PermissionRelation.ADMIN,
        )

        // Inject the loop config as the first message to the loop agent.
        val loopConfigJson = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()
            .writeValueAsString(effectiveLoopConfig)
        val actor = Actor(
            id = creatorId.toString(),
            displayName = userService.findById(creatorId)?.displayName() ?: creatorId.toString(),
            role = ActorRole.USER,
        )
        caseService.addMessage(
            caseId = case.id,
            actor = actor,
            content = listOf(MessageContent.Text("@$LOOP_AGENT_NAME $loopConfigJson")),
        )

        // Transition CLAIMED → TARGETING.
        runRepository.updateStatus(run.id, RunStatus.TARGETING)

        logger.info {
            "[Executor] Run=${run.id} → TARGETING, loop case=${case.id}, agent=$LOOP_AGENT_NAME"
        }

        return case.id
    }

    // -------------------------------------------------------------------------
    // Case monitoring
    // -------------------------------------------------------------------------

    /**
     * Await the loop case launch and close the Run based on the observed [CaseStatus].
     * Uses [SchedulerProperties.loopCaseTimeoutSeconds] (not [launchTimeoutSeconds]).
     */
    private suspend fun monitorLaunch(
        runId: UUID,
        caseId: UUID,
        runtime: CaseRuntime,
    ) {
        val timeoutMs = Duration.ofSeconds(properties.loopCaseTimeoutSeconds).toMillis()
        val currentStatus = runtime.statusFlow.value

        val observedStatus = when {
            currentStatus == CaseStatus.IDLE || currentStatus.isTerminal() -> currentStatus
            else -> withTimeoutOrNull(timeoutMs) {
                runtime.statusFlow.first { it == CaseStatus.IDLE || it.isTerminal() }
            }
        }

        when {
            observedStatus != null -> closeRun(runId, observedStatus, caseId)
            else -> {
                // Timeout: leave the run TARGETING. The orphan sweep decides later.
                // Mapping a timeout to DONE would be a false success.
                logger.info {
                    "[Executor] Run=$runId loop case=$caseId timed out after ${properties.loopCaseTimeoutSeconds}s " +
                        "— staying TARGETING (orphan sweep will decide)"
                }
            }
        }
    }

    /**
     * Close a Run based on the final [CaseStatus].
     *
     * IDLE → DONE
     * ERROR / KILLED → FAILED
     * anything else → DONE (unexpected terminal)
     */
    private fun closeRun(
        runId: UUID,
        caseStatus: CaseStatus?,
        caseId: UUID,
    ) {
        val now = Instant.now(clock)
        val (status, error) = caseStatus.toRunOutcome()
        runRepository.updateStatus(runId, status, now, error)
        logger.info {
            "[Executor] Run=$runId closed as $status (loopCase=$caseId caseStatus=$caseStatus)"
        }
    }

    companion object : KLogging() {
        /**
         * Name of the platform-scoped loop agent that receives the fan-out config.
         * TODO: make configurable via [SchedulerProperties] when seeding proves stable.
         */
        const val LOOP_AGENT_NAME = "scheduler-loop"

        private fun CaseStatus?.toRunOutcome(): Pair<RunStatus, String?> =
            when (this) {
                CaseStatus.ERROR, CaseStatus.KILLED ->
                    RunStatus.FAILED to "Loop case reached terminal status $this"
                else ->
                    RunStatus.DONE to null
            }
    }
}
