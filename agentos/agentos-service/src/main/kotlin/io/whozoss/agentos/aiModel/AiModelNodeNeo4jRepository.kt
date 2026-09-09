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
        $$"""
            MATCH (m:AiModel)
            WHERE m.aiProviderId = $aiProviderId AND (m.removed IS NULL OR m.removed = false)
            RETURN m ORDER BY m.apiName ASC
            """,
    )
    fun findActiveByAiProviderId(aiProviderId: String): List<AiModelNode>

    /**
     * Find all non-removed model configs belonging to a namespace, across all provider
     * configs. Uses the denormalised [AiModelNode.namespaceId] property.
     */
    @Query(
        $$"""
            MATCH (m:AiModel)
            WHERE m.namespaceId = $namespaceId AND (m.removed IS NULL OR m.removed = false)
            RETURN m ORDER BY m.apiName ASC
            """,
    )
    fun findActiveByNamespaceId(namespaceId: String): List<AiModelNode>

    /**
     * Find the first non-removed model config under [aiProviderId] whose apiName matches exactly.
     */
    @Query(
        $$"""
            MATCH (m:AiModel)
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
        $$"""
            MATCH (m:AiModel)
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
     * Find all non-removed platform-level model configs (namespaceId IS NULL AND userId IS NULL).
     */
    @Query(
        $$"""
            MATCH (m:AiModel)
            WHERE m.namespaceId IS NULL AND m.userId IS NULL AND (m.removed IS NULL OR m.removed = false)
            RETURN m ORDER BY m.apiName ASC
            """,
    )
    fun findActivePlatformLevel(): List<AiModelNode>

    /**
     * Fetch all non-removed model configs visible for a given namespace in a single query —
     * namespace-scoped models and platform-level models (namespaceId IS NULL).
     *
     * `namespaceId IS NULL OR namespaceId = $namespaceId` covers both layers in one
     * round-trip, avoiding the two separate calls previously made by [findActiveByNamespaceId]
     * + [findActivePlatformLevel].
     */
    @Query(
        $$"""
            MATCH (m:AiModel)
            WHERE (m.namespaceId IS NULL OR m.namespaceId = $namespaceId)
            AND (m.removed IS NULL OR m.removed = false)
            RETURN m ORDER BY m.apiName ASC
            """,
    )
    fun findAllForNamespace(namespaceId: String): List<AiModelNode>
}
