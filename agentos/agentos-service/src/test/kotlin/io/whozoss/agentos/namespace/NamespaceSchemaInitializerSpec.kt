package io.whozoss.agentos.namespace

import io.kotest.core.spec.style.StringSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
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
 * Integration test for [NamespaceSchemaInitializer].
 *
 * Verifies that Namespace nodes missing `removed` (created before the field was made
 * non-nullable) are backfilled to `removed = false` at startup.
 */
@SpringBootTest
@ActiveProfiles("test", "embedded-neo4j")
@Import(EmbeddedNeo4jTestConfiguration::class)
class NamespaceSchemaInitializerSpec : StringSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired lateinit var neo4jClient: Neo4jClient
    @Autowired lateinit var driver: Driver

    private fun runInitializer() {
        NamespaceSchemaInitializer(neo4jClient).run(object : ApplicationArguments {
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

        "backfill sets removed=false on Namespace node missing the property" {
            val nsId = UUID.randomUUID()
            neo4jClient.query(
                "CREATE (n:Namespace {id: \$id, name: 'legacy-ns'})"
            ).bindAll(mapOf("id" to nsId.toString())).run()

            neo4jClient.query("MATCH (n:Namespace {id: \$id}) RETURN n.removed IS NULL AS missing")
                .bindAll(mapOf("id" to nsId.toString()))
                .fetchAs(Boolean::class.java).mappedBy { _, r -> r["missing"].asBoolean() }
                .one().orElse(false) shouldBe true

            runInitializer()

            neo4jClient.query("MATCH (n:Namespace {id: \$id}) RETURN n.removed AS removed")
                .bindAll(mapOf("id" to nsId.toString()))
                .fetchAs(Boolean::class.java).mappedBy { _, r -> r["removed"].asBoolean() }
                .one().orElse(null) shouldBe false
        }

        "backfill does not overwrite removed=true on soft-deleted Namespace node" {
            val nsId = UUID.randomUUID()
            neo4jClient.query(
                "CREATE (n:Namespace {id: \$id, name: 'deleted-ns', removed: true})"
            ).bindAll(mapOf("id" to nsId.toString())).run()

            runInitializer()

            neo4jClient.query("MATCH (n:Namespace {id: \$id}) RETURN n.removed AS removed")
                .bindAll(mapOf("id" to nsId.toString()))
                .fetchAs(Boolean::class.java).mappedBy { _, r -> r["removed"].asBoolean() }
                .one().orElse(null) shouldBe true
        }

        "backfill is idempotent: running twice does not change values" {
            val nsId = UUID.randomUUID()
            neo4jClient.query(
                "CREATE (n:Namespace {id: \$id, name: 'legacy-ns'})"
            ).bindAll(mapOf("id" to nsId.toString())).run()

            runInitializer()
            runInitializer()

            neo4jClient.query("MATCH (n:Namespace {id: \$id}) RETURN n.removed AS removed")
                .bindAll(mapOf("id" to nsId.toString()))
                .fetchAs(Boolean::class.java).mappedBy { _, r -> r["removed"].asBoolean() }
                .one().orElse(null) shouldBe false
        }
    }
}
