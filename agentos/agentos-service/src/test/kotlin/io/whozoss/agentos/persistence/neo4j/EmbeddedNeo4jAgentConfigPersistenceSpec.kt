package io.whozoss.agentos.persistence.neo4j

import io.whozoss.agentos.config.TestAuditConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles

/**
 * Runs the [AbstractAgentConfigPersistenceSpec] contract against the embedded Neo4j
 * test harness (no Docker required).
 */
@SpringBootTest
@ActiveProfiles("test", "embedded-neo4j")
@Import(EmbeddedNeo4jTestConfiguration::class, TestAuditConfiguration::class)
class EmbeddedNeo4jAgentConfigPersistenceSpec : AbstractAgentConfigPersistenceSpec()
