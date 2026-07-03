package io.whozoss.agentos.feedback

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.sdk.feedback.Feedback
import io.whozoss.agentos.user.User
import io.whozoss.agentos.user.UserService
import java.util.UUID

/**
 * Unit tests for [FeedbackServiceImpl] backed by [InMemoryFeedbackRepository].
 *
 * These tests cover the service contract without a Spring context.
 */
class FeedbackServiceImplUnitSpec : StringSpec() {
    val namespaceId = UUID.randomUUID()
    val caseId = UUID.randomUUID()
    val caseEventId = UUID.randomUUID()

    val userId = UUID.randomUUID()
    val userIdStr = userId.toString()

    fun mockUserService(id: UUID = userId): UserService =
        mockk {
            every { getCurrentUser() } returns
                User(
                    metadata = EntityMetadata(id = id),
                    externalId = "user@example.com",
                    email = "user@example.com",
                )
        }

    fun service(userService: UserService = mockUserService()) = FeedbackServiceImpl(InMemoryFeedbackRepository(), userService)

    fun feedback(
        id: UUID = UUID.randomUUID(),
        cId: UUID = caseId,
        ceId: UUID = caseEventId,
        positive: Boolean = true,
        type: String? = null,
        comment: String? = null,
    ) = Feedback(
        metadata = EntityMetadata(id = id),
        namespaceId = namespaceId,
        caseId = cId,
        caseEventId = ceId,
        positive = positive,
        type = type,
        comment = comment,
    )

