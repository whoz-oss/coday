package io.whozoss.agentos.persistence.neo4j

import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles

/**
 * Runs the [AbstractUsageRecordPersistenceSpec] contract against the embedded Neo4j
 * engine using the Neo4j test harness (`embedded-neo4j` persistence mode).
 *
 * No Docker, no Testcontainers — the harness starts and stops with the Spring context.
 * This catches Cypher query bugs (aggregation nesting, ORDER BY, null-cost contamination)
 * that the in-memory [io.whozoss.agentos.usage.InMemoryUsageRecordRepository] cannot detect.
 */
@SpringBootTest
@ActiveProfiles("test", "embedded-neo4j")
@Import(EmbeddedNeo4jTestConfiguration::class)
class EmbeddedNeo4jUsageRecordPersistenceSpec : AbstractUsageRecordPersistenceSpec()
