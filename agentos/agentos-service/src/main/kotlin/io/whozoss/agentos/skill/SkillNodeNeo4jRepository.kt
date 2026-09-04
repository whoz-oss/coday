package io.whozoss.agentos.skill

import org.springframework.data.neo4j.repository.Neo4jRepository
import org.springframework.data.neo4j.repository.query.Query

/**
 * Spring Data Neo4j repository for [SkillNode].
 */
interface SkillNodeNeo4jRepository : Neo4jRepository<SkillNode, String> {
    /**
     * Find all non-removed namespace-scoped skills, ordered by name ASC.
     *
     * Traverses the BELONGS_TO edge and filters by the Namespace id.
     */
    @Query(
        $$"""
            MATCH (s:Skill)-[r:BELONGS_TO]->(ns:Namespace)
            WHERE ns.id = $namespaceId AND (s.removed IS NULL OR s.removed = false)
            RETURN s, r, ns ORDER BY s.name ASC
            """,
    )
    fun findActiveByNamespaceId(namespaceId: String): List<SkillNode>

    /**
     * Find all non-removed platform-level skills (`namespaceId IS NULL`), ordered by name ASC.
     */
    @Query(
        """
            MATCH (s:Skill)
            WHERE s.namespaceId IS NULL AND (s.removed IS NULL OR s.removed = false)
            RETURN s ORDER BY s.name ASC
            """,
    )
    fun findActivePlatform(): List<SkillNode>

    /**
     * Find a single non-removed skill matched by its [SkillNode.doubleKey] discriminator.
     */
    @Query(
        $$"""
            MATCH (s:Skill {doubleKey: $doubleKey})
            WHERE s.removed IS NULL OR s.removed = false
            RETURN s
            LIMIT 1
            """,
    )
    fun findActiveByDoubleKey(doubleKey: String): SkillNode?
}