    init {

        // -------------------------------------------------------------------------
        // create (plain save, no existing node)
        // -------------------------------------------------------------------------

        "create saves and returns the feedback entity" {
            val svc = service()
            val f = feedback(positive = true, comment = "Great answer")

            val saved = svc.create(f)

            saved.id shouldBe f.id
            saved.positive shouldBe true
            saved.comment shouldBe "Great answer"
        }

        // -------------------------------------------------------------------------
        // upsert
        // -------------------------------------------------------------------------

        "upsert creates a new node when no existing feedback for user+event" {
            val svc = service()
            val f = feedback(positive = true)

            val result = svc.upsert(f)

            result.positive shouldBe true
            svc.findByCaseEventId(caseEventId) shouldHaveSize 1
        }

        "upsert clears type and comment when updating to positive=true (positive strips reason fields)" {
            val svc = service()
            // First submission: thumbs-up, no reason
            val first =
                feedback(positive = false, type = "WRONG_ANSWER", comment = "Off topic")
                    .copy(metadata = EntityMetadata(id = UUID.randomUUID(), createdBy = userIdStr))
            svc.create(first) // bypass upsert to plant a node with known createdBy

            // Second submission: enrich with reason and comment
            val second = feedback(positive = true, type = "WRONG_ANSWER", comment = "Off topic")
            val result = svc.upsert(second)

            // Still only one node
            val all = svc.findByCaseEventId(caseEventId)
            all shouldHaveSize 1
            result.positive shouldBe true
            result.type shouldBe null
            result.comment shouldBe null
        }

        "upsert remove comment and type when user gives positive feedback" {
            val svc = service()
            // First submission: thumbs-up, no reason
            val first =
                feedback(positive = true, comment = null)
                    .copy(metadata = EntityMetadata(id = UUID.randomUUID(), createdBy = userIdStr))
            svc.create(first) // bypass upsert to plant a node with known createdBy

            // Second submission: enrich with reason and comment
            val second = feedback(positive = false, type = "WRONG_ANSWER", comment = "Off topic")
            val result = svc.upsert(second)

            // Still only one node
            val all = svc.findByCaseEventId(caseEventId)
            all shouldHaveSize 1
            result.positive shouldBe false
            result.type shouldBe "WRONG_ANSWER"
            result.comment shouldBe "Off topic"
        }

        "upsert preserves the original id and createdBy when updating" {
            val svc = service()
            val originalId = UUID.randomUUID()
            val first =
                feedback(positive = true)
                    .copy(metadata = EntityMetadata(id = originalId, createdBy = userIdStr))
            svc.create(first)

            val second = feedback(positive = false, comment = "Changed my mind")
            val result = svc.upsert(second)

            result.id shouldBe originalId
            result.metadata.createdBy shouldBe userIdStr
        }

        "upsert by different user creates a separate node" {
            val otherUserId = UUID.randomUUID()
            // Both services share the same repository so both users' feedback is visible
            val sharedRepo = InMemoryFeedbackRepository()
            val svc1 = FeedbackServiceImpl(sharedRepo, mockUserService(userId))
            val svc2 = FeedbackServiceImpl(sharedRepo, mockUserService(otherUserId))

            svc1.upsert(feedback(positive = true))
            svc2.upsert(feedback(positive = false))

            // Two distinct users — two separate nodes on the same event
            sharedRepo.findByCaseEventId(caseEventId) shouldHaveSize 2
        }

        // -------------------------------------------------------------------------
        // findByIds
        // -------------------------------------------------------------------------

        "findByIds returns matching entities" {
            val svc = service()
            val f1 = svc.create(feedback())
            val f2 = svc.create(feedback())

            val found = svc.findByIds(listOf(f1.id, f2.id))

            found shouldHaveSize 2
        }

        "findByIds returns empty list for unknown ids" {
            val svc = service()

            svc.findByIds(listOf(UUID.randomUUID())).shouldBeEmpty()
        }

        // -------------------------------------------------------------------------
        // findByParent (caseId)
        // -------------------------------------------------------------------------

        "findByParent returns all feedback for the given caseId" {
            val svc = service()
            val otherCaseId = UUID.randomUUID()
            svc.create(feedback(cId = caseId))
            svc.create(feedback(cId = caseId))
            svc.create(feedback(cId = otherCaseId))

            svc.findByParent(caseId) shouldHaveSize 2
            svc.findByParent(otherCaseId) shouldHaveSize 1
        }

        "findByParent returns empty list when case has no feedback" {
            val svc = service()

            svc.findByParent(UUID.randomUUID()).shouldBeEmpty()
        }

        // -------------------------------------------------------------------------
        // findByCaseEventId
        // -------------------------------------------------------------------------

        "findByCaseEventId returns feedback targeting the given event" {
            val svc = service()
            val otherEventId = UUID.randomUUID()
            svc.create(feedback(ceId = caseEventId))
            svc.create(feedback(ceId = caseEventId))
            svc.create(feedback(ceId = otherEventId))

            svc.findByCaseEventId(caseEventId) shouldHaveSize 2
            svc.findByCaseEventId(otherEventId) shouldHaveSize 1
        }

        "findByCaseEventId returns empty list when event has no feedback" {
            val svc = service()

            svc.findByCaseEventId(UUID.randomUUID()).shouldBeEmpty()
        }

        // -------------------------------------------------------------------------
        // delete (soft-delete)
        // -------------------------------------------------------------------------

        "delete removes entity from findByParent" {
            val svc = service()
            val f = svc.create(feedback())

            svc.delete(f.id).shouldBeTrue()
            svc.findByParent(caseId).shouldBeEmpty()
        }

        "double delete returns false" {
            val svc = service()
            val f = svc.create(feedback())

            svc.delete(f.id).shouldBeTrue()
            svc.delete(f.id).shouldBeFalse()
        }

        "delete returns false for unknown id" {
            val svc = service()

            svc.delete(UUID.randomUUID()).shouldBeFalse()
        }

        // -------------------------------------------------------------------------
        // deleteByParent
        // -------------------------------------------------------------------------

        "deleteByParent removes all feedback for a case" {
            val svc = service()
            val otherCaseId = UUID.randomUUID()
            svc.create(feedback(cId = caseId))
            svc.create(feedback(cId = caseId))
            svc.create(feedback(cId = otherCaseId))

            val count = svc.deleteByParent(caseId)

            count shouldBe 2
            svc.findByParent(caseId).shouldBeEmpty()
            svc.findByParent(otherCaseId) shouldHaveSize 1
        }
    }
}
