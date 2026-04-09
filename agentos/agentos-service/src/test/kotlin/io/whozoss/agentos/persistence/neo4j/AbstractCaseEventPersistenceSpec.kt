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
 */
abstract class AbstractCaseEventPersistenceSpec : StringSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    lateinit var repo: CaseEventRepository

    @Autowired
    lateinit var driver: Driver

    val namespaceId: UUID = UUID.randomUUID()

    fun msgEvent(
        caseId: UUID = UUID.randomUUID(),
        text: String = "hello",
    ) = MessageEvent(
        metadata = EntityMetadata(),
        namespaceId = namespaceId,
        caseId = caseId,
        actor = Actor(id = "u1", displayName = "User", role = ActorRole.USER),
        content = listOf(MessageContent.Text(text)),
    )

    fun statusEvent(
        caseId: UUID = UUID.randomUUID(),
        status: CaseStatus = CaseStatus.RUNNING,
    ) = CaseStatusEvent(
        metadata = EntityMetadata(),
        namespaceId = namespaceId,
        caseId = caseId,
        status = status,
    )

    init {
        beforeEach { Neo4jContainerSupport.clearDatabase(driver) }

        "save and findByIds returns the same MessageEvent" {
            val e = repo.save(msgEvent())
            val found = repo.findByIds(listOf(e.id))
            found shouldHaveSize 1
            found.first().id shouldBe e.id
            found.first().shouldBeInstanceOf<MessageEvent>()
            (found.first() as MessageEvent).actor.id shouldBe "u1"
        }

        "CaseStatusEvent round-trips correctly" {
            val e = repo.save(statusEvent(status = CaseStatus.IDLE))
            val found = repo.findByIds(listOf(e.id))
            found.first().shouldBeInstanceOf<CaseStatusEvent>()
            (found.first() as CaseStatusEvent).status shouldBe CaseStatus.IDLE
        }

        "ToolRequestEvent and ToolResponseEvent round-trip correctly" {
            val caseId = UUID.randomUUID()
            repo.save(
                ToolRequestEvent(
                    metadata = EntityMetadata(),
                    namespaceId = namespaceId,
                    caseId = caseId,
                    toolRequestId = "req-1",
                    toolName = "get_time",
                    args = "{\"timezone\":\"UTC\"}",
                ),
            )
            repo.save(
                ToolResponseEvent(
                    metadata = EntityMetadata(),
                    namespaceId = namespaceId,
                    caseId = caseId,
                    toolRequestId = "req-1",
                    toolName = "get_time",
                    output = MessageContent.Text("2026-01-01T00:00:00Z"),
                    success = true,
                ),
            )
            val found = repo.findByParent(caseId)
            found shouldHaveSize 2
            found.filterIsInstance<ToolRequestEvent>() shouldHaveSize 1
            found.filterIsInstance<ToolResponseEvent>() shouldHaveSize 1
        }

        "findByParent returns events ordered by timestamp then id" {
            val caseId = UUID.randomUUID()
            val saved = (1..5).map { repo.save(msgEvent(caseId, "message $it")) }
            val found = repo.findByParent(caseId)
            found shouldHaveSize 5
            val expectedIds = saved.sortedWith(compareBy({ it.timestamp }, { it.id })).map { it.id }
            found.map { it.id } shouldBe expectedIds
        }

        "findByParent isolates events between cases" {
            val case1 = UUID.randomUUID()
            val case2 = UUID.randomUUID()
            repo.save(msgEvent(case1))
            repo.save(msgEvent(case1))
            repo.save(msgEvent(case2))
            repo.findByParent(case1) shouldHaveSize 2
            repo.findByParent(case2) shouldHaveSize 1
        }

        "soft delete removes event from findByIds and findByParent" {
            val caseId = UUID.randomUUID()
            val e = repo.save(msgEvent(caseId))
            repo.delete(e.id).shouldBeTrue()
            repo.findByIds(listOf(e.id)).shouldBeEmpty()
            repo.findByParent(caseId).shouldBeEmpty()
        }

        "double delete returns false" {
            val e = repo.save(msgEvent())
            repo.delete(e.id).shouldBeTrue()
            repo.delete(e.id).shouldBeFalse()
        }

        "deleteByParent removes all events for a case" {
            val case1 = UUID.randomUUID()
            val case2 = UUID.randomUUID()
            repo.save(msgEvent(case1))
            repo.save(msgEvent(case1))
            repo.save(msgEvent(case2))
            val deleted = repo.deleteByParent(case1)
            deleted shouldBe 2
            repo.findByParent(case1).shouldBeEmpty()
            repo.findByParent(case2) shouldHaveSize 1
        }
    }
}
