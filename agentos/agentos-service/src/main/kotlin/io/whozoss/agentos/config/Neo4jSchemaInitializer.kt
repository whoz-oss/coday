package io.whozoss.agentos.config

import mu.KLogging
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.data.neo4j.core.Neo4jClient
import org.springframework.stereotype.Component

@Component
@ConditionalOnExpression(
    "'\${agentos.persistence.mode:embedded-neo4j}' == 'neo4j' " +
        "or '\${agentos.persistence.mode:embedded-neo4j}' == 'embedded-neo4j'",
)
class Neo4jSchemaInitializer(
    private val neo4jClient: Neo4jClient,
) : ApplicationRunner {
    override fun run(args: ApplicationArguments) {
        neo4jClient
            .query(
                "CREATE CONSTRAINT user_group_name_namespace_unique IF NOT EXISTS " +
                    "FOR (g:ActiveUserGroup) REQUIRE (g.name, g.namespaceId) IS UNIQUE",
            ).run()
        logger.info { "[Neo4jSchemaInitializer] Constraint user_group_name_namespace_unique ensured" }

        neo4jClient
            .query(
                "CREATE CONSTRAINT namespace_external_id_unique IF NOT EXISTS " +
                    "FOR (n:ActiveNamespace) REQUIRE n.externalId IS UNIQUE",
            ).run()
        logger.info { "[Neo4jSchemaInitializer] Constraint namespace_external_id_unique ensured" }

        neo4jClient
            .query(
                "CREATE CONSTRAINT user_external_id_unique IF NOT EXISTS " +
                    "FOR (u:ActiveUser) REQUIRE u.externalId IS UNIQUE",
            ).run()
        logger.info { "[Neo4jSchemaInitializer] Constraint user_external_id_unique ensured" }

        neo4jClient
            .query(
                "CREATE INDEX namespace_externalId IF NOT EXISTS FOR (n:Namespace) ON (n.externalId)",
            ).run()
        logger.info { "[Neo4jSchemaInitializer] Index namespace_externalId created" }

        neo4jClient
            .query(
                "CREATE INDEX user_externalId IF NOT EXISTS FOR (u:User) ON (u.externalId)",
            ).run()
        logger.info { "[Neo4jSchemaInitializer] Index user_externalId created" }

        // ── Per-node id uniqueness constraints ──────────────────────────────
        // Every node label that carries an `id` property gets a UNIQUE
        // constraint so Neo4j enforces identity at the storage level and
        // can resolve MATCH (n {id: $x}) with an O(log n) index seek.

        val idConstraints =
            listOf(
                // Root / namespace-scoped entities
                "namespace_id_unique" to "Namespace",
                "user_id_unique" to "User",
                "case_id_unique" to "Case",
                "agent_config_id_unique" to "AgentConfig",
                "ai_provider_id_unique" to "AiProvider",
                "ai_model_id_unique" to "AiModel",
                "integration_config_id_unique" to "IntegrationConfig",
                "user_group_id_unique" to "UserGroup",
                "feedback_id_unique" to "Feedback",
                // CaseEvent base label + every concrete subtype label
                "case_event_id_unique" to "CaseEvent",
                "message_event_id_unique" to "MessageEvent",
                "case_status_event_id_unique" to "CaseStatusEvent",
                "warn_event_id_unique" to "WarnEvent",
                "error_event_id_unique" to "ErrorEvent",
                "agent_selected_event_id_unique" to "AgentSelectedEvent",
                "agent_finished_event_id_unique" to "AgentFinishedEvent",
                "agent_running_event_id_unique" to "AgentRunningEvent",
                "tool_request_event_id_unique" to "ToolRequestEvent",
                "tool_response_event_id_unique" to "ToolResponseEvent",
                "thinking_event_id_unique" to "ThinkingEvent",
                "question_event_id_unique" to "QuestionEvent",
                "answer_event_id_unique" to "AnswerEvent",
                "intention_generated_event_id_unique" to "IntentionGeneratedEvent",
                "tool_selected_event_id_unique" to "ToolSelectedEvent",
                "text_chunk_event_id_unique" to "TextChunkEvent",
                "pending_confirmation_event_id_unique" to "PendingConfirmationEvent",
                "confirmation_resolved_event_id_unique" to "ConfirmationResolvedEvent",
            )

        idConstraints.forEach { (constraintName, label) ->
            neo4jClient
                .query(
                    "CREATE CONSTRAINT $constraintName IF NOT EXISTS " +
                        "FOR (n:$label) REQUIRE n.id IS UNIQUE",
                ).run()
            logger.info { "[Neo4jSchemaInitializer] Constraint $constraintName ensured" }
        }

        // Backfill @Version on AgentConfig nodes created before the version field was introduced.
        // Spring Data Neo4j's optimistic-locking check generates MATCH WHERE version = ?
        // which fails if the property is absent. Setting version = 0 makes existing nodes
        // compatible without affecting any application logic.
        val backfilled =
            neo4jClient
                .query(
                    "MATCH (a:AgentConfig) WHERE a.version IS NULL SET a.version = 0 RETURN count(a) AS count",
                ).fetchAs(Long::class.java)
                .mappedBy { _, record -> record["count"].asLong() }
                .one()
                .orElse(0L)
        if (backfilled > 0L) {
            logger.info { "[Neo4jSchemaInitializer] Backfilled version=0 on $backfilled AgentConfig node(s)" }
        }

        // Backfill enabled=false on AgentConfig nodes created before the enabled field was introduced.
        // Once all nodes have the property set, Cypher queries can use a.enabled directly
        // without COALESCE(a.enabled, false).
        val backfilledEnabled =
            neo4jClient
                .query(
                    "MATCH (a:AgentConfig) WHERE a.enabled IS NULL SET a.enabled = false RETURN count(a) AS count",
                ).fetchAs(Long::class.java)
                .mappedBy { _, record -> record["count"].asLong() }
                .one()
                .orElse(0L)
        if (backfilledEnabled > 0L) {
            logger.info { "[Neo4jSchemaInitializer] Backfilled enabled=false on $backfilledEnabled AgentConfig node(s)" }
        }
    }

    companion object : KLogging()
}
