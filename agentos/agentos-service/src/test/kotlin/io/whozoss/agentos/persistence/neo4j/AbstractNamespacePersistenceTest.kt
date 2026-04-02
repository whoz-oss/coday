package io.whozoss.agentos.persistence.neo4j

import io.kotest.core.spec.style.StringSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.whozoss.agentos.namespace.Namespace
import io.whozoss.agentos.namespace.NamespaceRepository
import io.whozoss.agentos.namespace.NamespaceRepository.Companion.NAMESPACE_PARENT_KEY
import io.whozoss.agentos.sdk.entity.EntityMetadata
import org.neo4j.driver.Driver
import org.springframework.beans.factory.annotation.Autowired

/**
 * Shared namespace persistence contract tests.
 *
 * Subclasses activate a specific persistence mode (Testcontainers or embedded)
 * and inherit all test cases, ensuring both modes satisfy the same contract.
 */
abstract class AbstractNamespacePersistenceTest : StringSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    lateinit var repo: NamespaceRepository

    @Autowired
    lateinit var driver: Driver

    init {
        beforeEach { Neo4jContainerSupport.clearDatabase(driver) }

        "save and findByIds returns the same namespace" {
            val ns = Namespace(metadata = EntityMetadata(), name = "engineering", description = "Engineering")
            val saved = repo.save(ns)
            val found = repo.findByIds(listOf(saved.id))
            found shouldHaveSize 1
            found.first().id shouldBe saved.id
            found.first().name shouldBe "engineering"
        }

        "findByParent returns all non-removed namespaces" {
            repo.save(Namespace(metadata = EntityMetadata(), name = "ns-1"))
            repo.save(Namespace(metadata = EntityMetadata(), name = "ns-2"))
            repo.save(Namespace(metadata = EntityMetadata(), name = "ns-3"))
            repo.findByParent(NAMESPACE_PARENT_KEY) shouldHaveSize 3
        }

        "soft delete removes namespace from findByIds" {
            val ns = repo.save(Namespace(metadata = EntityMetadata(), name = "to-delete"))
            repo.delete(ns.id).shouldBeTrue()
            repo.findByIds(listOf(ns.id)).shouldBeEmpty()
        }

        "double delete returns false" {
            val ns = repo.save(Namespace(metadata = EntityMetadata(), name = "double"))
            repo.delete(ns.id).shouldBeTrue()
            repo.delete(ns.id).shouldBeFalse()
        }

        "deleteByParent removes all namespaces" {
            repo.save(Namespace(metadata = EntityMetadata(), name = "a"))
            repo.save(Namespace(metadata = EntityMetadata(), name = "b"))
            val deleted = repo.deleteByParent(NAMESPACE_PARENT_KEY)
            deleted shouldBe 2
            repo.findByParent(NAMESPACE_PARENT_KEY).shouldBeEmpty()
        }

        "update: saving with same id replaces the node" {
            val ns = repo.save(Namespace(metadata = EntityMetadata(), name = "original"))
            repo.save(ns.copy(name = "updated"))
            val found = repo.findByIds(listOf(ns.id))
            found shouldHaveSize 1
            found.first().name shouldBe "updated"
        }
    }
}
