package io.whozoss.agentos.scheduledPrompt

import io.whozoss.agentos.agentConfig.AgentConfigService
import io.whozoss.agentos.workflow.LoopConfig
import io.whozoss.agentos.workflow.LoopConfigResolver
import io.whozoss.agentos.workflow.WorkflowRunBindings
import io.whozoss.agentos.workflow.WorkflowService
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * [LoopConfigResolver] for scheduler-started loop cases.
 *
 * Run (by its loop case id) → scheduled prompt → its [io.whozoss.agentos.workflow.Workflow] (the
 * default one when it names none) → the workflow's steps with the run bindings substituted.
 * Returns null when the case was not started by the scheduler.
 */
@Service
@ConditionalOnProperty(name = ["agentos.prompt.scheduler.enabled"], havingValue = "true")
class ScheduledPromptLoopConfigResolver(
    private val runRepository: ScheduledPromptRunRepository,
    private val scheduledPromptRepository: ScheduledPromptRepository,
    private val agentConfigService: AgentConfigService,
    private val workflowService: WorkflowService,
) : LoopConfigResolver {
    override fun resolve(caseId: UUID): LoopConfig? {
        val run = runRepository.findByLoopCaseId(caseId) ?: return null
        val scheduledPrompt =
            checkNotNull(scheduledPromptRepository.findById(run.scheduledPromptId)) {
                "ScheduledPrompt ${run.scheduledPromptId} of run ${run.id} not found"
            }
        val workflow = workflowService.resolveForScheduledPrompt(scheduledPrompt.workflowId, scheduledPrompt.namespaceId)
        val agentName =
            checkNotNull(agentConfigService.findById(scheduledPrompt.agentConfigId)?.name) {
                "AgentConfig ${scheduledPrompt.agentConfigId} of scheduled prompt ${scheduledPrompt.id} not found"
            }
        val bindings =
            WorkflowRunBindings(
                agentConfigId = scheduledPrompt.agentConfigId,
                agentName = agentName,
                promptId = scheduledPrompt.promptTemplateId,
                title = scheduledPrompt.name,
                scheduledPromptId = scheduledPrompt.id,
                runId = run.id,
            )
        return bindings.applyTo(workflow.toLoopConfig())
    }
}
