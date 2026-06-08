package io.whozoss.agentos.config

import io.kotest.core.spec.style.StringSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import io.whozoss.agentos.agentConfig.AgentConfig
import io.whozoss.agentos.agentConfig.AgentConfigRepository
import io.whozoss.agentos.persistence.neo4j.EmbeddedNeo4jTestConfiguration
import io.whozoss.agentos.sdk.entity.EntityMetadata
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.data.neo4j.core.Neo4jClient
import org.springframework.test.context.ActiveProfiles
import java.util.UUID

/**
 * Integration test for [Neo4jSchemaInitializer].
 *
 * Verifies that AgentConfig nodes without a version property (created before @Version
 * was introduced) are backfilled with version=0 on startup, so that Spring Data Neo4j's
 * optimistic-locking check does not fail on the first update.
 */
@SpringBootTest
@ActiveProfiles("test", "embedded-neo4j")
@Import(EmbeddedNeo4jTestConfiguration::class)
class Neo4jSchemaInitializerSpec : StringSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired lateinit var neo4jClient: Neo4jClient
    @Autowired lateinit var agentConfigRepository: AgentConfigRepository

    init {
        "backfill allows update on legacy AgentConfig node without version property" {
            val namespaceId = UUID.randomUUID()
            val agentId = UUID.randomUUID()

            // Simulate a legacy node: insert via raw Cypher without version property
            neo4jClient.query(
                "CREATE (a:AgentConfig {id: \$id, namespaceId: \$namespaceId, name: 'legacy-agent', removed: false})"
            ).bindAll(mapOf("id" to agentId.toString(), "namespaceId" to namespaceId.toString())).run()

            // Verify node exists without version
            val hasVersion = neo4jClient.query(
                "MATCH (a:AgentConfig {id: \$id}) RETURN a.version IS NOT NULL AS hasVersion"
            ).bindAll(mapOf("id" to agentId.toString()))
                .fetchAs(Boolean::class.java)
                .mappedBy { _, record -> record["hasVersion"].asBoolean() }
                .one().orElse(false)
            hasVersion shouldBe false

            // Run the backfill
            val initializer = Neo4jSchemaInitializer(neo4jClient)
            initializer.run(object : org.springframework.boot.ApplicationArguments {
                override fun getSourceArgs() = emptyArray<String>()
                override fun getNonOptionArgs() = emptyList<String>()
                override fun getOptionNames() = emptySet<String>()
                override fun containsOption(name: String) = false
                override fun getOptionValues(name: String) = emptyList<String>()
            })

            // Verify version is now 0 after backfill
            val versionAfterBackfill = neo4jClient.query(
                "MATCH (a:AgentConfig {id: \$id}) RETURN a.version AS version"
            ).bindAll(mapOf("id" to agentId.toString()))
                .fetchAs(Long::class.java)
                .mappedBy { _, record -> record["version"].asLong() }
                .one().orElse(null)
            versionAfterBackfill shouldBe 0L

            // Load via repository and update — should not throw OptimisticLockingFailureException
            val loaded = agentConfigRepository.findByIds(listOf(agentId)).first()
            loaded.metadata.version shouldBe 0L

            val saved = agentConfigRepository.save(loaded.copy(name = "updated-agent"))
            saved.name shouldBe "updated-agent"
            saved.metadata.version shouldBe 1L
        }
    }
}
