package io.whozoss.agentos.workflow

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.whozoss.agentos.exception.ConflictException
import io.whozoss.agentos.scheduledPrompt.ScheduledPromptRepository
import io.whozoss.agentos.sdk.entity.EntityMetadata
import mu.KLogging
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Seeds the platform default [Workflow] at startup, then points every scheduled prompt that names
 * no workflow at it.
 *
 * The default is the scheduler's historical behaviour expressed as data: list the users the target
 * agent is deployed to, then start one case per user with the scheduled prompt. Its params only
 * carry run bindings, so the same definition serves every scheduled prompt.
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
        val default = workflowRepository.findByName(null, WorkflowServiceImpl.DEFAULT_WORKFLOW_NAME) ?: create()
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
            checkNotNull(workflowRepository.findByName(null, WorkflowServiceImpl.DEFAULT_WORKFLOW_NAME)) { "Default workflow vanished after a conflict" }
        }

    companion object : KLogging() {
        private val MAPPER = jacksonObjectMapper()

        /** The default definition; mirrors what the scheduler did before workflows existed. */
        fun defaultWorkflow(): Workflow =
            Workflow(
                metadata = EntityMetadata(id = UUID.randomUUID()),
                namespaceId = null,
                name = WorkflowServiceImpl.DEFAULT_WORKFLOW_NAME,
                description = "Lists the users the scheduled agent is deployed to, then starts one case per user with the scheduled prompt.",
                steps =
                    listOf(
                        LoopStep(
                            id = "list-audience",
                            tool = "ListAgentUsers",
                            input = LoopStepInput(params = MAPPER.valueToTree(mapOf("agentConfigId" to "{{run.agentConfigId}}"))),
                        ),
                        LoopStep(
                            id = "fan-out",
                            tool = "BatchCaseStarter",
                            input =
                                LoopStepInput(
                                    from = "list-audience",
                                    params =
                                        MAPPER.valueToTree(
                                            mapOf(
                                                "agentName" to "{{run.agentName}}",
                                                "promptId" to "{{run.promptId}}",
                                                "title" to "{{run.title}}",
                                                "scheduledPromptId" to "{{run.scheduledPromptId}}",
                                                "runId" to "{{run.runId}}",
                                            ),
                                        ),
                                ),
                        ),
                    ),
            )
    }
}
