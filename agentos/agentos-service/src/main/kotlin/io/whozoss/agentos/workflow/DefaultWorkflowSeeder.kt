package io.whozoss.agentos.workflow

import io.whozoss.agentos.exception.ConflictException
import io.whozoss.agentos.scheduledPrompt.ScheduledPromptRepository
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.userGroup.ListAgentUsersTool
import mu.KLogging
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Seeds the platform **user-group** [Workflow] at startup, then points every scheduled prompt that
 * names no workflow at it (handover D5).
 *
 * It is the scheduler's historical behaviour expressed as data: list the users the scheduled agent
 * is deployed to (`listAgentUsers`, fed with the agent by
 * [io.whozoss.agentos.scheduledPrompt.ScheduledWorkflowWiring]), then start one case per user.
 * The three other predefined workflows (talent, task, dossier) are created by Whoz, not here.
 *
 * Existence, not content: created only when absent, never updated or deleted afterwards. Several
 * instances booting together race on the `doubleKey` constraint; the losers get a 409, logged.
 * The backfill is a single idempotent query, so running it on every boot is harmless.
 */
@Service
@Order(103)
class DefaultWorkflowSeeder(
    private val workflowService: WorkflowService,
    private val workflowRepository: WorkflowRepository,
    private val scheduledPromptRepository: ScheduledPromptRepository,
) : ApplicationRunner {
    override fun run(args: ApplicationArguments) = seed()

    internal fun seed() {
        val default = workflowRepository.findByName(null, Workflow.USER_GROUP_WORKFLOW_NAME) ?: create()
        val backfilled = scheduledPromptRepository.backfillWorkflowId(default.id)
        if (backfilled > 0) logger.info { "[WorkflowSeeder] Linked $backfilled scheduled prompt(s) to the default workflow" }
    }

    private fun create(): Workflow =
        try {
            workflowService.create(defaultWorkflow()).also {
                logger.info { "[WorkflowSeeder] Created platform workflow '${it.name}'" }
            }
        } catch (e: ConflictException) {
            logger.info { "[WorkflowSeeder] Default workflow created concurrently, reusing it" }
            checkNotNull(workflowRepository.findByName(null, Workflow.USER_GROUP_WORKFLOW_NAME)) {
                "Default workflow vanished after a conflict"
            }
        }

    companion object : KLogging() {
        /** The user-group definition: the agent's deployment audience, then one case per user. */
        fun defaultWorkflow(): Workflow =
            Workflow(
                metadata = EntityMetadata(id = UUID.randomUUID()),
                namespaceId = null,
                name = Workflow.USER_GROUP_WORKFLOW_NAME,
                description = "Lists the users the scheduled agent is deployed to, then starts one case per user with the scheduled prompt.",
                steps =
                    listOf(
                        WorkflowStep(id = "list-audience", tool = ListAgentUsersTool.TOOL_NAME),
                        WorkflowStep(id = "start-cases", tool = Workflow.BATCH_CASE_STARTER_TOOL),
                    ),
            )
    }
}
