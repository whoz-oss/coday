package io.whozoss.agentos.persistence.neo4j

import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles

/**
 * Runs the [AbstractIntegrationConfigPersistenceSpec] contract against the embedded
 * Neo4j engine using the Neo4j test harness (`embedded-neo4j` persistence mode).
 *
 * No Docker, no Testcontainers — the harness starts and stops with the Spring context.
 */
@SpringBootTest
@ActiveProfiles("test", "embedded-neo4j")
@Import(EmbeddedNeo4jTestConfiguration::class)
class EmbeddedNeo4jIntegrationConfigPersistenceSpec : AbstractIntegrationConfigPersistenceSpec()
