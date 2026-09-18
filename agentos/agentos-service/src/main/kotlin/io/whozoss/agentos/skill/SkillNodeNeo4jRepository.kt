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
     * Matches by the scalar [SkillNode.namespaceId] property.
     */
    @Query(
        $$"""
            MATCH (s:Skill)
            WHERE s.namespaceId = $namespaceId AND (s.removed IS NULL OR s.removed = false)
            RETURN s ORDER BY s.name ASC
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

    /**
     * Find non-removed skills for a namespace whose names match any in the given collection (case-insensitive).
     */
    @Query(
        $$"""
            MATCH (s:Skill)
            WHERE (s.namespaceId = $namespaceId OR s.namespaceId IS NULL)
              AND (s.removed IS NULL OR s.removed = false)
              AND toLower(s.name) IN $lowercasedNames
            RETURN s ORDER BY s.name ASC
            """,
    )
    fun findByNamespaceIdAndNames(
        namespaceId: String,
        lowercasedNames: Collection<String>,
    ): List<SkillNode>
}
