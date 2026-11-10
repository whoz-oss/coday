package io.whozoss.agentos.workflow

import io.whozoss.agentos.entity.EntityService
import java.util.UUID

/**
 * Manages [Workflow] definitions. Authorization is the controller's job; this service owns the
 * business rules: validation, name uniqueness per scope, the undeletable default workflow, and
 * refusing to delete a workflow still referenced by a scheduled prompt.
 */
interface WorkflowService : EntityService<Workflow, UUID> {
    /** Non-removed platform workflows. */
    fun findPlatform(): List<Workflow>

    /** Workflows usable in [namespaceId]: the namespace's own, then the platform ones. */
    fun findAvailable(namespaceId: UUID): List<Workflow>

    /**
     * The workflow named [name] as seen from [namespaceId]: the namespace's own first, then the
     * platform one. Null [namespaceId] looks at platform workflows only. Null when none matches.
     * Name matching is case- and accent-insensitive (same rule as uniqueness).
     */
    fun findByName(
        namespaceId: UUID?,
        name: String,
    ): Workflow?

    /** The seeded user-group workflow ([Workflow.USER_GROUP_WORKFLOW_NAME]), default of scheduled prompts. */
    fun getDefault(): Workflow

    /**
     * The workflow a scheduled prompt in [namespaceId] may reference: [workflowId] when given,
     * the default one otherwise.
     *
     * @throws io.whozoss.agentos.exception.BadRequestException when [workflowId] does not exist,
     *   is removed, or belongs to another namespace.
     */
    fun resolveForScheduledPrompt(
        workflowId: UUID?,
        namespaceId: UUID?,
    ): Workflow
}

/** Tells whether a workflow is still referenced; implemented by the scheduled prompt side. */
fun interface WorkflowUsageChecker {
    fun isInUse(workflowId: UUID): Boolean
}
