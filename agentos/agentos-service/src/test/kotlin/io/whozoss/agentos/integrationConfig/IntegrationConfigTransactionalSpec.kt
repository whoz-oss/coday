package io.whozoss.agentos.integrationConfig

import com.ninjasquad.springmockk.SpykBean
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.verify
import io.whozoss.agentos.namespace.Namespace
import io.whozoss.agentos.namespace.NamespaceRepository
import io.whozoss.agentos.persistence.Neo4jChildLinkService
import io.whozoss.agentos.persistence.neo4j.EmbeddedNeo4jTestConfiguration
import io.whozoss.agentos.persistence.neo4j.Neo4jContainerSupport
import io.whozoss.agentos.sdk.entity.EntityMetadata
import org.neo4j.driver.Driver
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import java.util.UUID

/**
 * Transactional integration tests for [Neo4jIntegrationConfigRepository] (story 6.1, AC9).
 *
 * Validates that:
 *  1. A successful `save` materialises both the entity node AND the
 *     `(:IntegrationConfig)-[:BELONGS_TO]->(:Namespace)` edge in the same Neo4j transaction.
 *  2. When [Neo4jChildLinkService.link] throws mid-write, the transactional boundary on
 *     [Neo4jIntegrationConfigRepository.save] rolls back the entity write — no orphan
 *     IntegrationConfig node remains (NFR-REL-3, fail-closed posture).
 *
 * Uses `@SpykBean` so most calls go through the real bean; the failure case substitutes the
 * `link` invocation with a thrown exception.
 */
@SpringBootTest
@ActiveProfiles("test", "embedded-neo4j")
@Import(EmbeddedNeo4jTestConfiguration::class)
class IntegrationConfigTransactionalSpec : StringSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    lateinit var repo: IntegrationConfigRepository

    @Autowired
    lateinit var namespaceRepo: NamespaceRepository

    @Autowired
    lateinit var driver: Driver

    @SpykBean
    lateinit var childLinkService: Neo4jChildLinkService

    private fun namespace() = Namespace(metadata = EntityMetadata(), name = "tx-spec-ns")

    private fun config(namespaceId: UUID) =
        IntegrationConfig(
            metadata = EntityMetadata(),
            namespaceId = namespaceId,
            userId = null,
            name = "JIRA",
            integrationType = "JIRA",
        )

    init {
        beforeEach { Neo4jContainerSupport.clearDatabase(driver) }

        "create flow materialises both the entity node and the BELONGS_TO edge" {
            val ns = namespaceRepo.save(namespace())
            val saved = repo.save(config(ns.id))

            // Round-trip through the repo confirms the entity is queryable via the BELONGS_TO traversal
            val byParent = repo.findByParent(ns.id)
            byParent shouldHaveSize 1
            byParent.first().id shouldBe saved.id

            // Edge presence is verified directly to rule out a code path that returns the entity
            // without persisting the relationship
            driver.session().use { session ->
                val edgeCount = session.run(
                    """
                    MATCH (c:IntegrationConfig {id: ${'$'}cid})-[:BELONGS_TO]->(ns:Namespace {id: ${'$'}nsid})
                    RETURN count(*) AS n
                    """.trimIndent(),
                    mapOf("cid" to saved.id.toString(), "nsid" to ns.id.toString()),
                ).single().get("n").asLong()
                edgeCount shouldBe 1L
            }

            verify(exactly = 1) {
                childLinkService.link("IntegrationConfig", saved.id.toString(), "Namespace", ns.id.toString())
            }
        }

        "transient failure during link rolls back the entity write — entity was persisted then rolled back" {
            val ns = namespaceRepo.save(namespace())

            // Capture the id passed to link(...). This id only exists if neo4jRepository.save
            // already returned a persisted node — link is invoked from save's `.also { }` step.
            // Capturing it proves the SDN write attempt completed before the fault fired,
            // so the post-rollback "0 nodes" assertion below distinguishes rollback from
            // "save was never reached".
            val capturedChildIds = mutableListOf<String>()
            every {
                childLinkService.link("IntegrationConfig", any(), "Namespace", ns.id.toString())
            } answers {
                capturedChildIds += secondArg<String>()
                throw RuntimeException("simulated transient Neo4j failure after entity persist")
            }

            shouldThrow<RuntimeException> {
                repo.save(config(ns.id))
            }

            // Pre-condition for the rollback claim: the entity write reached SDN and produced
            // an id (otherwise the spy would never have been invoked).
            capturedChildIds shouldHaveSize 1

            // Post-condition: the specific id we observed mid-flight is gone. Combined with
            // the pre-condition, this proves the transactional boundary on save() rolled back
            // a write that had already happened, rather than the test being fooled by SDN
            // skipping the write entirely.
            driver.session().use { session ->
                val orphanCount = session.run(
                    "MATCH (c:IntegrationConfig {id: ${'$'}cid}) RETURN count(c) AS n",
                    mapOf("cid" to capturedChildIds.first()),
                ).single().get("n").asLong()
                orphanCount shouldBe 0L
            }
        }
    }
}
