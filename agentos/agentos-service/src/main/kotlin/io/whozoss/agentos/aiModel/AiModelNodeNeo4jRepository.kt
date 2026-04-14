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
        "MATCH (m:LlmModelConfig) " +
            "WHERE m.llmConfigId = \$llmConfigId AND (m.removed IS NULL OR m.removed = false) " +
            "RETURN m ORDER BY m.apiName ASC",
    )
    fun findActiveByLlmConfigId(llmConfigId: String): List<AiModelNode>

    /**
     * Find all non-removed model configs belonging to a namespace, across all provider
     * configs. Uses the denormalised [AiModelNode.namespaceId] property.
     */
    @Query(
        "MATCH (m:LlmModelConfig) " +
            "WHERE m.namespaceId = \$namespaceId AND (m.removed IS NULL OR m.removed = false) " +
            "RETURN m ORDER BY m.apiName ASC",
    )
    fun findActiveByNamespaceId(namespaceId: String): List<AiModelNode>

    /**
     * Find the first non-removed model config under [llmConfigId] whose apiName matches exactly.
     */
    @Query(
        "MATCH (m:LlmModelConfig) " +
            "WHERE m.llmConfigId = \$llmConfigId AND m.apiName = \$apiName " +
            "AND (m.removed IS NULL OR m.removed = false) " +
            "RETURN m LIMIT 1",
    )
    fun findActiveByLlmConfigIdAndApiName(
        llmConfigId: String,
        apiName: String,
    ): AiModelNode?

    /**
     * Find the first non-removed model config under [llmConfigId] whose alias matches exactly.
     */
    @Query(
        "MATCH (m:LlmModelConfig) " +
            "WHERE m.llmConfigId = \$llmConfigId AND m.alias = \$alias " +
            "AND (m.removed IS NULL OR m.removed = false) " +
            "RETURN m LIMIT 1",
    )
    fun findActiveByLlmConfigIdAndAlias(
        llmConfigId: String,
        alias: String,
    ): AiModelNode?
}
