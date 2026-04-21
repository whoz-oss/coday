package io.whozoss.agentos.persistence.neo4j

import io.kotest.core.spec.style.StringSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.whozoss.agentos.caseEvent.CaseEventRepository
import io.whozoss.agentos.caseFlow.Case
import io.whozoss.agentos.caseFlow.CaseRepository
import io.whozoss.agentos.namespace.Namespace
import io.whozoss.agentos.namespace.NamespaceRepository
import io.whozoss.agentos.sdk.actor.Actor
import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.caseEvent.CaseStatusEvent
import io.whozoss.agentos.sdk.caseEvent.MessageContent
import io.whozoss.agentos.sdk.caseEvent.MessageEvent
import io.whozoss.agentos.sdk.caseEvent.ToolRequestEvent
import io.whozoss.agentos.sdk.caseEvent.ToolResponseEvent
import io.whozoss.agentos.sdk.caseFlow.CaseStatus
import io.whozoss.agentos.sdk.entity.EntityMetadata
import org.neo4j.driver.Driver
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID

/**
 * Shared case-event persistence contract tests.
 *
 * Subclasses activate a specific persistence mode (Testcontainers or embedded)
 * and inherit all test cases, ensuring both modes satisfy the same contract.
 *
 * A [Case] node must exist before events are saved because
 * [CaseEventNodeNeo4jRepository.linkEventToCase] does a MATCH on the Case node
 * to create the BELONGS_TO edge. [caseRepo] and [namespaceRepo] are used to
 * pre-create the required parent nodes.
 */
abstract class AbstractCaseEventPersistenceSpec : StringSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    lateinit var repo: CaseEventRepository

    @Autowired
    lateinit var caseRepo: CaseRepository

    @Autowired
    lateinit var namespaceRepo: NamespaceRepository

    @Autowired
    lateinit var driver: Driver

    fun namespace() = Namespace(metadata = EntityMetadata(), name = "test-ns")

    fun case(namespaceId: UUID) =
        Case(metadata = EntityMetadata(), namespaceId = namespaceId, status = CaseStatus.PENDING)

    fun msgEvent(
        caseId: UUID,
        text: String = "hello",
    ) = MessageEvent(
        metadata = EntityMetadata(),
        namespaceId = UUID.randomUUID(),
        caseId = caseId,
        actor = Actor(id = "u1", displayName = "User", role = ActorRole.USER),
        content = listOf(MessageContent.Text(text)),
    )

    fun statusEvent(
        caseId: UUID,
        status: CaseStatus = CaseStatus.RUNNING,
    ) = CaseStatusEvent(
        metadata = EntityMetadata(),
        namespaceId = UUID.randomUUID(),
        caseId = caseId,
        status = status,
    )

    init {
        beforeEach { Neo4jContainerSupport.clearDatabase(driver) }

        "save and findByIds returns the same MessageEvent" {
            val ns = namespaceRepo.save(namespace())
            val case = caseRepo.save(case(ns.id))
            val e = repo.save(msgEvent(case.id))
            val found = repo.findByIds(listOf(e.id))
            found shouldHaveSize 1
            found.first().id shouldBe e.id
            found.first().shouldBeInstanceOf<MessageEvent>()
            (found.first() as MessageEvent).actor.id shouldBe "u1"
        }

        "CaseStatusEvent round-trips correctly" {
            val ns = namespaceRepo.save(namespace())
            val case = caseRepo.save(case(ns.id))
            val e = repo.save(statusEvent(case.id, status = CaseStatus.IDLE))
            val found = repo.findByIds(listOf(e.id))
            found.first().shouldBeInstanceOf<CaseStatusEvent>()
            (found.first() as CaseStatusEvent).status shouldBe CaseStatus.IDLE
        }

        "ToolRequestEvent and ToolResponseEvent round-trip correctly" {
            val ns = namespaceRepo.save(namespace())
            val case = caseRepo.save(case(ns.id))
            repo.save(
                ToolRequestEvent(
                    metadata = EntityMetadata(),
                    namespaceId = ns.id,
                    caseId = case.id,
                    toolRequestId = "req-1",
                    toolName = "get_time",
                    args = "{\"timezone\":\"UTC\"}",
                ),
            )
            repo.save(
                ToolResponseEvent(
                    metadata = EntityMetadata(),
                    namespaceId = ns.id,
                    caseId = case.id,
                    toolRequestId = "req-1",
                    toolName = "get_time",
                    output = MessageContent.Text("2026-01-01T00:00:00Z"),
                    success = true,
                ),
            )
            val found = repo.findByParent(case.id)
            found shouldHaveSize 2
            found.filterIsInstance<ToolRequestEvent>() shouldHaveSize 1
            found.filterIsInstance<ToolResponseEvent>() shouldHaveSize 1
        }

        "findByParent returns events ordered by timestamp then id" {
            val ns = namespaceRepo.save(namespace())
            val case = caseRepo.save(case(ns.id))
            val saved = (1..5).map { repo.save(msgEvent(case.id, "message $it")) }
            val found = repo.findByParent(case.id)
            found shouldHaveSize 5
            val expectedIds = saved.sortedWith(compareBy({ it.timestamp }, { it.id })).map { it.id }
            found.map { it.id } shouldBe expectedIds
        }

        "findByParent isolates events between cases" {
            val ns = namespaceRepo.save(namespace())
            val case1 = caseRepo.save(case(ns.id))
            val case2 = caseRepo.save(case(ns.id))
            repo.save(msgEvent(case1.id))
            repo.save(msgEvent(case1.id))
            repo.save(msgEvent(case2.id))
            repo.findByParent(case1.id) shouldHaveSize 2
            repo.findByParent(case2.id) shouldHaveSize 1
        }

        "soft delete removes event from findByIds and findByParent" {
            val ns = namespaceRepo.save(namespace())
            val case = caseRepo.save(case(ns.id))
            val e = repo.save(msgEvent(case.id))
            repo.delete(e.id).shouldBeTrue()
            repo.findByIds(listOf(e.id)).shouldBeEmpty()
            repo.findByParent(case.id).shouldBeEmpty()
        }

        "double delete returns false" {
            val ns = namespaceRepo.save(namespace())
            val case = caseRepo.save(case(ns.id))
            val e = repo.save(msgEvent(case.id))
            repo.delete(e.id).shouldBeTrue()
            repo.delete(e.id).shouldBeFalse()
        }

        "saving events does not overwrite Case node properties" {
            // Regression: previously CaseNode.stub() wrote empty status/title onto
            // the existing Case node when saving via the @Relationship field.
            val ns = namespaceRepo.save(namespace())
            val savedCase = caseRepo.save(case(ns.id).copy(status = CaseStatus.RUNNING))
            repo.save(msgEvent(savedCase.id))
            repo.save(msgEvent(savedCase.id))
            val found = caseRepo.findByIds(listOf(savedCase.id))
            found shouldHaveSize 1
            found.first().status shouldBe CaseStatus.RUNNING
        }

        "deleteByParent removes all events for a case" {
            val ns = namespaceRepo.save(namespace())
            val case1 = caseRepo.save(case(ns.id))
            val case2 = caseRepo.save(case(ns.id))
            repo.save(msgEvent(case1.id))
            repo.save(msgEvent(case1.id))
            repo.save(msgEvent(case2.id))
            val deleted = repo.deleteByParent(case1.id)
            deleted shouldBe 2
            repo.findByParent(case1.id).shouldBeEmpty()
            repo.findByParent(case2.id) shouldHaveSize 1
        }
    }
}
