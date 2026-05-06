package io.whozoss.agentos.aiModel

import org.springframework.data.neo4j.repository.Neo4jRepository
import org.springframework.data.neo4j.repository.query.Query

/**
 * Spring Data Neo4j repository for [AiModelNode].
 */
interface AiModelNodeNeo4jRepository : Neo4jRepository<AiModelNode, String> {
    /**
     * Find all non-removed model configs belonging to a provider config, ordered by apiName.
     */
    @Query(
        $$"""MATCH (m:AiModel)
            WHERE m.aiProviderId = $aiProviderId AND (m.removed IS NULL OR m.removed = false)
            RETURN m ORDER BY m.apiName ASC
            """,
    )
    fun findActiveByAiProviderId(aiProviderId: String): List<AiModelNode>

    /**
     * Find all non-removed namespace-shared model configs (userId IS NULL), ordered by apiName.
     *
     * Story 6.4 AC14: filters `userId IS NULL` so that user-scoped overrides are hidden from
     * namespace-scope listings (FR22, AR8).
     */
    @Query(
        $$"""MATCH (m:AiModel)
            WHERE m.namespaceId = $namespaceId AND m.userId IS NULL AND (m.removed IS NULL OR m.removed = false)
            RETURN m ORDER BY m.apiName ASC
            """,
    )
    fun findActiveByNamespaceId(namespaceId: String): List<AiModelNode>

    /**
     * Find the first non-removed model config under [aiProviderId] whose apiName matches exactly.
     */
    @Query(
        $$"""MATCH (m:AiModel)
            WHERE m.aiProviderId = $aiProviderId AND m.apiName = $apiName
            AND (m.removed IS NULL OR m.removed = false)
            RETURN m LIMIT 1
            """,
    )
    fun findActiveByAiProviderIdAndApiName(
        aiProviderId: String,
        apiName: String,
    ): AiModelNode?

    /**
     * Find the first non-removed model config under [aiProviderId] whose alias matches exactly.
     */
    @Query(
        $$"""MATCH (m:AiModel)
            WHERE m.aiProviderId = $aiProviderId AND m.alias = $alias
            AND (m.removed IS NULL OR m.removed = false)
            RETURN m LIMIT 1
            """,
    )
    fun findActiveByAiProviderIdAndAlias(
        aiProviderId: String,
        alias: String,
    ): AiModelNode?

    /**
     * Find all non-removed model configs scoped to a user, ordered by apiName then id.
     * The `id` tie-breaker keeps pagination deterministic when two models share an apiName
     * under the same parent (legal post PR #797 — alias-only uniqueness).
     */
    @Query(
        $$"""MATCH (m:AiModel)
            WHERE m.userId = $userId AND (m.removed IS NULL OR m.removed = false)
            RETURN m ORDER BY m.apiName ASC, m.id ASC
            """,
    )
    fun findActiveByUserId(userId: String): List<AiModelNode>

    /**
     * NULL-tolerant triple lookup for the 3-tier reconciliation service (story 6.4).
     *
     * `name` matches [AiModelNode.alias] first; when alias is null, falls back to
     * [AiModelNode.apiModelName]. Matches `(namespaceId, userId, name)` with NULL parity.
     *
     * Determinism: when both an alias-match and an apiName-fallback row are eligible in the
     * same scope (legal — alias-only uniqueness, cf. PR #797), `ORDER BY m.alias DESC` puts
     * the explicit alias-match first (`alias != null` sorts before `alias IS NULL` under
     * `DESC`). The `m.id ASC` tie-breaker keeps the LIMIT 1 stable across query plans.
     */
    @Query(
        $$"""MATCH (m:AiModel)
            WHERE (m.namespaceId = $namespaceId OR (m.namespaceId IS NULL AND $namespaceId IS NULL))
              AND (m.userId = $userId OR (m.userId IS NULL AND $userId IS NULL))
              AND ((m.alias = $name) OR (m.alias IS NULL AND m.apiName = $name))
              AND (m.removed IS NULL OR m.removed = false)
            RETURN m ORDER BY m.alias DESC, m.id ASC LIMIT 1
            """,
    )
    fun findActiveByTriple(
        namespaceId: String?,
        userId: String?,
        name: String,
    ): AiModelNode?
}
