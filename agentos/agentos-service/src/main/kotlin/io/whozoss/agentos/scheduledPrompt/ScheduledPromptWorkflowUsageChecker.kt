package io.whozoss.agentos.scheduledPrompt

import io.whozoss.agentos.workflow.WorkflowUsageChecker
import org.springframework.stereotype.Component
import java.util.UUID

/** A workflow is in use while a non-removed scheduled prompt references it. */
@Component
class ScheduledPromptWorkflowUsageChecker(
    private val scheduledPromptRepository: ScheduledPromptRepository,
) : WorkflowUsageChecker {
    override fun isInUse(workflowId: UUID): Boolean = scheduledPromptRepository.existsActiveByWorkflowId(workflowId)
}
