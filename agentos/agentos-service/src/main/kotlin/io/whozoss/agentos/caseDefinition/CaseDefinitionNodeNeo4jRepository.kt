package io.whozoss.agentos.caseDefinition

import org.springframework.data.neo4j.repository.Neo4jRepository
import org.springframework.data.neo4j.repository.query.Query

/**
 * Spring Data Neo4j repository for [CaseDefinitionNode].
 */
interface CaseDefinitionNodeNeo4jRepository : Neo4jRepository<CaseDefinitionNode, String> {

    /**
     * Find all non-removed namespace-shared case definitions (userId IS NULL) for the given namespace.
     * User-scoped overlays (userId != null) are intentionally excluded.
     */
    @Query(
        $$"""
            MATCH (cd:CaseDefinition)
            WHERE cd.namespaceId = $namespaceId
              AND cd.userId IS NULL
              AND NOT COALESCE(cd.removed, false)
            RETURN cd ORDER BY cd.name ASC
            """,
    )
    fun findActiveByNamespaceId(namespaceId: String): List<CaseDefinitionNode>

    /**
     * Find all non-removed platform-level case definitions (namespaceId IS NULL AND userId IS NULL).
     */
    @Query(
        """
            MATCH (cd:CaseDefinition)
            WHERE cd.namespaceId IS NULL
              AND cd.userId IS NULL
              AND NOT COALESCE(cd.removed, false)
            RETURN cd ORDER BY cd.name ASC
            """,
    )
    fun findActivePlatform(): List<CaseDefinitionNode>

    /**
     * Find a single non-removed case definition matched by its [CaseDefinitionNode.tripleKey] discriminator.
     */
    @Query(
        $$"""
            MATCH (cd:CaseDefinition {tripleKey: $tripleKey})
            WHERE NOT COALESCE(cd.removed, false)
            RETURN cd LIMIT 1
            """,
    )
    fun findActiveByTripleKey(tripleKey: String): CaseDefinitionNode?

    /**
     * Find all non-removed case definitions that belong to any of the four overlay layers for the
     * given (namespaceId, userId) pair, ordered by name:
     *   - platform         (namespaceId IS NULL AND userId IS NULL)
     *   - user-global      (userId = userId AND namespaceId IS NULL)
     *   - namespace-shared (namespaceId = namespaceId AND userId IS NULL)
     *   - user×namespace   (namespaceId = namespaceId AND userId = userId)
     *
     * Unlike Prompt.findEffective, the agent is ALWAYS present on a CaseDefinition
     * (agentConfigId is mandatory). Therefore there is NO bifurcation: a single
     * access-control branch applies for all case definitions:
     *   - The agent must exist and be enabled.
     *   - The user must be super-admin OR a member of a UserGroup to which the agent is DEPLOYED_TO.
     *
     * There is NO namespace-direct fallback: if an agent is not deployed to any user-group,
     * only super-admins can see its case definitions.
     *
     * Performance:
     *   - u and ns resolved once via UNIQUE id constraints.
     *   - User group memberships collected once into accessibleGroupIds before scanning.
     *   - MATCH (cd:CaseDefinition) runs with 1 input row, using namespaceId/userId indexes.
     *   - BELONGS_TO traversal is a required MATCH (not OPTIONAL) because agentConfigId is mandatory.
     */
    @Query(
        $$"""
            OPTIONAL MATCH (u:User)
              WHERE u.id = $userId AND NOT COALESCE(u.removed, false)
            OPTIONAL MATCH (ns:Namespace)
              WHERE ns.id = $namespaceId AND NOT COALESCE(ns.removed, false)
            OPTIONAL MATCH (u)-[:MEMBER|ADMIN]->(ag:UserGroup)-[:BELONGS_TO]->(ns)
              WHERE NOT COALESCE(ag.removed, false)
            WITH u, ns,
                 COALESCE(u.isAdmin, false) AS isAdmin,
                 collect(ag.id) AS accessibleGroupIds
            MATCH (cd:CaseDefinition)
            WHERE NOT COALESCE(cd.removed, false)
              AND (
                (cd.namespaceId IS NULL AND cd.userId IS NULL)
                OR (cd.userId = $userId AND cd.namespaceId IS NULL)
                OR (cd.namespaceId = $namespaceId AND cd.userId IS NULL)
                OR (cd.namespaceId = $namespaceId AND cd.userId = $userId)
              )
            MATCH (cd)-[:BELONGS_TO]->(a:AgentConfig)
              WHERE NOT COALESCE(a.removed, false)
                AND a.enabled = true
            WITH cd, a, isAdmin, accessibleGroupIds
            WHERE isAdmin
              OR EXISTS {
                MATCH (a)-[:DEPLOYED_TO]->(g:UserGroup)
                WHERE g.id IN accessibleGroupIds
              }
            RETURN cd ORDER BY cd.name ASC
            """,
    )
    fun findEffective(namespaceId: String, userId: String): List<CaseDefinitionNode>

    /**
     * Find all non-removed case definitions at an exact scope level, optionally filtered by agentConfigIds.
     * Scope is determined by the combination of namespaceId and userId (null = not set).
     * When [agentConfigIds] is null or empty, all case definitions at the scope level are returned.
     */
    @Query(
        $$"""
            MATCH (cd:CaseDefinition)
            WHERE NOT COALESCE(cd.removed, false)
              AND (cd.namespaceId = $namespaceId OR ($namespaceId IS NULL AND cd.namespaceId IS NULL))
              AND (cd.userId = $userId OR ($userId IS NULL AND cd.userId IS NULL))
              AND ($agentConfigIds IS NULL OR cd.agentConfigId IN $agentConfigIds)
            RETURN cd ORDER BY cd.name ASC
            """,
    )
    fun findByScope(
        namespaceId: String?,
        userId: String?,
        agentConfigIds: List<String>?,
    ): List<CaseDefinitionNode>
}
