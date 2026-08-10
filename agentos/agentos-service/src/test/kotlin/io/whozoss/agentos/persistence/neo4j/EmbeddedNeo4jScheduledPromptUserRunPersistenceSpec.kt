package io.whozoss.agentos.persistence.neo4j

import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles

/**
 * Runs the [AbstractScheduledPromptUserRunPersistenceSpec] contract against the embedded Neo4j
 * engine using the Neo4j test harness (`embedded-neo4j` persistence mode).
 *
 * Verifies the full Cypher contract for [io.whozoss.agentos.scheduledPrompt.ScheduledPromptUserRunRepository]
 * end-to-end, including the deployment-graph traversal in [io.whozoss.agentos.scheduledPrompt.ScheduledPromptUserRunRepository.materialize]
 * and the lease-based crash-recovery in [io.whozoss.agentos.scheduledPrompt.ScheduledPromptUserRunRepository.claimBatch].
 */
@SpringBootTest
@ActiveProfiles("test", "embedded-neo4j")
@Import(EmbeddedNeo4jTestConfiguration::class)
class EmbeddedNeo4jScheduledPromptUserRunPersistenceSpec : AbstractScheduledPromptUserRunPersistenceSpec()
