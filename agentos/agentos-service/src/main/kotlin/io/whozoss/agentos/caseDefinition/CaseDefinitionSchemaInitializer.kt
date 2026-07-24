package io.whozoss.agentos.caseDefinition

import mu.KLogging
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.data.neo4j.core.Neo4jClient
import org.springframework.stereotype.Component

/**
 * Idempotent Neo4j schema initialiser for [CaseDefinition].
 *
 * Creates:
 * - A UNIQUE constraint on `tripleKey` to enforce name uniqueness per
 *   `(namespaceId, userId, name)` scope. On soft-delete the key is rewritten
 *   to `tombstone:<id>` to free the unique slot immediately.
 * - Auxiliary indexes on `namespaceId`, `userId`, and `agentConfigId` to back
 *   the listing and effective-resolution queries.
 */
@Component
@ConditionalOnExpression(
    "'\${agentos.persistence.mode:in-memory}' == 'neo4j' " +
        "or '\${agentos.persistence.mode:in-memory}' == 'embedded-neo4j'",
)
class CaseDefinitionSchemaInitializer(
    private val neo4jClient: Neo4jClient,
) : ApplicationRunner {
    override fun run(args: ApplicationArguments) {
        ensureTripleKeyUniqueConstraint()
        ensureNamespaceIdIndex()
        ensureUserIdIndex()
        ensureAgentConfigIdIndex()
    }

    private fun ensureTripleKeyUniqueConstraint() {
        neo4jClient
            .query(
                "CREATE CONSTRAINT case_definition_triple_key_unique IF NOT EXISTS " +
                    "FOR (cd:CaseDefinition) REQUIRE cd.tripleKey IS UNIQUE",
            ).run()
        logger.info { "[CaseDefinitionSchema] constraint 'case_definition_triple_key_unique' ensured" }
    }

    private fun ensureNamespaceIdIndex() {
        neo4jClient
            .query(
                "CREATE INDEX case_definition_namespace_id IF NOT EXISTS FOR (cd:CaseDefinition) ON (cd.namespaceId)",
            ).run()
        logger.info { "[CaseDefinitionSchema] index 'case_definition_namespace_id' ensured" }
    }

    private fun ensureUserIdIndex() {
        neo4jClient
            .query(
                "CREATE INDEX case_definition_user_id IF NOT EXISTS FOR (cd:CaseDefinition) ON (cd.userId)",
            ).run()
        logger.info { "[CaseDefinitionSchema] index 'case_definition_user_id' ensured" }
    }

    private fun ensureAgentConfigIdIndex() {
        neo4jClient
            .query(
                "CREATE INDEX case_definition_agent_config_id IF NOT EXISTS FOR (cd:CaseDefinition) ON (cd.agentConfigId)",
            ).run()
        logger.info { "[CaseDefinitionSchema] index 'case_definition_agent_config_id' ensured" }
    }

    companion object : KLogging()
}
