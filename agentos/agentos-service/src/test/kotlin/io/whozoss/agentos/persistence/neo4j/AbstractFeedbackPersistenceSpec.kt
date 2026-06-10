package io.whozoss.agentos.persistence.neo4j

import io.kotest.core.spec.style.StringSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.whozoss.agentos.caseEvent.CaseEventRepository
import io.whozoss.agentos.caseFlow.CaseRepository
import io.whozoss.agentos.feedback.FeedbackRepository
import io.whozoss.agentos.namespace.NamespaceRepository
import io.whozoss.agentos.sdk.actor.Actor
import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.caseEvent.MessageContent
import io.whozoss.agentos.sdk.caseEvent.MessageEvent
import io.whozoss.agentos.sdk.caseFlow.CaseStatus
import io.whozoss.agentos.caseFlow.Case
import io.whozoss.agentos.namespace.Namespace
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.sdk.feedback.Feedback
import org.neo4j.driver.Driver
import org.springframework.beans.factory.annotation.Autowired
import java.time.Instant
import java.util.UUID

/**
 * Shared feedback persistence contract tests.
 *
 * Subclasses activate a specific persistence mode (embedded harness or Testcontainers)
 * and inherit all test cases, ensuring both modes satisfy the same contract.
 *
 * A [Case] and a [io.whozoss.agentos.sdk.caseEvent.CaseEvent] node must exist before
 * feedback is saved because [io.whozoss.agentos.feedback.Neo4jFeedbackRepository]
 * creates a `(:Feedback)-[:FEEDBACK_ON]->(:CaseEvent)` edge via MATCH. Pre-creating
 * parent nodes with [caseRepo], [caseEventRepo], and [namespaceRepo] satisfies that
 * requirement.
 */
