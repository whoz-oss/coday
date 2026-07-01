package io.whozoss.agentos.caseDefinition

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.sdk.entity.EntityMetadata
import java.util.UUID
import org.springframework.web.server.ResponseStatusException

class CaseDefinitionControllerUnitSpec : StringSpec({

    val service = mockk<CaseDefinitionService>()
    val controller = CaseDefinitionController(service)
    val namespaceId: UUID = UUID.randomUUID()
    val agentId: UUID = UUID.randomUUID()

    fun def(
        id: UUID = UUID.randomUUID(),
        name: String = "my-def",
        nsId: UUID = namespaceId,
        userGroupId: UUID? = null,
        userId: UUID? = null,
        enabled: Boolean = true,
        cronExpression: String = "0 8 * * *",
    ) = CaseDefinition(
        metadata = EntityMetadata(id = id),
        namespaceId = nsId,
        userGroupId = userGroupId,
        userId = userId,
        name = name,
        agentId = agentId,
        prompt = "Do the thing.",
        cronExpression = cronExpression,
        enabled = enabled,
    )

    fun resource(
        id: UUID? = null,
        nsId: UUID = namespaceId,
        userGroupId: UUID? = null,
        userId: UUID? = null,
        name: String = "my-def",
        enabled: Boolean = true,
        frequency: ScheduleFrequency = ScheduleFrequency.DAILY,
        timeUtc: String = "08:00",
        dayOfWeek: String? = null,
    ) = CaseDefinitionResource(
        id = id,
        namespaceId = nsId,
        userGroupId = userGroupId,
        userId = userId,
        name = name,
        agentId = agentId,
        prompt = "Do the thing.",
        frequency = frequency,
        timeUtc = timeUtc,
        dayOfWeek = dayOfWeek,
        enabled = enabled,
    )

    beforeTest { clearAllMocks() }

    // requireNamespaceId guard
    "list throws 400 when namespaceId is null" { shouldThrow<ResponseStatusException> { controller.list(null) } }
    "getById throws 400 when namespaceId is null" { shouldThrow<ResponseStatusException> { controller.getById(UUID.randomUUID(), null) } }
    "create throws 400 when namespaceId is null" { shouldThrow<ResponseStatusException> { controller.create(null, resource()) } }
    "update throws 400 when namespaceId is null" { shouldThrow<ResponseStatusException> { controller.update(UUID.randomUUID(), null, resource()) } }
    "toggle throws 400 when namespaceId is null" { shouldThrow<ResponseStatusException> { controller.toggle(UUID.randomUUID(), null) } }
    "delete throws 400 when namespaceId is null" { shouldThrow<ResponseStatusException> { controller.delete(UUID.randomUUID(), null) } }

    // requireNamespaceMatch guard
    "create throws 400 when namespaceId in body does not match query param" {
        shouldThrow<ResponseStatusException> { controller.create(namespaceId, resource(nsId = UUID.randomUUID())) }
    }
    "update throws 400 when namespaceId in body does not match query param" {
        val id = UUID.randomUUID()
        every { service.findById(id) } returns def(id = id)
        shouldThrow<ResponseStatusException> { controller.update(id, namespaceId, resource(id = id, nsId = UUID.randomUUID())) }
    }

    // list
    "list returns all definitions for the namespace" {
        every { service.findByParent(namespaceId) } returns listOf(def(name = "alpha"), def(name = "beta"))
        controller.list(namespaceId).map { it.name } shouldBe listOf("alpha", "beta")
        verify(exactly = 1) { service.findByParent(namespaceId) }
    }

    // getById
    "getById returns the resource when definition exists and belongs to namespace" {
        val id = UUID.randomUUID()
        every { service.findById(id, withRemoved = true) } returns def(id = id)
        val result = controller.getById(id, namespaceId)
        result.id shouldBe id
        result.namespaceId shouldBe namespaceId
    }
    "getById throws 404 when definition does not exist" {
        val id = UUID.randomUUID()
        every { service.findById(id, withRemoved = true) } returns null
        shouldThrow<ResourceNotFoundException> { controller.getById(id, namespaceId) }
    }
    "getById throws 404 when definition belongs to a different namespace" {
        val id = UUID.randomUUID()
        every { service.findById(id, withRemoved = true) } returns def(id = id, nsId = UUID.randomUUID())
        shouldThrow<ResourceNotFoundException> { controller.getById(id, namespaceId) }
    }

    // create
    "create delegates to service and returns mapped resource" {
        val r = resource()
        val saved = def(name = r.name)
        every { service.create(any()) } returns saved
        val result = controller.create(namespaceId, r)
        result.name shouldBe saved.name
        result.namespaceId shouldBe namespaceId
        verify(exactly = 1) { service.create(any()) }
    }
    "create sets namespaceId from query param" {
        val saved = def()
        every { service.create(any()) } answers { firstArg<CaseDefinition>().namespaceId shouldBe namespaceId; saved }
        controller.create(namespaceId, resource())
    }
    "create propagates userGroupId" {
        val groupId = UUID.randomUUID()
        val saved = def(userGroupId = groupId)
        every { service.create(any()) } answers { firstArg<CaseDefinition>().userGroupId shouldBe groupId; saved }
        controller.create(namespaceId, resource(userGroupId = groupId))
    }
    "create propagates userId" {
        val uid = UUID.randomUUID()
        val saved = def(userId = uid)
        every { service.create(any()) } answers { firstArg<CaseDefinition>().userId shouldBe uid; saved }
        controller.create(namespaceId, resource(userId = uid))
    }
    "create WEEKLY without dayOfWeek throws 400" {
        shouldThrow<ResponseStatusException> { controller.create(namespaceId, resource(frequency = ScheduleFrequency.WEEKLY)) }
    }

    // update
    "update applies changes" {
        val id = UUID.randomUUID()
        val existing = def(id = id, name = "old")
        every { service.findById(id) } returns existing
        every { service.update(any()) } returns existing.copy(name = "new", enabled = false)
        val result = controller.update(id, namespaceId, resource(id = id, name = "new", enabled = false))
        result.name shouldBe "new"
        result.enabled shouldBe false
    }
    "update throws 404 when definition does not exist" {
        val id = UUID.randomUUID()
        every { service.findById(id) } returns null
        shouldThrow<ResourceNotFoundException> { controller.update(id, namespaceId, resource()) }
    }
    "update throws 404 when definition belongs to a different namespace" {
        val id = UUID.randomUUID()
        every { service.findById(id) } returns def(id = id, nsId = UUID.randomUUID())
        shouldThrow<ResourceNotFoundException> { controller.update(id, namespaceId, resource()) }
    }

    // toggle
    "toggle flips enabled from true to false" {
        val id = UUID.randomUUID()
        val existing = def(id = id, enabled = true)
        every { service.findById(id) } returns existing
        every { service.setEnabled(id, false) } returns existing.copy(enabled = false)
        controller.toggle(id, namespaceId).enabled shouldBe false
    }
    "toggle throws 404 when definition does not exist" {
        val id = UUID.randomUUID()
        every { service.findById(id) } returns null
        shouldThrow<ResourceNotFoundException> { controller.toggle(id, namespaceId) }
    }
    "toggle throws 404 when definition belongs to a different namespace" {
        val id = UUID.randomUUID()
        every { service.findById(id) } returns def(id = id, nsId = UUID.randomUUID())
        shouldThrow<ResourceNotFoundException> { controller.toggle(id, namespaceId) }
    }

    // delete
    "delete calls service.delete when definition exists and belongs to namespace" {
        val id = UUID.randomUUID()
        every { service.findById(id) } returns def(id = id)
        every { service.delete(id) } returns true
        controller.delete(id, namespaceId)
        verify(exactly = 1) { service.delete(id) }
    }
    "delete throws 404 when definition does not exist" {
        val id = UUID.randomUUID()
        every { service.findById(id) } returns null
        shouldThrow<ResourceNotFoundException> { controller.delete(id, namespaceId) }
    }
    "delete throws 404 when definition belongs to a different namespace" {
        val id = UUID.randomUUID()
        every { service.findById(id) } returns def(id = id, nsId = UUID.randomUUID())
        shouldThrow<ResourceNotFoundException> { controller.delete(id, namespaceId) }
    }

    // toResource mapping
    "toResource maps namespace-only targeting" {
        every { service.findByParent(namespaceId) } returns listOf(def())
        val result = controller.list(namespaceId).first()
        result.namespaceId shouldBe namespaceId
        result.userGroupId shouldBe null
        result.userId shouldBe null
    }
    "toResource maps userGroupId" {
        val groupId = UUID.randomUUID()
        every { service.findByParent(namespaceId) } returns listOf(def(userGroupId = groupId))
        controller.list(namespaceId).first().userGroupId shouldBe groupId
    }
    "toResource maps userId" {
        val uid = UUID.randomUUID()
        every { service.findByParent(namespaceId) } returns listOf(def(userId = uid))
        controller.list(namespaceId).first().userId shouldBe uid
    }
    "toResource maps DAILY cron" {
        every { service.findByParent(namespaceId) } returns listOf(def(cronExpression = "0 9 * * *"))
        val result = controller.list(namespaceId).first()
        result.frequency shouldBe ScheduleFrequency.DAILY
        result.timeUtc shouldBe "09:00"
        result.dayOfWeek shouldBe null
    }
    "toResource maps WEEKLY cron" {
        every { service.findByParent(namespaceId) } returns listOf(def(cronExpression = "30 14 * * FRI"))
        val result = controller.list(namespaceId).first()
        result.frequency shouldBe ScheduleFrequency.WEEKLY
        result.timeUtc shouldBe "14:30"
        result.dayOfWeek shouldBe "FRI"
    }

    // toDomain mapping
    "toDomain DAILY builds correct cron" {
        val saved = def(cronExpression = "0 9 * * *")
        every { service.create(any()) } answers { firstArg<CaseDefinition>().cronExpression shouldBe "0 9 * * *"; saved }
        controller.create(namespaceId, resource(frequency = ScheduleFrequency.DAILY, timeUtc = "09:00"))
    }
    "toDomain WEEKLY builds correct cron" {
        val saved = def(cronExpression = "30 14 * * FRI")
        every { service.create(any()) } answers { firstArg<CaseDefinition>().cronExpression shouldBe "30 14 * * FRI"; saved }
        controller.create(namespaceId, resource(frequency = ScheduleFrequency.WEEKLY, timeUtc = "14:30", dayOfWeek = "FRI"))
    }
})
