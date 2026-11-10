package io.whozoss.agentos.workflow

import io.whozoss.agentos.entity.EntityRepository
import java.util.UUID

/**
 * Persistence for [Workflow].
 *
 * The parent of a workflow is its namespace: [findByParent] returns the namespace workflows only.
 * Platform workflows are returned by [findPlatform].
 */
interface WorkflowRepository : EntityRepository<Workflow, UUID> {
    /** Non-removed platform workflows (namespaceId IS NULL). */
    fun findPlatform(): List<Workflow>

    /** The non-removed workflow named [name] in the scope of [namespaceId] (null = platform), or null. */
    fun findByName(
        namespaceId: UUID?,
        name: String,
    ): Workflow?
}
