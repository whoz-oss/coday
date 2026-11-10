package io.whozoss.agentos.workflow

import org.springframework.data.neo4j.repository.Neo4jRepository
import org.springframework.data.neo4j.repository.query.Query
import org.springframework.transaction.annotation.Transactional

/** Spring Data Neo4j repository for [WorkflowNode]. */
interface WorkflowNodeNeo4jRepository : Neo4jRepository<WorkflowNode, String> {
    /** Non-removed workflows of one namespace, or platform workflows when [namespaceId] is null. */
    @Transactional(readOnly = true)
    @Query(
        $$"""
            MATCH (w:Workflow)
            WHERE NOT COALESCE(w.removed, false)
              AND (w.namespaceId = $namespaceId OR ($namespaceId IS NULL AND w.namespaceId IS NULL))
            RETURN w ORDER BY w.name ASC
            """,
    )
    fun findActiveByScope(namespaceId: String?): List<WorkflowNode>

    /** The non-removed workflow carrying [doubleKey], or null. */
    @Transactional(readOnly = true)
    @Query(
        $$"""
            MATCH (w:Workflow {doubleKey: $doubleKey})
            WHERE NOT COALESCE(w.removed, false)
            RETURN w LIMIT 1
            """,
    )
    fun findActiveByDoubleKey(doubleKey: String): WorkflowNode?
}
