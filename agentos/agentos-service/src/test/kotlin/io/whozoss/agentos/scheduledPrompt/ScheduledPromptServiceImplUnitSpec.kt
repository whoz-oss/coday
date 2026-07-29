package io.whozoss.agentos.scheduledPrompt

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.whozoss.agentos.agentConfig.AgentConfig
import io.whozoss.agentos.agentConfig.AgentConfigService
import io.whozoss.agentos.exception.BadRequestException
import io.whozoss.agentos.exception.ConflictException
import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.exception.UnprocessableEntityException
import io.whozoss.agentos.namespace.Namespace
import io.whozoss.agentos.namespace.NamespaceService
import io.whozoss.agentos.prompt.Prompt
import io.whozoss.agentos.prompt.PromptService
import io.whozoss.agentos.sdk.api.scheduledPrompt.SchedulerEndType
import io.whozoss.agentos.sdk.api.scheduledPrompt.SchedulerUnit
import io.whozoss.agentos.sdk.entity.EntityMetadata
import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.UUID

class ScheduledPromptServiceImplUnitSpec : StringSpec() {
    private val agentConfigService = mockk<AgentConfigService>(relaxed = true)
    private val promptService = mockk<PromptService>(relaxed = true)
    private val namespaceService = mockk<NamespaceService>(relaxed = true)

    // Fixed clock: 2026-01-01 07:00 UTC — before the default timeUtc of 08:00
    private val fixedNow = Instant.parse("2026-01-01T07:00:00Z")
    private val fixedClock = Clock.fixed(fixedNow, ZoneOffset.UTC)

    private fun newService(repo: ScheduledPromptRepository = InMemoryScheduledPromptRepository()): ScheduledPromptServiceImpl =
        ScheduledPromptServiceImpl(repo, agentConfigService, promptService, namespaceService, fixedClock)

    private val namespaceId: UUID = UUID.randomUUID()
    private val agentConfigId: UUID = UUID.randomUUID()
    private val promptId: UUID = UUID.randomUUID()
    private val today: LocalDate = LocalDate.of(2026, 1, 1)

    private fun defaultAgent(nsId: UUID? = namespaceId) = AgentConfig(
        metadata = EntityMetadata(id = agentConfigId, version = 0L),
        namespaceId = nsId,
        name = "agent",
    )

    private fun defaultPrompt(linkedAgentId: UUID? = null) = Prompt(
        metadata = EntityMetadata(id = promptId, version = 0L),
        namespaceId = namespaceId,
        agentConfigId = linkedAgentId,
        name = "my-prompt",
        content = listOf("Hello"),
    )

    private fun recurrence(
        unit: SchedulerUnit = SchedulerUnit.WEEK,
        days: List<DayOfWeek> = emptyList(),
        timeUtc: LocalTime = LocalTime.of(8, 0),
    ) = Recurrence(unit = unit, days = days, timeUtc = timeUtc)

    private fun planning(
        startDate: LocalDate = today,
        endType: SchedulerEndType = SchedulerEndType.NEVER,
        endDate: LocalDate? = null,
        maxOccurrenceCount: Int? = null,
    ) = Planning(startDate = startDate, endType = endType, endDate = endDate, maxOccurrenceCount = maxOccurrenceCount)

    private fun sp(
        name: String = "my-prompt",
        nsId: UUID? = namespaceId,
        userId: UUID? = null,
        description: String? = null,
        enabled: Boolean = true,
        recurrence: Recurrence = recurrence(),
        planning: Planning = planning(),
        agentId: UUID = agentConfigId,
        pId: UUID = promptId,
    ) = ScheduledPrompt(
        metadata = EntityMetadata(id = UUID.randomUUID()),
        namespaceId = nsId,
        userId = userId,
        agentConfigId = agentId,
        promptTemplateId = pId,
        name = name,
        description = description,
        recurrence = recurrence,
        planning = planning,
        enabled = enabled,
        // nextRunAt sentinel — overwritten by the service on create/update
        nextRunAt = Instant.EPOCH,
    )

