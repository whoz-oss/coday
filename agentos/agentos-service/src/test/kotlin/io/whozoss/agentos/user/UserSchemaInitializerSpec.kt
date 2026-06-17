package io.whozoss.agentos.user

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
 * Integration test for [UserSchemaInitializer].
 *
 * Verifies that User nodes missing `removed` (created before the field was made
 * non-nullable) are backfilled to `removed = false` at startup.
 */
@SpringBootTest
@ActiveProfiles("test", "embedded-neo4j")
@Import(EmbeddedNeo4jTestConfiguration::class)
class UserSchemaInitializerSpec : StringSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired lateinit var neo4jClient: Neo4jClient
    @Autowired lateinit var driver: Driver

    private fun runInitializer() {
        UserSchemaInitializer(neo4jClient).run(object : ApplicationArguments {
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

        "backfill sets removed=false on User node missing the property" {
            val userId = UUID.randomUUID()
            neo4jClient.query(
                "CREATE (u:User {id: \$id, externalId: 'ext-1', email: 'a@b.com'})"
            ).bindAll(mapOf("id" to userId.toString())).run()

            neo4jClient.query("MATCH (u:User {id: \$id}) RETURN u.removed IS NULL AS missing")
                .bindAll(mapOf("id" to userId.toString()))
                .fetchAs(Boolean::class.java).mappedBy { _, r -> r["missing"].asBoolean() }
                .one().orElse(false) shouldBe true

            runInitializer()

            neo4jClient.query("MATCH (u:User {id: \$id}) RETURN u.removed AS removed")
                .bindAll(mapOf("id" to userId.toString()))
                .fetchAs(Boolean::class.java).mappedBy { _, r -> r["removed"].asBoolean() }
                .one().orElse(null) shouldBe false
        }

        "backfill does not overwrite removed=true on soft-deleted User node" {
            val userId = UUID.randomUUID()
            neo4jClient.query(
                "CREATE (u:User {id: \$id, externalId: 'ext-2', email: 'b@c.com', removed: true})"
            ).bindAll(mapOf("id" to userId.toString())).run()

            runInitializer()

            neo4jClient.query("MATCH (u:User {id: \$id}) RETURN u.removed AS removed")
                .bindAll(mapOf("id" to userId.toString()))
                .fetchAs(Boolean::class.java).mappedBy { _, r -> r["removed"].asBoolean() }
                .one().orElse(null) shouldBe true
        }

        "backfill is idempotent: running twice does not change values" {
            val userId = UUID.randomUUID()
            neo4jClient.query(
                "CREATE (u:User {id: \$id, externalId: 'ext-3', email: 'c@d.com'})"
            ).bindAll(mapOf("id" to userId.toString())).run()

            runInitializer()
            runInitializer()

            neo4jClient.query("MATCH (u:User {id: \$id}) RETURN u.removed AS removed")
                .bindAll(mapOf("id" to userId.toString()))
                .fetchAs(Boolean::class.java).mappedBy { _, r -> r["removed"].asBoolean() }
                .one().orElse(null) shouldBe false
        }
    }
}
