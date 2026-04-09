package io.whozoss.agentos.persistence.neo4j

import org.springframework.data.neo4j.repository.Neo4jRepository
import org.springframework.data.neo4j.repository.query.Query

/**
 * Spring Data Neo4j repository for [LlmModelConfigNode].
 */
interface LlmModelConfigNodeNeo4jRepository : Neo4jRepository<LlmModelConfigNode, String> {
    /**
     * Find all non-removed model configs belonging to a provider config, ordered by apiName.
     *
     * Used for:
     * - listing models under a provider ([Neo4jLlmModelConfigRepository.findByParent])
     * - uniqueness checks on (llmConfigId, apiName) and (llmConfigId, alias)
     * - alias-based resolution: find the model matching an alias, then load its parent
     *   [LlmConfigNode] to get provider credentials
     */
    @Query(
        "MATCH (m:LlmModelConfig) " +
            "WHERE m.llmConfigId = \$llmConfigId AND (m.removed IS NULL OR m.removed = false) " +
            "RETURN m ORDER BY m.apiName ASC",
    )
    fun findActiveByLlmConfigId(llmConfigId: String): List<LlmModelConfigNode>
}
