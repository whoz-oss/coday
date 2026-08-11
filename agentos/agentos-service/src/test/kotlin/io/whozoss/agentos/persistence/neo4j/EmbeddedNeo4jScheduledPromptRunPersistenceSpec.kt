package io.whozoss.agentos.persistence.neo4j

import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles

/**
 * Runs the [AbstractScheduledPromptRunPersistenceSpec] contract against the embedded Neo4j
 * engine using the Neo4j test harness (`embedded-neo4j` persistence mode).
 *
 * Introduced after a production bug where `r.status NOT IN [...]` (invalid Cypher)
 * was used instead of `NOT r.status IN [...]` in [io.whozoss.agentos.scheduledPrompt.ScheduledPromptRunNodeNeo4jRepository.updateStatus].
 * Any Cypher syntax error in the query causes the Spring context to fail at startup, so
 * this test acts as an early-detection guard for query regressions.
 */
@SpringBootTest
@ActiveProfiles("test", "embedded-neo4j")
@Import(EmbeddedNeo4jTestConfiguration::class)
class EmbeddedNeo4jScheduledPromptRunPersistenceSpec : AbstractScheduledPromptRunPersistenceSpec()
