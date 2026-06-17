package io.whozoss.agentos.config

import io.kotest.core.spec.style.StringSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import io.whozoss.agentos.agentConfig.AgentConfigRepository
import io.whozoss.agentos.agentConfig.AgentConfigSchemaInitializer
import io.whozoss.agentos.persistence.neo4j.EmbeddedNeo4jTestConfiguration
import org.neo4j.driver.Driver
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.data.neo4j.core.Neo4jClient
import org.springframework.test.context.ActiveProfiles
import java.util.UUID

/**
 * Integration test for [AgentConfigSchemaInitializer].
 *
 * Verifies that AgentConfig nodes missing `version`, `enabled`, or `removed`
 * (created before those fields were introduced) are backfilled at startup:
 * - `version = 0`  so Spring Data Neo4j's optimistic-locking check does not fail
 * - `enabled = false` so Cypher queries can use `a.enabled` directly without COALESCE
 * - `removed = false` now that the field is non-nullable on [io.whozoss.agentos.agentConfig.AgentConfigNode]
 */
@SpringBootTest
@ActiveProfiles("test", "embedded-neo4j")
@Import(EmbeddedNeo4jTestConfiguration::class)
class Neo4jSchemaInitializerSpec : StringSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired lateinit var neo4jClient: Neo4jClient
    @Autowired lateinit var agentConfigRepository: AgentConfigRepository
    @Autowired lateinit var driver: Driver

    private fun runInitializer() {
        AgentConfigSchemaInitializer(neo4jClient).run(object : ApplicationArguments {
            override fun getSourceArgs() = emptyArray<String>()
            override fun getNonOptionArgs() = emptyList<String>()
            override fun getOptionNames() = emptySet<String>()
            override fun containsOption(name: String) = false
            override fun getOptionValues(name: String) = emptyList<String>()
        })
    }

    private fun clearDatabase() {
        driver.session().use { it.run("MATCH (n) DETACH DELETE n") }
    }

    init {
        beforeEach { clearDatabase() }

        "backfill sets version=0 and enabled=false on legacy AgentConfig node" {
            val namespaceId = UUID.randomUUID()
            val agentId = UUID.randomUUID()
            neo4jClient.query(
                "CREATE (a:AgentConfig {id: \$id, namespaceId: \$nsId, name: 'legacy', removed: false})"
            ).bindAll(mapOf("id" to agentId.toString(), "nsId" to namespaceId.toString())).run()

            neo4jClient.query("MATCH (a:AgentConfig {id: \$id}) RETURN a.version IS NOT NULL AS v")
                .bindAll(mapOf("id" to agentId.toString()))
                .fetchAs(Boolean::class.java).mappedBy { _, r -> r["v"].asBoolean() }
                .one().orElse(false) shouldBe false

            neo4jClient.query("MATCH (a:AgentConfig {id: \$id}) RETURN a.enabled IS NOT NULL AS e")
                .bindAll(mapOf("id" to agentId.toString()))
                .fetchAs(Boolean::class.java).mappedBy { _, r -> r["e"].asBoolean() }
                .one().orElse(false) shouldBe false

            runInitializer()

            neo4jClient.query("MATCH (a:AgentConfig {id: \$id}) RETURN a.version AS version")
                .bindAll(mapOf("id" to agentId.toString()))
                .fetchAs(Long::class.java).mappedBy { _, r -> r["version"].asLong() }
                .one().orElse(null) shouldBe 0L

            neo4jClient.query("MATCH (a:AgentConfig {id: \$id}) RETURN a.enabled AS enabled")
                .bindAll(mapOf("id" to agentId.toString()))
                .fetchAs(Boolean::class.java).mappedBy { _, r -> r["enabled"].asBoolean() }
                .one().orElse(null) shouldBe false

            val loaded = agentConfigRepository.findByIds(listOf(agentId)).first()
            loaded.metadata.version shouldBe 0L
            val saved = agentConfigRepository.save(loaded.copy(name = "updated"))
            saved.name shouldBe "updated"
            saved.metadata.version shouldBe 1L
        }

        "backfill sets removed=false on AgentConfig node missing the property" {
            val agentId = UUID.randomUUID()
            val namespaceId = UUID.randomUUID()
            neo4jClient.query(
                "CREATE (a:AgentConfig {id: \$id, namespaceId: \$nsId, name: 'legacy', version: 0, enabled: false})"
            ).bindAll(mapOf("id" to agentId.toString(), "nsId" to namespaceId.toString())).run()

            neo4jClient.query("MATCH (a:AgentConfig {id: \$id}) RETURN a.removed IS NULL AS missing")
                .bindAll(mapOf("id" to agentId.toString()))
                .fetchAs(Boolean::class.java).mappedBy { _, r -> r["missing"].asBoolean() }
                .one().orElse(false) shouldBe true

            runInitializer()

            neo4jClient.query("MATCH (a:AgentConfig {id: \$id}) RETURN a.removed AS removed")
                .bindAll(mapOf("id" to agentId.toString()))
                .fetchAs(Boolean::class.java).mappedBy { _, r -> r["removed"].asBoolean() }
                .one().orElse(null) shouldBe false
        }

        "backfill is idempotent: running twice does not change values" {
            val agentId = UUID.randomUUID()
            val namespaceId = UUID.randomUUID()
            neo4jClient.query(
                "CREATE (a:AgentConfig {id: \$id, namespaceId: \$nsId, name: 'legacy'})"
            ).bindAll(mapOf("id" to agentId.toString(), "nsId" to namespaceId.toString())).run()

            runInitializer()
            runInitializer()

            neo4jClient.query("MATCH (a:AgentConfig {id: \$id}) RETURN a.version AS v")
                .bindAll(mapOf("id" to agentId.toString()))
                .fetchAs(Long::class.java).mappedBy { _, r -> r["v"].asLong() }
                .one().orElse(null) shouldBe 0L
        }
    }
}
