package io.whozoss.agentos.feedback

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.whozoss.agentos.caseEvent.CaseEventService
import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.sdk.actor.Actor
import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.caseEvent.MessageContent
import io.whozoss.agentos.sdk.caseEvent.MessageEvent
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.sdk.feedback.Feedback
import java.time.Instant
import java.util.UUID

/**
 * Unit tests for [FeedbackController].
 *
 * Permission checks are declarative (`@PreAuthorize`) and only fire through Spring AOP.
 * Direct instantiation bypasses the proxy, so authorization is NOT tested here.
 * These tests cover:
 * - [FeedbackController.create]: happy path, missing event 404, upsert delegation
 * - [FeedbackController.listByCase]: delegation
 * - [FeedbackController.listByCaseEvent]: delegation
 * - toResource / toDomain mapping (verified through the public endpoints)
 */
class FeedbackControllerUnitSpec : StringSpec({

    val feedbackService = mockk<FeedbackService>()
    val caseEventService = mockk<CaseEventService>()
    val controller = FeedbackController(feedbackService, caseEventService)

    val namespaceId = UUID.randomUUID()
    val caseId = UUID.randomUUID()
    val caseEventId = UUID.randomUUID()

    fun resource(
        id: UUID? = null,
        nsId: UUID = namespaceId,
        cId: UUID = caseId,
        ceId: UUID = caseEventId,
        positive: Boolean = true,
        type: String? = null,
        comment: String? = null,
    ) = FeedbackResource(
        id = id,
        namespaceId = nsId,
        caseId = cId,
        caseEventId = ceId,
        positive = positive,
        type = type,
        comment = comment,
    )

    fun feedback(
        id: UUID = UUID.randomUUID(),
        ceId: UUID = caseEventId,
        positive: Boolean = true,
        type: String? = null,
        comment: String? = null,
    ) = Feedback(
        metadata = EntityMetadata(id = id),
        namespaceId = namespaceId,
        caseId = caseId,
        caseEventId = ceId,
        positive = positive,
        type = type,
        comment = comment,
        timestamp = Instant.now(),
    )

    fun event() = MessageEvent(
        metadata = EntityMetadata(),
        namespaceId = namespaceId,
        caseId = caseId,
        actor = Actor(id = "u1", displayName = "User", role = ActorRole.USER),
        content = listOf(MessageContent.Text("hello")),
    )

    beforeTest { clearAllMocks() }

    // -------------------------------------------------------------------------
    // create (delegates to upsert)
    // -------------------------------------------------------------------------

    "create returns 201 resource when event exists" {
        val r = resource(positive = true, comment = "Helpful!")
        val saved = feedback(positive = true, comment = "Helpful!")
        every { caseEventService.findById(caseEventId) } returns event()
        every { feedbackService.upsert(any()) } returns saved

        val result = controller.create(r)

        result.positive shouldBe true
        result.comment shouldBe "Helpful!"
        result.caseId shouldBe caseId
        result.caseEventId shouldBe caseEventId
        verify(exactly = 1) { caseEventService.findById(caseEventId) }
        verify(exactly = 1) { feedbackService.upsert(any()) }
    }

    "create throws 404 when target case event does not exist" {
        every { caseEventService.findById(caseEventId) } returns null

        shouldThrow<ResourceNotFoundException> { controller.create(resource()) }
        verify(exactly = 0) { feedbackService.upsert(any()) }
    }

    "create maps type and comment from resource to domain" {
        val r = resource(type = "WRONG_ANSWER", comment = "Missing context")
        val saved = feedback(type = "WRONG_ANSWER", comment = "Missing context")
        every { caseEventService.findById(caseEventId) } returns event()
        every { feedbackService.upsert(any()) } returns saved

        val result = controller.create(r)

        result.type shouldBe "WRONG_ANSWER"
        result.comment shouldBe "Missing context"
    }

    "create generates a fresh UUID when resource id is null" {
        // Two distinct calls must produce different UUIDs — proves a fresh UUID is generated
        // rather than reusing a fixed value.
        val r = resource(id = null)
        val captured = mutableListOf<UUID>()
        every { caseEventService.findById(caseEventId) } returns event()
        every { feedbackService.upsert(any()) } answers {
            val f = firstArg<Feedback>()
            captured += f.id
            f
        }

        controller.create(r)
        controller.create(r)

        // Two invocations with null id must have produced two distinct UUIDs
        (captured[0] != captured[1]) shouldBe true
    }

    // -------------------------------------------------------------------------
    // listByCase
    // -------------------------------------------------------------------------

    "listByCase delegates to feedbackService.findByParent" {
        val f1 = feedback()
        val f2 = feedback()
        every { feedbackService.findByParent(caseId) } returns listOf(f1, f2)

        val result = controller.listByCase(caseId)

        result.size shouldBe 2
        verify(exactly = 1) { feedbackService.findByParent(caseId) }
    }

    "listByCase returns empty list when case has no feedback" {
        every { feedbackService.findByParent(caseId) } returns emptyList()

        controller.listByCase(caseId) shouldBe emptyList()
    }

    // -------------------------------------------------------------------------
    // listByCaseEvent
    // -------------------------------------------------------------------------

    "listByCaseEvent delegates to feedbackService.findByCaseEventId" {
        val f1 = feedback(ceId = caseEventId)
        val f2 = feedback(ceId = caseEventId)
        every { feedbackService.findByCaseEventId(caseEventId) } returns listOf(f1, f2)

        val result = controller.listByCaseEvent(caseEventId)

        result.size shouldBe 2
        verify(exactly = 1) { feedbackService.findByCaseEventId(caseEventId) }
    }

    // -------------------------------------------------------------------------
    // toResource mapping (verified via create response)
    // -------------------------------------------------------------------------

    "create response maps all resource fields correctly" {
        val fId = UUID.randomUUID()
        val ts = Instant.parse("2026-01-01T00:00:00Z")
        val saved = Feedback(
            metadata = EntityMetadata(
                id = fId,
                created = ts,
                createdBy = "user-abc",
                modified = ts,
                modifiedBy = "user-abc",
            ),
            namespaceId = namespaceId,
            caseId = caseId,
            caseEventId = caseEventId,
            positive = false,
            type = "UNHELPFUL",
            comment = "Did not answer",
            timestamp = ts,
        )
        every { caseEventService.findById(caseEventId) } returns event()
        every { feedbackService.upsert(any()) } returns saved

        val result = controller.create(resource(positive = false))

        result.id shouldBe fId
        result.namespaceId shouldBe namespaceId
        result.caseId shouldBe caseId
        result.caseEventId shouldBe caseEventId
        result.positive shouldBe false
        result.type shouldBe "UNHELPFUL"
        result.comment shouldBe "Did not answer"
        result.timestamp shouldBe ts
        result.createdBy shouldBe "user-abc"
        result.createdOn shouldBe ts
    }
})
