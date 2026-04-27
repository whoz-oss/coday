package io.whozoss.agentos.persistence

import mu.KLogging
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.data.neo4j.core.Neo4jClient
import org.springframework.stereotype.Service

/**
 * Single helper for the parent-child relationship pattern repeated across every entity
 * Neo4j repository (AgentConfig, AiProvider, AiModel, IntegrationConfig, Case, CaseEvent).
 *
 * All callers used to declare a per-repository `linkXxxToYyy` method with the same
 * [Cypher][org.springframework.data.neo4j.repository.query.Query] body — only the
 * labels and parameter names changed. Centralising the pattern here lets us:
 *
 * - drop ~40 lines of duplicated `@Query` declarations,
 * - express the relationship type explicitly at the call site (clearer than the
 *   implicit `BELONGS_TO` repeated in every Cypher snippet),
 * - keep a single audit log line when an edge is materialised.
 *
 * Labels are interpolated server-side via Cypher backticks. Callers are trusted
 * (they pass string literals from code, not user input), so this is not an
 * injection vector.
 */
@Service
@ConditionalOnExpression(
    "'\${agentos.persistence.mode:in-memory}' == 'neo4j' " +
        "or '\${agentos.persistence.mode:in-memory}' == 'embedded-neo4j'",
)
class Neo4jChildLinkService(
    private val neo4jClient: Neo4jClient,
) {

    /**
     * MERGE a directed [relationship] edge from the child node to the parent node.
     * Idempotent — no-op when the edge already exists.
     *
     * @param childLabel Neo4j label of the child node (e.g. `"AgentConfig"`)
     * @param childId    `id` property of the child node
     * @param parentLabel Neo4j label of the parent node (e.g. `"Namespace"`, `"Case"`, `"User"`)
     * @param parentId    `id` property of the parent node
     * @param relationship Edge type to MERGE; defaults to `BELONGS_TO`
     */
    fun link(
        childLabel: String,
        childId: String,
        parentLabel: String,
        parentId: String,
        relationship: String = "BELONGS_TO",
    ) {
        val cypher = """
            MATCH (c:`$childLabel` {id: ${'$'}childId})
            MATCH (p:`$parentLabel` {id: ${'$'}parentId})
            MERGE (c)-[:`$relationship`]->(p)
        """.trimIndent()

        neo4jClient.query(cypher)
            .bind(childId).to("childId")
            .bind(parentId).to("parentId")
            .run()

        logger.debug { "[Neo4jChildLink] $childLabel($childId) -[:$relationship]-> $parentLabel($parentId)" }
    }

    companion object : KLogging()
}
