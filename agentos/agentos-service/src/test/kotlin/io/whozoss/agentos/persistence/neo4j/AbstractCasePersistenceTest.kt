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
 */
abstract class AbstractCasePersistenceTest : StringSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    lateinit var repo: CaseRepository

    @Autowired
    lateinit var driver: Driver

    fun case(
        namespaceId: UUID = UUID.randomUUID(),
        status: CaseStatus = CaseStatus.PENDING,
    ) = Case(metadata = EntityMetadata(), namespaceId = namespaceId, status = status)

    init {
        beforeEach { Neo4jContainerSupport.clearDatabase(driver) }

        "save and findByIds returns the same case" {
            val c = case()
            val saved = repo.save(c)
            val found = repo.findByIds(listOf(saved.id))
            found shouldHaveSize 1
            found.first().id shouldBe saved.id
            found.first().status shouldBe CaseStatus.PENDING
        }

        "findByParent returns cases for that namespace only" {
            val ns1 = UUID.randomUUID()
            val ns2 = UUID.randomUUID()
            repo.save(case(ns1))
            repo.save(case(ns1))
            repo.save(case(ns2))
            repo.findByParent(ns1) shouldHaveSize 2
            repo.findByParent(ns2) shouldHaveSize 1
        }

        "update: save with same id replaces the node" {
            val c = repo.save(case())
            repo.save(c.copy(status = CaseStatus.RUNNING))
            val found = repo.findByIds(listOf(c.id))
            found.first().status shouldBe CaseStatus.RUNNING
        }

        "soft delete removes case from findByIds" {
            val c = repo.save(case())
            repo.delete(c.id).shouldBeTrue()
            repo.findByIds(listOf(c.id)).shouldBeEmpty()
        }

        "double delete returns false" {
            val c = repo.save(case())
            repo.delete(c.id).shouldBeTrue()
            repo.delete(c.id).shouldBeFalse()
        }

        "deleteByParent removes all cases in namespace without touching others" {
            val ns1 = UUID.randomUUID()
            val ns2 = UUID.randomUUID()
            repo.save(case(ns1))
            repo.save(case(ns1))
            val survivor = repo.save(case(ns2))
            val deleted = repo.deleteByParent(ns1)
            deleted shouldBe 2
            repo.findByParent(ns1).shouldBeEmpty()
            repo.findByParent(ns2) shouldHaveSize 1
            repo.findByParent(ns2).first().id shouldBe survivor.id
        }
    }
}
