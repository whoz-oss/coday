package io.whozoss.agentos.agentConfig

import org.springframework.data.neo4j.repository.Neo4jRepository
import org.springframework.data.neo4j.repository.query.Query

/**
 * Spring Data Neo4j repository for [AgentConfigNode].
 */
interface AgentConfigNodeNeo4jRepository : Neo4jRepository<AgentConfigNode, String> {
    /**
     * Find all non-removed agent configs belonging to a namespace, ordered by name.
     */
    @Query(
        $$"""
            MATCH (a:AgentConfig)
            WHERE a.namespaceId = $namespaceId AND (a.removed IS NULL OR a.removed = false)
            RETURN a ORDER BY a.name ASC
            """,
    )
    fun findActiveByNamespaceId(namespaceId: String): List<AgentConfigNode>

    /**
     * Creates the `BELONGS_TO` relationship from an AgentConfig node to its
     * parent Namespace node.
     *
     * Called after every save. Using an explicit query avoids SDN writing stub
     * [io.whozoss.agentos.namespace.NamespaceNode] properties (empty name /
     * description) onto the existing Namespace node when the relationship is
     * expressed via the @Relationship field.
     */
    @Query(
        $$"""MATCH (a:AgentConfig {id: $agentConfigId})
            MATCH (ns:Namespace {id: $namespaceId})
            MERGE (a)-[:BELONGS_TO]->(ns)
            """,
    )
    fun linkAgentConfigToNamespace(
        agentConfigId: String,
        namespaceId: String,
    )
}