    init {
        beforeTest {
            every { agentConfigService.findById(agentConfigId) } returns defaultAgent()
            every { promptService.findById(promptId) } returns defaultPrompt()
            every { namespaceService.findById(namespaceId) } returns Namespace(
                metadata = EntityMetadata(id = namespaceId),
                name = "test-namespace",
            )
            every { promptService.create(any<Prompt>()) } answers {
                val p = it.invocation.args[0] as Prompt
                // Return a prompt with the known promptId so that the subsequent
                // promptService.findById(promptId) mock resolves correctly in create().
                p.copy(metadata = EntityMetadata(id = promptId, version = 0L))
            }
            every { promptService.update(any<Prompt>()) } answers {
                it.invocation.args[0] as Prompt
            }
            every { promptService.delete(any()) } returns true
        }

        // -------------------------------------------------------------------------
        // Name is now free-form (no slug constraint)
        // -------------------------------------------------------------------------

        "create accepts free-form name with spaces" {
            newService().create(sp(name = "Daily Digest")).name shouldBe "Daily Digest"
        }

        "create accepts name starting with uppercase" {
            newService().create(sp(name = "Daily-digest")).name shouldBe "Daily-digest"
        }

        "create accepts name starting with digit" {
            newService().create(sp(name = "1digest")).name shouldBe "1digest"
        }

        "create accepts name with special characters" {
            newService().create(sp(name = "Rapport hébdo!")).name shouldBe "Rapport hébdo!"
        }

        // -------------------------------------------------------------------------
        // tripleKey collision: names that slugify to the same value conflict
        // -------------------------------------------------------------------------

        "create throws ConflictException when two names normalize to the same slug in the same scope" {
            val svc = newService()
            svc.create(sp(name = "Daily Digest"))
            shouldThrow<ConflictException> { svc.create(sp(name = "daily digest")) }
        }

        "create succeeds when two names normalize to different slugs" {
            val svc = newService()
            svc.create(sp(name = "Daily Digest"))
            svc.create(sp(name = "Weekly Report")).name shouldBe "Weekly Report"
        }

        // -------------------------------------------------------------------------
        // agentConfigId validation
        // -------------------------------------------------------------------------

        "create throws ResourceNotFoundException when agentConfigId does not exist" {
            val unknownId = UUID.randomUUID()
            every { agentConfigService.findById(unknownId) } returns null
            shouldThrow<ResourceNotFoundException> { newService().create(sp(agentId = unknownId)) }
        }

        "create throws UnprocessableEntityException for filesystem-only agent" {
            val fsId = UUID.randomUUID()
            every { agentConfigService.findById(fsId) } returns AgentConfig(
                metadata = EntityMetadata(id = fsId, version = null),
                namespaceId = namespaceId,
                name = "fs-agent",
            )
            shouldThrow<UnprocessableEntityException> { newService().create(sp(agentId = fsId)) }
        }

        "create throws BadRequestException when agentConfig belongs to a different namespace" {
            val otherNs = UUID.randomUUID()
            every { agentConfigService.findById(agentConfigId) } returns defaultAgent(nsId = otherNs)
            shouldThrow<BadRequestException> { newService().create(sp(nsId = namespaceId)) }
        }

        "create succeeds with platform agent (namespaceId == null) from any scope" {
            every { agentConfigService.findById(agentConfigId) } returns defaultAgent(nsId = null)
            newService().create(sp()).agentConfigId shouldBe agentConfigId
        }

        // -------------------------------------------------------------------------
        // promptId validation
        // -------------------------------------------------------------------------

        "create throws ResourceNotFoundException when promptId does not exist" {
            val unknownPId = UUID.randomUUID()
            every { promptService.findById(unknownPId) } returns null
            shouldThrow<ResourceNotFoundException> { newService().create(sp(pId = unknownPId)) }
        }

        "create succeeds when prompt has no agentConfigId" {
            every { promptService.findById(promptId) } returns defaultPrompt(linkedAgentId = null)
            newService().create(sp()).promptTemplateId shouldBe promptId
        }

        "create throws BadRequestException when prompt has agentConfigId" {
            every { promptService.findById(promptId) } returns defaultPrompt(linkedAgentId = agentConfigId)
            val ex = shouldThrow<BadRequestException> { newService().create(sp()) }
            ex.message shouldContain "agentConfigId"
        }

        // -------------------------------------------------------------------------
        // End condition validation (planning)
        // -------------------------------------------------------------------------

        "create throws BadRequestException when endType is ON_DATE and endDate is null" {
            val ex = shouldThrow<BadRequestException> {
                newService().create(sp(planning = planning(endType = SchedulerEndType.ON_DATE, endDate = null)))
            }
            ex.message shouldContain "endDate"
        }

        "create throws BadRequestException when endDate is not after startDate" {
            val ex = shouldThrow<BadRequestException> {
                newService().create(sp(planning = planning(endType = SchedulerEndType.ON_DATE, startDate = today, endDate = today)))
            }
            ex.message shouldContain "endDate"
        }

        "create succeeds when endDate is after startDate" {
            newService().create(
                sp(planning = planning(endType = SchedulerEndType.ON_DATE, endDate = today.plusDays(1))),
            ).planning.endType shouldBe SchedulerEndType.ON_DATE
        }

        "create throws BadRequestException when endType is OCCURRENCES and maxOccurrenceCount is null" {
            val ex = shouldThrow<BadRequestException> {
                newService().create(sp(planning = planning(endType = SchedulerEndType.OCCURRENCES, maxOccurrenceCount = null)))
            }
            ex.message shouldContain "maxOccurrenceCount"
        }

        "create throws BadRequestException when maxOccurrenceCount is 0" {
            val ex = shouldThrow<BadRequestException> {
                newService().create(sp(planning = planning(endType = SchedulerEndType.OCCURRENCES, maxOccurrenceCount = 0)))
            }
            ex.message shouldContain "maxOccurrenceCount"
        }

        "create throws BadRequestException when maxOccurrenceCount is negative" {
            val ex = shouldThrow<BadRequestException> {
                newService().create(sp(planning = planning(endType = SchedulerEndType.OCCURRENCES, maxOccurrenceCount = -1)))
            }
            ex.message shouldContain "maxOccurrenceCount"
        }

        "create succeeds when endType is OCCURRENCES and maxOccurrenceCount is 1" {
            newService().create(
                sp(planning = planning(endType = SchedulerEndType.OCCURRENCES, maxOccurrenceCount = 1)),
            ).planning.maxOccurrenceCount shouldBe 1
        }

        "create succeeds when endType is NEVER" {
            newService().create(sp(planning = planning(endType = SchedulerEndType.NEVER))).planning.endType shouldBe SchedulerEndType.NEVER
        }

        // -------------------------------------------------------------------------
        // Conflict (tripleKey uniqueness)
        // -------------------------------------------------------------------------

        "create throws ConflictException when name is already taken in the same scope" {
            val svc = newService()
            svc.create(sp(name = "my-prompt"))
            shouldThrow<ConflictException> { svc.create(sp(name = "my-prompt")) }
        }

        "create succeeds with same name in different namespace" {
            val otherNs = UUID.randomUUID()
            every { agentConfigService.findById(agentConfigId) } returns defaultAgent(nsId = null)
            val svc = newService()
            svc.create(sp(name = "my-prompt", nsId = namespaceId))
            svc.create(sp(name = "my-prompt", nsId = otherNs)).name shouldBe "my-prompt"
        }

        // -------------------------------------------------------------------------
        // Basic CRUD
        // -------------------------------------------------------------------------

        "create persists and returns the entity" {
            val svc = newService()
            val saved = svc.create(sp("daily-digest", recurrence = recurrence(unit = SchedulerUnit.WEEK)))
            saved.name shouldBe "daily-digest"
            saved.namespaceId shouldBe namespaceId
            saved.recurrence.unit shouldBe SchedulerUnit.WEEK
            saved.enabled.shouldBeTrue()
        }

        "create calculates nextRunAt (not EPOCH sentinel)" {
            val saved = newService().create(sp())
            saved.nextRunAt shouldBe Instant.parse("2026-01-01T08:00:00Z") // fixedNow=07:00, timeUtc=08:00 → today
        }

        "create sets lastRunAt to null" {
            newService().create(sp()).lastRunAt shouldBe null
        }

        "update recalculates nextRunAt" {
            val svc = newService()
            val saved = svc.create(sp())
            val updated = svc.update(saved.copy(recurrence = recurrence(unit = SchedulerUnit.WEEK, timeUtc = LocalTime.of(9, 0))))
            updated.nextRunAt shouldBe Instant.parse("2026-01-01T09:00:00Z")
        }

        "toggle re-enables and recalculates nextRunAt" {
            val svc = newService()
            val saved = svc.create(sp(enabled = true))
            val disabled = svc.toggle(saved.id)
            disabled.enabled.shouldBeFalse()
            val reEnabled = svc.toggle(disabled.id)
            reEnabled.enabled.shouldBeTrue()
            reEnabled.nextRunAt shouldBe Instant.parse("2026-01-01T08:00:00Z")
        }

        "findById returns the entity when it exists" {
            val svc = newService()
            val saved = svc.create(sp())
            svc.findById(saved.id) shouldBe saved
        }

        "findById returns null when entity does not exist" {
            newService().findById(UUID.randomUUID()).shouldBeNull()
        }

        "update persists changes including free-form name" {
            val svc = newService()
            val saved = svc.create(sp(name = "original"))
            val updated = svc.update(saved.copy(name = "Updated Name", enabled = false))
            updated.name shouldBe "Updated Name"
            updated.enabled.shouldBeFalse()
        }

        "update throws ResourceNotFoundException when promptId does not exist" {
            val svc = newService()
            val saved = svc.create(sp())
            val unknownPId = UUID.randomUUID()
            every { promptService.findById(unknownPId) } returns null
            shouldThrow<ResourceNotFoundException> { svc.update(saved.copy(promptTemplateId = unknownPId)) }
        }

        "delete returns true and soft-deletes" {
            val svc = newService()
            val saved = svc.create(sp())
            svc.delete(saved.id).shouldBeTrue()
            svc.findByParent(namespaceId).shouldBeEmpty()
        }

        "delete returns false when entity does not exist" {
            newService().delete(UUID.randomUUID()).shouldBeFalse()
        }

        "deleteByParent soft-deletes all entities in the namespace" {
            val svc = newService()
            svc.create(sp("sp-1"))
            svc.create(sp("sp-2"))
            svc.deleteByParent(namespaceId) shouldBe 2
            svc.findByParent(namespaceId).shouldBeEmpty()
        }

        "deleteByParent does not affect other namespaces" {
            val otherNs = UUID.randomUUID()
            every { agentConfigService.findById(agentConfigId) } returns defaultAgent(nsId = null)
            val svc = newService()
            svc.create(sp("in-ns", nsId = namespaceId))
            svc.create(sp("other", nsId = otherNs))
            svc.deleteByParent(namespaceId)
            svc.findByParent(otherNs) shouldHaveSize 1
        }

        // -------------------------------------------------------------------------
        // findByParent and findPlatform
        // -------------------------------------------------------------------------

        "findByParent returns entities scoped to the given namespace" {
            val otherNs = UUID.randomUUID()
            every { agentConfigService.findById(agentConfigId) } returns defaultAgent(nsId = null)
            val svc = newService()
            svc.create(sp("in-ns", nsId = namespaceId))
            svc.create(sp("other-ns", nsId = otherNs))
            val result = svc.findByParent(namespaceId)
            result shouldHaveSize 1
            result.first().name shouldBe "in-ns"
        }

        "findByParent returns empty list when namespace has no entities" {
            newService().findByParent(UUID.randomUUID()).shouldBeEmpty()
        }

        "findByParent returns entities sorted by name" {
            val svc = newService()
            svc.create(sp("zeta"))
            svc.create(sp("alpha"))
            svc.create(sp("mu"))
            svc.findByParent(namespaceId).map { it.name } shouldBe listOf("alpha", "mu", "zeta")
        }

        "findPlatform returns only platform-level entities" {
            every { agentConfigService.findById(agentConfigId) } returns defaultAgent(nsId = null)
            val svc = newService()
            svc.create(sp("platform-sp", nsId = null))
            svc.create(sp("ns-sp", nsId = namespaceId))
            val platform = svc.findPlatform()
            platform shouldHaveSize 1
            platform.first().name shouldBe "platform-sp"
            platform.first().namespaceId shouldBe null
        }

        // -------------------------------------------------------------------------
        // toggle
        // -------------------------------------------------------------------------

        "toggle flips enabled from true to false" {
            val svc = newService()
            svc.toggle(svc.create(sp(enabled = true)).id).enabled.shouldBeFalse()
        }

        "toggle flips enabled from false to true" {
            val svc = newService()
            svc.toggle(svc.create(sp(enabled = false)).id).enabled.shouldBeTrue()
        }

        "toggle throws ResourceNotFoundException when entity does not exist" {
            shouldThrow<ResourceNotFoundException> { newService().toggle(UUID.randomUUID()) }
        }

        // -------------------------------------------------------------------------
        // findEffective — overlay fold
        // -------------------------------------------------------------------------

        "findEffective returns all four layers when names are distinct" {
            every { agentConfigService.findById(agentConfigId) } returns defaultAgent(nsId = null)
            val svc = newService()
            val user = UUID.randomUUID()
            svc.create(sp("platform-only", nsId = null, userId = null))
            svc.create(sp("user-only", nsId = null, userId = user))
            svc.create(sp("ns-only", nsId = namespaceId, userId = null))
            svc.create(sp("user-ns-only", nsId = namespaceId, userId = user))
            val effective = svc.findEffective(namespaceId, user)
            effective shouldHaveSize 4
            effective.map { it.name } shouldBe listOf("ns-only", "platform-only", "user-ns-only", "user-only")
        }

        "findEffective higher layer overrides lower layer by name" {
            every { agentConfigService.findById(agentConfigId) } returns defaultAgent(nsId = null)
            val svc = newService()
            val user = UUID.randomUUID()
            svc.create(sp("deploy", nsId = null, userId = null))
            val nsLayer = svc.create(sp("deploy", nsId = namespaceId, userId = null, recurrence = recurrence(days = listOf(DayOfWeek.MONDAY))))
            val effective = svc.findEffective(namespaceId, user)
            effective shouldHaveSize 1
            effective.first().id shouldBe nsLayer.id
        }

        "findEffective user x namespace wins over all other layers" {
            every { agentConfigService.findById(agentConfigId) } returns defaultAgent(nsId = null)
            val svc = newService()
            val user = UUID.randomUUID()
            svc.create(sp("deploy", nsId = null, userId = null))
            svc.create(sp("deploy", nsId = null, userId = user))
            svc.create(sp("deploy", nsId = namespaceId, userId = null))
            val winner = svc.create(sp("deploy", nsId = namespaceId, userId = user, recurrence = recurrence(days = listOf(DayOfWeek.FRIDAY))))
            svc.findEffective(namespaceId, user).let {
                it shouldHaveSize 1
                it.first().id shouldBe winner.id
            }
        }

        "findEffective returns results sorted by name" {
            every { agentConfigService.findById(agentConfigId) } returns defaultAgent(nsId = null)
            val svc = newService()
            val user = UUID.randomUUID()
            svc.create(sp("zebra", nsId = null, userId = null))
            svc.create(sp("alpha", nsId = namespaceId, userId = null))
            svc.create(sp("middle", nsId = null, userId = user))
            svc.findEffective(namespaceId, user).map { it.name } shouldBe listOf("alpha", "middle", "zebra")
        }

        "findEffective returns empty when no entities exist" {
            newService().findEffective(UUID.randomUUID(), UUID.randomUUID()).shouldBeEmpty()
        }

        // -------------------------------------------------------------------------
        // Round-trips
        // -------------------------------------------------------------------------

        "recurrence round-trips" {
            val r = recurrence(unit = SchedulerUnit.MONTH, days = listOf(DayOfWeek.MONDAY), timeUtc = LocalTime.of(14, 30))
            newService().create(sp(recurrence = r)).recurrence shouldBe r
        }

        "planning NEVER round-trips" {
            val p = planning(startDate = today, endType = SchedulerEndType.NEVER)
            newService().create(sp(planning = p)).planning shouldBe p
        }

        "planning ON_DATE round-trips" {
            val p = planning(endType = SchedulerEndType.ON_DATE, endDate = today.plusMonths(3))
            newService().create(sp(planning = p)).planning shouldBe p
        }

        "planning OCCURRENCES round-trips" {
            val p = planning(endType = SchedulerEndType.OCCURRENCES, maxOccurrenceCount = 10)
            newService().create(sp(planning = p)).planning shouldBe p
        }

        "description round-trips" {
            newService().create(sp(description = "Daily digest")).description shouldBe "Daily digest"
        }

        "promptTemplateId round-trips" {
            newService().create(sp()).promptTemplateId shouldBe promptId
        }

        // -------------------------------------------------------------------------
        // createWithPrompt
        // -------------------------------------------------------------------------

        "createWithPrompt creates a linked prompt with scheduled--{nameSlug} pattern" {
            val svc = newService()
            svc.createWithPrompt(sp(name = "Daily Digest"), "Hello")
            verify { promptService.create(match { it.name == "scheduled--daily-digest" && it.agentConfigId == null }) }
        }

        "createWithPrompt passes promptContent to the prompt" {
            val svc = newService()
            svc.createWithPrompt(sp(), "My content")
            verify { promptService.create(match { it.content == listOf("My content") }) }
        }

        "createWithPrompt returns saved entity and promptContent" {
            val svc = newService()
            val (saved, content) = svc.createWithPrompt(sp(name = "digest"), "Hello")
            saved.name shouldBe "digest"
            content shouldBe "Hello"
        }

        "createWithPrompt throws ResourceNotFoundException when namespace not found" {
            every { namespaceService.findById(namespaceId) } returns null
            shouldThrow<ResourceNotFoundException> { newService().createWithPrompt(sp(), "Hello") }
        }

        "createWithPrompt throws BadRequestException when endType is ON_DATE and endDate is null" {
            shouldThrow<BadRequestException> {
                newService().createWithPrompt(
                    sp(planning = planning(endType = SchedulerEndType.ON_DATE, endDate = null)),
                    "Hello",
                )
            }
        }

        // -------------------------------------------------------------------------
        // updateWithPrompt
        // -------------------------------------------------------------------------

        "updateWithPrompt updates prompt name and content" {
            val svc = newService()
            val saved = svc.create(sp(name = "original"))
            svc.updateWithPrompt(saved.copy(name = "New Name"), "Updated content")
            verify { promptService.update(match { it.name == "scheduled--new-name" && it.content == listOf("Updated content") }) }
        }

        "updateWithPrompt throws ResourceNotFoundException when linked prompt not found" {
            val svc = newService()
            val saved = svc.create(sp())
            val unknownPId = UUID.randomUUID()
            every { promptService.findById(unknownPId) } returns null
            shouldThrow<ResourceNotFoundException> {
                svc.updateWithPrompt(saved.copy(promptTemplateId = unknownPId), "Hello")
            }
        }

        // -------------------------------------------------------------------------
        // deleteWithPrompt
        // -------------------------------------------------------------------------

        "deleteWithPrompt deletes entity and linked prompt" {
            val svc = newService()
            val saved = svc.create(sp())
            svc.deleteWithPrompt(saved.id).shouldBeTrue()
            verify { promptService.delete(saved.promptTemplateId) }
        }

        "deleteWithPrompt returns false when entity does not exist" {
            newService().deleteWithPrompt(UUID.randomUUID()).shouldBeFalse()
        }
    }
}
