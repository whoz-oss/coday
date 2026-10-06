package io.whozoss.agentos.persistence.neo4j

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import io.whozoss.agentos.caseFlow.Case
import io.whozoss.agentos.caseFlow.CaseRepository
import io.whozoss.agentos.caseFlow.CaseService
import io.whozoss.agentos.caseFlow.CaseVersionBackfill
import io.whozoss.agentos.caseFlow.saveChange
import io.whozoss.agentos.exception.ConflictException
import io.whozoss.agentos.namespace.Namespace
import io.whozoss.agentos.namespace.NamespaceRepository
import io.whozoss.agentos.sdk.caseFlow.CaseStatus
import io.whozoss.agentos.sdk.entity.EntityMetadata
import org.neo4j.driver.Driver
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.test.context.ActiveProfiles

/** Optimistic locking on [Case] nodes, against the embedded Neo4j engine. */
@SpringBootTest
@ActiveProfiles("test", "embedded-neo4j")
@Import(EmbeddedNeo4jTestConfiguration::class)
class EmbeddedNeo4jCaseVersionSpec : StringSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired lateinit var caseService: CaseService

    @Autowired lateinit var cases: CaseRepository

    @Autowired lateinit var namespaces: NamespaceRepository

    @Autowired lateinit var backfill: CaseVersionBackfill

    @Autowired lateinit var driver: Driver

    init {
        beforeEach { Neo4jContainerSupport.clearDatabase(driver) }

        "creating a case with the id of an existing case is refused and leaves that case untouched" {
            val namespace = namespaces.save(Namespace(name = "owner"))
            val other = namespaces.save(Namespace(name = "intruder"))
            val original = caseService.create(Case(namespaceId = namespace.id, title = "Original"))
            shouldThrow<ConflictException> {
                caseService.create(Case(metadata = EntityMetadata(id = original.id), namespaceId = other.id, title = "Intruder"))
            }
            val stored = cases.findByIds(listOf(original.id)).single()
            stored.namespaceId shouldBe namespace.id
            stored.title shouldBe "Original"
        }

        "creating a case with the id of a soft-deleted case is refused and does not bring it back" {
            val namespace = namespaces.save(Namespace(name = "owner"))
            val deleted = caseService.create(Case(namespaceId = namespace.id))
            cases.delete(deleted.id) shouldBe true
            shouldThrow<ConflictException> {
                caseService.create(Case(metadata = EntityMetadata(id = deleted.id), namespaceId = namespace.id))
            }
            cases.findByIds(listOf(deleted.id), withRemoved = true).single().metadata.removed shouldBe true
        }

        "a save based on a stale read is refused instead of overwriting the newer one" {
            val namespace = namespaces.save(Namespace(name = "owner"))
            val stale = cases.save(Case(namespaceId = namespace.id))
            cases.save(stale.copy(title = "Newer"))
            shouldThrow<OptimisticLockingFailureException> { cases.save(stale.copy(status = CaseStatus.RUNNING)) }
            cases.findByIds(listOf(stale.id)).single().title shouldBe "Newer"
        }

        "a change based on a stale read is applied again to the fresh case" {
            val namespace = namespaces.save(Namespace(name = "owner"))
            val stale = cases.save(Case(namespaceId = namespace.id))
            cases.save(stale.copy(title = "Generated title"))
            val saved = cases.saveChange(stale) { it.copy(status = CaseStatus.RUNNING) }
            saved.title shouldBe "Generated title"
            saved.status shouldBe CaseStatus.RUNNING
        }

        "a case saved before versioning can only be updated once the backfill gave it a version" {
            val namespace = namespaces.save(Namespace(name = "owner"))
            val legacy = cases.save(Case(namespaceId = namespace.id))
            driver.session().use { session ->
                session.run("MATCH (c:Case {id: \$id}) REMOVE c.version", mapOf("id" to legacy.id.toString())).consume()
            }
            val unversioned = cases.findByIds(listOf(legacy.id)).single()
            shouldThrow<OptimisticLockingFailureException> { cases.save(unversioned.copy(title = "Before backfill")) }
            backfill.afterSingletonsInstantiated()
            val migrated = cases.findByIds(listOf(legacy.id)).single()
            cases.save(migrated.copy(title = "After backfill")).title shouldBe "After backfill"
        }
    }
}
