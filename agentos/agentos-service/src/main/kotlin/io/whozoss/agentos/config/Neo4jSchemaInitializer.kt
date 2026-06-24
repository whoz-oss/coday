package io.whozoss.agentos.config

import io.whozoss.agentos.sdk.caseEvent.CaseEventType
import mu.KLogging
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.core.annotation.Order
import org.springframework.data.neo4j.core.Neo4jClient
import org.springframework.stereotype.Component

/**
 * Cross-entity Neo4j schema initialiser.
 *
 * Responsible for per-node `id` uniqueness constraints for every entity label and
 * CaseEvent subtype — concerns that span all entity types and have no natural home
 * in any single per-entity initialiser.
 *
 * Entity-specific backfills and constraints live in their own initialiser classes
 * (e.g. [io.whozoss.agentos.namespace.NamespaceSchemaInitializer],
 * [io.whozoss.agentos.user.UserSchemaInitializer],
 * [io.whozoss.agentos.agentConfig.AgentConfigSchemaInitializer],
 * [io.whozoss.agentos.userGroup.UserGroupSchemaInitializer]).
 */
@Component
@Order(1)
@ConditionalOnExpression(
    "'\${agentos.persistence.mode:in-memory}' == 'neo4j' " +
        "or '\${agentos.persistence.mode:in-memory}' == 'embedded-neo4j'",
)
class Neo4jSchemaInitializer(
    private val neo4jClient: Neo4jClient,
) : ApplicationRunner {
    override fun run(args: ApplicationArguments) {
        ensureIdConstraints()
    }

    private fun ensureIdConstraints() {
        // ── Per-node id uniqueness constraints ──────────────────────────────
        // Every node label that carries an `id` property gets a UNIQUE
        // constraint so Neo4j enforces identity at the storage level and
        // can resolve MATCH (n {id: $x}) with an O(log n) index seek.
        val idConstraints =
            listOf(
                "namespace_id_unique" to "Namespace",
                "user_id_unique" to "User",
                "case_id_unique" to "Case",
                "agent_config_id_unique" to "AgentConfig",
                "ai_provider_id_unique" to "AiProvider",
                "ai_model_id_unique" to "AiModel",
                "integration_config_id_unique" to "IntegrationConfig",
                "user_group_id_unique" to "UserGroup",
                "feedback_id_unique" to "Feedback",
            )

        // CaseEvent base label + one entry per subtype derived from the canonical enum.
        // CaseEventType.value is the exact Neo4j label string, so this stays in sync
        // automatically whenever a new event subtype is added to the sealed hierarchy.
        val caseEventIdConstraints =
            listOf("case_event_id_unique" to "CaseEvent") +
                CaseEventType.entries.map { type ->
                    val constraintName =
                        type.value
                            .replace(Regex("([A-Z])")) { "_${it.value}" }
                            .trimStart('_')
                            .lowercase() + "_id_unique"
                    constraintName to type.value
                }

        (idConstraints + caseEventIdConstraints).forEach { (constraintName, label) ->
            neo4jClient
                .query(
                    "CREATE CONSTRAINT $constraintName IF NOT EXISTS " +
                        "FOR (n:$label) REQUIRE n.id IS UNIQUE",
                ).run()
            logger.info { "[Neo4jSchemaInitializer] Constraint $constraintName ensured" }
        }
    }

    companion object : KLogging()
}
