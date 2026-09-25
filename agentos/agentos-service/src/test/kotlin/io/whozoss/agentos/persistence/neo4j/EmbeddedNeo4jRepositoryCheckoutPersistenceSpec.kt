package io.whozoss.agentos.persistence.neo4j

import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.spec.style.StringSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import io.whozoss.agentos.git.RepositoryCheckout
import io.whozoss.agentos.git.RepositoryCheckoutService
import io.whozoss.agentos.git.RepositoryCheckoutStatus
import io.whozoss.agentos.namespace.Namespace
import io.whozoss.agentos.namespace.NamespaceRepository
import org.neo4j.driver.Driver
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import java.util.UUID

@SpringBootTest
@ActiveProfiles("test", "embedded-neo4j")
@Import(EmbeddedNeo4jTestConfiguration::class)
class EmbeddedNeo4jRepositoryCheckoutPersistenceSpec : StringSpec() {
    override fun extensions() = listOf(SpringExtension)
    @Autowired lateinit var namespaces: NamespaceRepository
    @Autowired lateinit var checkouts: RepositoryCheckoutService
    @Autowired lateinit var driver: Driver
    init {
        beforeEach { Neo4jContainerSupport.clearDatabase(driver) }
        "checkout readiness and failure are persisted and linked to the namespace" {
            val namespace = namespaces.save(Namespace(name = "checkout"))
            val checkout = checkouts.create(RepositoryCheckout(namespaceId = namespace.id,
                integrationConfigId = UUID.randomUUID(), repositoryUrl = "https://example.com/repo.git", mainBranch = "main"))
            checkouts.findByNamespaceId(namespace.id) shouldBe checkout
            checkouts.findByParent(namespace.id).map { it.id } shouldBe listOf(checkout.id)
            checkouts.markStatus(checkout.id, RepositoryCheckoutStatus.FAILED, "clone failed")
            checkouts.findByStatusIn(listOf(RepositoryCheckoutStatus.FAILED), 10).single().failureReason shouldBe "clone failed"
            val ready = checkouts.markStatus(checkout.id, RepositoryCheckoutStatus.READY)
            checkouts.findByNamespaceId(namespace.id) shouldBe ready
            (ready.lastFetchedAt != null) shouldBe true
            ready.failureReason shouldBe null
            driver.session().use { session ->
                session.run("MATCH (:RepositoryCheckout {id: \$id})-[:BELONGS_TO]->(:Namespace {id: \$ns}) RETURN count(*) AS n",
                    mapOf("id" to checkout.id.toString(), "ns" to namespace.id.toString())).single()["n"].asInt() shouldBe 1
            }
        }
        "the database refuses two active checkouts and frees the slot after soft deletion" {
            val namespace = namespaces.save(Namespace(name = "unique-checkout"))
            fun value() = RepositoryCheckout(namespaceId = namespace.id, integrationConfigId = UUID.randomUUID(),
                repositoryUrl = "https://example.com/repo.git", mainBranch = "main")
            val original = checkouts.create(value())
            shouldThrowAny { checkouts.create(value()) }
            checkouts.delete(original.id) shouldBe true
            checkouts.findByIds(listOf(original.id), withRemoved = true).single().metadata.removed shouldBe true
            val replacement = checkouts.create(value())
            checkouts.findByNamespaceId(namespace.id)?.id shouldBe replacement.id
        }
    }
}
