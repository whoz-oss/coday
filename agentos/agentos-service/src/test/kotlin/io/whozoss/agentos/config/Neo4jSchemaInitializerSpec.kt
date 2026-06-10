package io.whozoss.agentos.config

import io.kotest.core.spec.style.StringSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import io.whozoss.agentos.agentConfig.AgentConfigRepository
import io.whozoss.agentos.persistence.neo4j.EmbeddedNeo4jTestConfiguration
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.data.neo4j.core.Neo4jClient
import org.springframework.test.context.ActiveProfiles
import java.util.UUID

/**
 * Integration test for [Neo4jSchemaInitializer].
 *
 * Verifies that AgentConfig nodes without `version` or `enabled` properties
 * (created before those fields were introduced) are backfilled at startup:
 * - `version = 0`  so Spring Data Neo4j's optimistic-locking check does not fail
 * - `enabled = false` so Cypher queries can use `a.enabled` directly without COALESCE
 */
@SpringBootTest
@ActiveProfiles("test", "embedded-neo4j")
@Import(EmbeddedNeo4jTestConfiguration::class)
class Neo4jSchemaInitializerSpec : StringSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired lateinit var neo4jClient: Neo4jClient
    @Autowired lateinit var agentConfigRepository: AgentConfigRepository

    private fun runInitializer() {
        Neo4jSchemaInitializer(neo4jClient).run(object : org.springframework.boot.ApplicationArguments {
            override fun getSourceArgs() = emptyArray<String>()
            override fun getNonOptionArgs() = emptyList<String>()
            override fun getOptionNames() = emptySet<String>()
            override fun containsOption(name: String) = false
            override fun getOptionValues(name: String) = emptyList<String>()
        })
    }

    private fun createLegacyNode(agentId: UUID, namespaceId: UUID) {
        neo4jClient.query(
            "CREATE (a:AgentConfig {id: \$id, namespaceId: \$namespaceId, name: 'legacy-agent', removed: false})"
        ).bindAll(mapOf("id" to agentId.toString(), "namespaceId" to namespaceId.toString())).run()
    }

    init {
        "backfill sets version=0 and enabled=false on legacy AgentConfig node" {
            val namespaceId = UUID.randomUUID()
            val agentId = UUID.randomUUID()

            createLegacyNode(agentId, namespaceId)

            // Verify node has neither version nor enabled before backfill
            neo4jClient.query(
                "MATCH (a:AgentConfig {id: \$id}) RETURN a.version IS NOT NULL AS hasVersion"
            ).bindAll(mapOf("id" to agentId.toString()))
                .fetchAs(Boolean::class.java)
                .mappedBy { _, record -> record["hasVersion"].asBoolean() }
                .one().orElse(false) shouldBe false

            neo4jClient.query(
                "MATCH (a:AgentConfig {id: \$id}) RETURN a.enabled IS NOT NULL AS hasEnabled"
            ).bindAll(mapOf("id" to agentId.toString()))
                .fetchAs(Boolean::class.java)
                .mappedBy { _, record -> record["hasEnabled"].asBoolean() }
                .one().orElse(false) shouldBe false

            runInitializer()

            // version backfilled to 0
            neo4jClient.query(
                "MATCH (a:AgentConfig {id: \$id}) RETURN a.version AS version"
            ).bindAll(mapOf("id" to agentId.toString()))
                .fetchAs(Long::class.java)
                .mappedBy { _, record -> record["version"].asLong() }
                .one().orElse(null) shouldBe 0L

            // enabled backfilled to false
            neo4jClient.query(
                "MATCH (a:AgentConfig {id: \$id}) RETURN a.enabled AS enabled"
            ).bindAll(mapOf("id" to agentId.toString()))
                .fetchAs(Boolean::class.java)
                .mappedBy { _, record -> record["enabled"].asBoolean() }
                .one().orElse(null) shouldBe false

            // Load via repository and update — should not throw OptimisticLockingFailureException
            val loaded = agentConfigRepository.findByIds(listOf(agentId)).first()
            loaded.metadata.version shouldBe 0L

            val saved = agentConfigRepository.save(loaded.copy(name = "updated-agent"))
            saved.name shouldBe "updated-agent"
            saved.metadata.version shouldBe 1L
        }
    }
}
