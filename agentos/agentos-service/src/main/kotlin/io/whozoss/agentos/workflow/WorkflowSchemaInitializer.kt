package io.whozoss.agentos.workflow

import mu.KLogging
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.data.neo4j.core.Neo4jClient
import org.springframework.stereotype.Component

/**
 * Idempotent Neo4j schema initialiser for [Workflow]: a UNIQUE constraint on `doubleKey`
 * (name unique per namespace, platform included) and an index on `namespaceId` for listings.
 */
@Component
@ConditionalOnExpression(
    "'\${agentos.persistence.mode:in-memory}' == 'neo4j' " +
        "or '\${agentos.persistence.mode:in-memory}' == 'embedded-neo4j'",
)
class WorkflowSchemaInitializer(
    private val neo4jClient: Neo4jClient,
) : ApplicationRunner {
    override fun run(args: ApplicationArguments) {
        neo4jClient
            .query("CREATE CONSTRAINT workflow_double_key_unique IF NOT EXISTS FOR (w:Workflow) REQUIRE w.doubleKey IS UNIQUE")
            .run()
        neo4jClient
            .query("CREATE INDEX workflow_namespace_id IF NOT EXISTS FOR (w:Workflow) ON (w.namespaceId)")
            .run()
        logger.info { "[WorkflowSchema] constraint 'workflow_double_key_unique' and index 'workflow_namespace_id' ensured" }
    }

    companion object : KLogging() {
        const val DOUBLE_KEY_CONSTRAINT_NAME = "workflow_double_key_unique"
    }
}
