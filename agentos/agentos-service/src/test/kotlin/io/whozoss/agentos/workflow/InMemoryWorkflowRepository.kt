package io.whozoss.agentos.workflow

import io.whozoss.agentos.entity.EntityRepository
import io.whozoss.agentos.entity.InMemoryEntityRepository
import io.whozoss.agentos.util.toSlug
import java.util.UUID

/** In-memory [WorkflowRepository] for unit tests. Name matching is slug-based, like the Neo4j doubleKey. */
class InMemoryWorkflowRepository(
    private val delegate: InMemoryEntityRepository<Workflow, UUID?> =
        InMemoryEntityRepository(parentIdExtractor = { it.namespaceId }, comparator = compareBy { it.name }),
) : WorkflowRepository,
    EntityRepository<Workflow, UUID> by delegate as EntityRepository<Workflow, UUID> {
    override fun findPlatform(): List<Workflow> = delegate.findAll().filter { it.namespaceId == null }

    override fun findByName(
        namespaceId: UUID?,
        name: String,
    ): Workflow? = delegate.findAll().firstOrNull { it.namespaceId == namespaceId && it.name.toSlug() == name.toSlug() }
}
