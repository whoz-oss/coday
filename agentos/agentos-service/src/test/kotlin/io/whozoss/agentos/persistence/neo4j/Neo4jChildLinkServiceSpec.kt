package io.whozoss.agentos.persistence.neo4j

import io.kotest.core.spec.style.StringSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import io.whozoss.agentos.persistence.Neo4jChildLinkService
import org.neo4j.driver.Driver
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles

/**
 * Direct integration tests for [Neo4jChildLinkService] against the embedded Neo4j harness.
 *
 * Companion to the transitive coverage in `Abstract*PersistenceSpec` (which exercises
 * [Neo4jChildLinkService.link] indirectly through `repo.save(...)` followed by
 * `findByParent` queries that depend on the BELONGS_TO edge). This spec asserts the
 * properties no indirect test reaches:
 * - parent node properties are not mutated by `link()` (the bug that motivated the
 *   manual Cypher pattern; SDN's `@Relationship` would otherwise overwrite them)
 * - `MERGE` is idempotent — calling `link()` twice yields a single edge
 * - the configurable `relationship` parameter is honoured (CREATED_BY isolated from BELONGS_TO)
 * - the operation is a no-op (no edge created) when either endpoint is missing
 */
@SpringBootTest
@ActiveProfiles("test", "embedded-neo4j")
@Import(EmbeddedNeo4jTestConfiguration::class)
class Neo4jChildLinkServiceSpec(
    @Autowired private val service: Neo4jChildLinkService,
    @Autowired private val driver: Driver,
) : StringSpec() {
    override fun extensions() = listOf(SpringExtension)

    init {
        beforeEach { Neo4jContainerSupport.clearDatabase(driver) }

        "creates a BELONGS_TO edge between two existing nodes" {
            seedNode("Namespace", "ns-1", "name" to "Engineering")
            seedNode("AgentConfig", "agent-1")

            service.link("AgentConfig", "agent-1", "Namespace", "ns-1")

            edgeExists("AgentConfig", "agent-1", "Namespace", "ns-1", "BELONGS_TO") shouldBe true
        }

        "preserves parent node properties (no SDN-style stub overwrite)" {
            seedNode("Namespace", "ns-1", "name" to "Engineering", "description" to "All engineers")
            seedNode("AgentConfig", "agent-1")

            service.link("AgentConfig", "agent-1", "Namespace", "ns-1")

            nodeProperty("Namespace", "ns-1", "name") shouldBe "Engineering"
            nodeProperty("Namespace", "ns-1", "description") shouldBe "All engineers"
        }

        "MERGE is idempotent — calling link twice creates a single edge" {
            seedNode("Namespace", "ns-1", "name" to "X")
            seedNode("AgentConfig", "agent-1")

            service.link("AgentConfig", "agent-1", "Namespace", "ns-1")
            service.link("AgentConfig", "agent-1", "Namespace", "ns-1")

            edgeCount("AgentConfig", "agent-1", "Namespace", "ns-1", "BELONGS_TO") shouldBe 1
        }

        "uses the configured relationship type (CREATED_BY for Case to User)" {
            seedNode("User", "user-1", "externalId" to "selim")
            seedNode("Case", "case-1", "title" to "T")

            service.link("Case", "case-1", "User", "user-1", relationship = "CREATED_BY")

            edgeExists("Case", "case-1", "User", "user-1", "CREATED_BY") shouldBe true
            // Confirms BELONGS_TO is NOT created by mistake when relationship is overridden
            edgeExists("Case", "case-1", "User", "user-1", "BELONGS_TO") shouldBe false
        }

        "is a no-op when the child node does not exist" {
            seedNode("Namespace", "ns-1", "name" to "X")
            // No seed for AgentConfig

            service.link("AgentConfig", "missing-id", "Namespace", "ns-1")

            edgeExists("AgentConfig", "missing-id", "Namespace", "ns-1", "BELONGS_TO") shouldBe false
        }

        "is a no-op when the parent node does not exist" {
            seedNode("AgentConfig", "agent-1")

            service.link("AgentConfig", "agent-1", "Namespace", "missing-id")

            edgeExists("AgentConfig", "agent-1", "Namespace", "missing-id", "BELONGS_TO") shouldBe false
        }
    }

    // -------------------------------------------------------------------------
    // Cypher helpers — local to this spec
    // -------------------------------------------------------------------------

    private fun seedNode(label: String, id: String, vararg props: Pair<String, Any>) {
        val allProps = listOf("id" to id) + props
        val propsCypher = allProps.joinToString(", ") { (k, _) -> "$k: \$$k" }
        driver.session().use {
            it.run("CREATE (n:`$label` {$propsCypher})", allProps.toMap())
        }
    }

    private fun edgeExists(
        childLabel: String,
        childId: String,
        parentLabel: String,
        parentId: String,
        rel: String,
    ): Boolean = edgeCount(childLabel, childId, parentLabel, parentId, rel) > 0

    private fun edgeCount(
        childLabel: String,
        childId: String,
        parentLabel: String,
        parentId: String,
        rel: String,
    ): Int = driver.session().use {
        it.run(
            """
            MATCH (c:`$childLabel` {id: ${'$'}childId})
                  -[r:`$rel`]->
                  (p:`$parentLabel` {id: ${'$'}parentId})
            RETURN count(r) AS n
            """.trimIndent(),
            mapOf("childId" to childId, "parentId" to parentId),
        ).single().get("n").asInt()
    }

    private fun nodeProperty(label: String, id: String, prop: String): String? =
        driver.session().use {
            val record = it.run(
                "MATCH (n:`$label` {id: \$id}) RETURN n.`$prop` AS v",
                mapOf("id" to id),
            ).single()
            if (record.get("v").isNull) null else record.get("v").asString()
        }
}
