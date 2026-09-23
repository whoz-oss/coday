package io.whozoss.agentos.persistence.neo4j

import io.whozoss.agentos.config.TestAuditConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles

/**
 * Runs the [AbstractScheduledPromptPersistenceSpec] contract against the embedded Neo4j
 * test harness (no Docker required).
 *
 * Introduced to catch the delta-sync regression where raw-Cypher mutation methods
 * ([io.whozoss.agentos.scheduledPrompt.ScheduledPromptNodeNeo4jRepository.softDeleteWithPromptsByAgentConfigId],
 * [io.whozoss.agentos.scheduledPrompt.ScheduledPromptNodeNeo4jRepository.disableByAgentConfigId])
 * bypassed `@LastModifiedDate` and left `sp.modified` stale, making the affected prompts
 * permanently invisible to any client polling with a `updatedSince` cursor.
 */
@SpringBootTest
@ActiveProfiles("test", "embedded-neo4j")
@Import(EmbeddedNeo4jTestConfiguration::class, TestAuditConfiguration::class)
class EmbeddedNeo4jScheduledPromptPersistenceSpec : AbstractScheduledPromptPersistenceSpec()
