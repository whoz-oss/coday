package io.whozoss.agentos.persistence.neo4j

import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles

/**
 * Runs the [AbstractNamespacePersistenceSpec] contract against the embedded Neo4j
 * engine using the Neo4j test harness (`embedded-neo4j` persistence mode).
 *
 * The `embedded-neo4j` profile sets `agentos.persistence.mode=embedded-neo4j`,
 * which activates [io.whozoss.agentos.config.Neo4jPersistenceConfiguration] for
 * the repositories. [EmbeddedNeo4jTestConfiguration] overrides the [Driver] bean
 * with a harness-based one, avoiding the Netty version conflict that arises when
 * the full embedded engine tries to start BoltServer in tests.
 *
 * No Docker, no Testcontainers — the harness starts and stops with the Spring context.
 */
@SpringBootTest
@ActiveProfiles("test", "embedded-neo4j")
@Import(EmbeddedNeo4jTestConfiguration::class)
class EmbeddedNeo4jNamespacePersistenceSpec : AbstractNamespacePersistenceSpec()
