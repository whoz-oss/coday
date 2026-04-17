package io.whozoss.agentos.persistence.neo4j

import io.kotest.core.spec.style.StringSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.whozoss.agentos.caseFlow.Case
import io.whozoss.agentos.caseFlow.CaseRepository
import io.whozoss.agentos.namespace.Namespace
import io.whozoss.agentos.namespace.NamespaceRepository
import io.whozoss.agentos.sdk.caseFlow.CaseStatus
import io.whozoss.agentos.sdk.entity.EntityMetadata
import org.neo4j.driver.Driver
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID

/**
 * Shared case persistence contract tests.
 *
 * Subclasses activate a specific persistence mode (Testcontainers or embedded)
 * and inherit all test cases, ensuring both modes satisfy the same contract.
 *
 * A [Namespace] node must exist before [Case] nodes are saved because
 * [CaseNodeNeo4jRepository.findActiveByNamespaceId] traverses the BELONGS_TO
 * edge to the Namespace node. [namespaceRepo] is used to pre-create namespaces.
 */
abstract class AbstractCasePersistenceSpec : StringSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    lateinit var repo: CaseRepository

    @Autowired
    lateinit var namespaceRepo: NamespaceRepository

    @Autowired
    lateinit var driver: Driver

    fun namespace() = Namespace(metadata = EntityMetadata(), name = "test-ns")

    fun case(
        namespaceId: UUID,
        status: CaseStatus = CaseStatus.PENDING,
    ) = Case(metadata = EntityMetadata(), namespaceId = namespaceId, status = status)

    init {
        beforeEach { Neo4jContainerSupport.clearDatabase(driver) }

        "save and findByIds returns the same case" {
            val ns = namespaceRepo.save(namespace())
            val saved = repo.save(case(ns.id))
            val found = repo.findByIds(listOf(saved.id))
            found shouldHaveSize 1
            found.first().id shouldBe saved.id
            found.first().status shouldBe CaseStatus.PENDING
        }

        "findByParent returns cases for that namespace only" {
            val ns1 = namespaceRepo.save(namespace())
            val ns2 = namespaceRepo.save(namespace())
            repo.save(case(ns1.id))
            repo.save(case(ns1.id))
            repo.save(case(ns2.id))
            repo.findByParent(ns1.id) shouldHaveSize 2
            repo.findByParent(ns2.id) shouldHaveSize 1
        }

        "update: save with same id replaces the node" {
            val ns = namespaceRepo.save(namespace())
            val c = repo.save(case(ns.id))
            repo.save(c.copy(status = CaseStatus.RUNNING))
            val found = repo.findByIds(listOf(c.id))
            found.first().status shouldBe CaseStatus.RUNNING
        }

        "soft delete removes case from findByIds" {
            val ns = namespaceRepo.save(namespace())
            val c = repo.save(case(ns.id))
            repo.delete(c.id).shouldBeTrue()
            repo.findByIds(listOf(c.id)).shouldBeEmpty()
        }

        "double delete returns false" {
            val ns = namespaceRepo.save(namespace())
            val c = repo.save(case(ns.id))
            repo.delete(c.id).shouldBeTrue()
            repo.delete(c.id).shouldBeFalse()
        }

        "saving a case does not overwrite Namespace node properties" {
            // Regression: NamespaceNode.stub() used to write empty name/description
            // onto the existing Namespace node when the BELONGS_TO edge was saved
            // via the @Relationship field.
            val ns = namespaceRepo.save(Namespace(metadata = EntityMetadata(), name = "my-namespace", description = "important"))
            repo.save(case(ns.id))
            repo.save(case(ns.id))
            val found = namespaceRepo.findByIds(listOf(ns.id))
            found shouldHaveSize 1
            found.first().name shouldBe "my-namespace"
            found.first().description shouldBe "important"
        }

        "deleteByParent removes all cases in namespace without touching others" {
            val ns1 = namespaceRepo.save(namespace())
            val ns2 = namespaceRepo.save(namespace())
            repo.save(case(ns1.id))
            repo.save(case(ns1.id))
            val survivor = repo.save(case(ns2.id))
            val deleted = repo.deleteByParent(ns1.id)
            deleted shouldBe 2
            repo.findByParent(ns1.id).shouldBeEmpty()
            repo.findByParent(ns2.id) shouldHaveSize 1
            repo.findByParent(ns2.id).first().id shouldBe survivor.id
        }
    }
}