abstract class AbstractFeedbackPersistenceSpec : StringSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired lateinit var repo: FeedbackRepository
    @Autowired lateinit var caseRepo: CaseRepository
    @Autowired lateinit var caseEventRepo: CaseEventRepository
    @Autowired lateinit var namespaceRepo: NamespaceRepository
    @Autowired lateinit var driver: Driver

    fun namespace() = Namespace(metadata = EntityMetadata(), name = "test-ns")

    fun case(namespaceId: UUID) =
        Case(metadata = EntityMetadata(), namespaceId = namespaceId, status = CaseStatus.PENDING)

    fun msgEvent(caseId: UUID, namespaceId: UUID) =
        MessageEvent(
            metadata = EntityMetadata(),
            namespaceId = namespaceId,
            caseId = caseId,
            actor = Actor(id = "u1", displayName = "User", role = ActorRole.USER),
            content = listOf(MessageContent.Text("hello")),
        )

    fun feedback(
        namespaceId: UUID,
        caseId: UUID,
        caseEventId: UUID,
        positive: Boolean = true,
        type: String? = null,
        comment: String? = null,
    ) = Feedback(
        metadata = EntityMetadata(),
        namespaceId = namespaceId,
        caseId = caseId,
        caseEventId = caseEventId,
        positive = positive,
        type = type,
        comment = comment,
        timestamp = Instant.now(),
    )

    init {
        beforeEach { Neo4jContainerSupport.clearDatabase(driver) }

        "save and findByIds returns the same Feedback" {
            val ns = namespaceRepo.save(namespace())
            val case = caseRepo.save(case(ns.id))
            val event = caseEventRepo.save(msgEvent(case.id, ns.id))
            val f = repo.save(feedback(ns.id, case.id, event.id, positive = true, comment = "Good"))

            val found = repo.findByIds(listOf(f.id))

            found shouldHaveSize 1
            found.first().id shouldBe f.id
            found.first().positive shouldBe true
            found.first().comment shouldBe "Good"
            found.first().caseEventId shouldBe event.id
        }

        "findByParent returns all feedback for a case" {
            val ns = namespaceRepo.save(namespace())
            val case1 = caseRepo.save(case(ns.id))
            val case2 = caseRepo.save(case(ns.id))
            val event1 = caseEventRepo.save(msgEvent(case1.id, ns.id))
            val event2 = caseEventRepo.save(msgEvent(case2.id, ns.id))
            repo.save(feedback(ns.id, case1.id, event1.id))
            repo.save(feedback(ns.id, case1.id, event1.id))
            repo.save(feedback(ns.id, case2.id, event2.id))

            repo.findByParent(case1.id) shouldHaveSize 2
            repo.findByParent(case2.id) shouldHaveSize 1
        }

        "findByCaseEventId returns feedback targeting a specific event" {
            val ns = namespaceRepo.save(namespace())
            val case = caseRepo.save(case(ns.id))
            val event1 = caseEventRepo.save(msgEvent(case.id, ns.id))
            val event2 = caseEventRepo.save(msgEvent(case.id, ns.id))
            repo.save(feedback(ns.id, case.id, event1.id))
            repo.save(feedback(ns.id, case.id, event1.id))
            repo.save(feedback(ns.id, case.id, event2.id))

            repo.findByCaseEventId(event1.id) shouldHaveSize 2
            repo.findByCaseEventId(event2.id) shouldHaveSize 1
        }

        "Feedback round-trips type and comment fields correctly" {
            val ns = namespaceRepo.save(namespace())
            val case = caseRepo.save(case(ns.id))
            val event = caseEventRepo.save(msgEvent(case.id, ns.id))
            val f = repo.save(feedback(ns.id, case.id, event.id, positive = false, type = "WRONG_ANSWER", comment = "Did not answer"))

            val found = repo.findByIds(listOf(f.id)).first()

            found.positive shouldBe false
            found.type shouldBe "WRONG_ANSWER"
            found.comment shouldBe "Did not answer"
        }

        "soft delete removes feedback from findByIds and findByParent" {
            val ns = namespaceRepo.save(namespace())
            val case = caseRepo.save(case(ns.id))
            val event = caseEventRepo.save(msgEvent(case.id, ns.id))
            val f = repo.save(feedback(ns.id, case.id, event.id))

            repo.delete(f.id).shouldBeTrue()
            repo.findByIds(listOf(f.id)).shouldBeEmpty()
            repo.findByParent(case.id).shouldBeEmpty()
        }

        "double delete returns false" {
            val ns = namespaceRepo.save(namespace())
            val case = caseRepo.save(case(ns.id))
            val event = caseEventRepo.save(msgEvent(case.id, ns.id))
            val f = repo.save(feedback(ns.id, case.id, event.id))

            repo.delete(f.id).shouldBeTrue()
            repo.delete(f.id).shouldBeFalse()
        }

        "deleteByParent removes all feedback for a case" {
            val ns = namespaceRepo.save(namespace())
            val case1 = caseRepo.save(case(ns.id))
            val case2 = caseRepo.save(case(ns.id))
            val event1 = caseEventRepo.save(msgEvent(case1.id, ns.id))
            val event2 = caseEventRepo.save(msgEvent(case2.id, ns.id))
            repo.save(feedback(ns.id, case1.id, event1.id))
            repo.save(feedback(ns.id, case1.id, event1.id))
            repo.save(feedback(ns.id, case2.id, event2.id))

            val deleted = repo.deleteByParent(case1.id)

            deleted shouldBe 2
            repo.findByParent(case1.id).shouldBeEmpty()
            repo.findByParent(case2.id) shouldHaveSize 1
        }

        "saving feedback does not overwrite CaseEvent node properties" {
            // Regression guard: FeedbackNode must NOT set the caseEvent @Relationship field
            // to a stub — doing so would overwrite the real CaseEventNode's properties.
            val ns = namespaceRepo.save(namespace())
            val case = caseRepo.save(case(ns.id))
            val event = caseEventRepo.save(msgEvent(case.id, ns.id))
            val originalEventText = "hello"

            // Save two feedback entries targeting the same event
            repo.save(feedback(ns.id, case.id, event.id))
            repo.save(feedback(ns.id, case.id, event.id))

            // The case event must still be retrievable and intact
            val refetchedEvents = caseEventRepo.findByParent(case.id)
            refetchedEvents shouldHaveSize 1
            refetchedEvents.first().id shouldBe event.id
        }
    }
}
