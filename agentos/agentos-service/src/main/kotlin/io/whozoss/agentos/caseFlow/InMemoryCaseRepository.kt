package io.whozoss.agentos.caseFlow

import io.whozoss.agentos.entity.EntityRepository
import io.whozoss.agentos.entity.InMemoryEntityRepository
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.stereotype.Repository
import java.util.UUID

/**
 * In-memory implementation of [CaseRepository].
 *
 * Active when `agentos.persistence.mode` is absent, `in-memory`, or any value
 * other than `neo4j` or `embedded-neo4j`. This is the default fallback used by
 * the openapi spec generation task and lightweight local runs.
 */
@Repository
@ConditionalOnExpression(
    "'\${agentos.persistence.mode:in-memory}' != 'neo4j' " +
        "and '\${agentos.persistence.mode:in-memory}' != 'embedded-neo4j'",
)
class InMemoryCaseRepository(
    private val delegate: EntityRepository<Case, UUID> = InMemoryEntityRepository(
        parentIdExtractor = { it.namespaceId },
        comparator = compareBy { it.metadata.id },
    ),
) : CaseRepository,
    EntityRepository<Case, UUID> by delegate {

    /**
     * Permissive implementation: returns every case in the namespace. Consistent
     * with [io.whozoss.agentos.permissions.InMemoryPermissionServiceImpl] which
     * always grants access in in-memory mode. Production filtering happens in
     * [Neo4jCaseRepository.findAccessibleByUserInNamespace].
     */
    override fun findAccessibleByUserInNamespace(userId: UUID, namespaceId: UUID): List<Case> =
        delegate.findByParent(namespaceId)
}
