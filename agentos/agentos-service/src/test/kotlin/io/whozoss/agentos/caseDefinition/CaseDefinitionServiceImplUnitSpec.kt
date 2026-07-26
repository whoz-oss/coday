package io.whozoss.agentos.caseDefinition

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
import io.whozoss.agentos.agentConfig.AgentConfig
import io.whozoss.agentos.agentConfig.AgentConfigService
import io.whozoss.agentos.exception.BadRequestException
import io.whozoss.agentos.exception.ConflictException
import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.exception.UnprocessableEntityException
import io.whozoss.agentos.prompt.Prompt
import io.whozoss.agentos.prompt.PromptService
import io.whozoss.agentos.sdk.api.caseDefinition.SchedulerEndType
import io.whozoss.agentos.sdk.api.caseDefinition.SchedulerUnit
import io.whozoss.agentos.sdk.entity.EntityMetadata
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/**
 * Unit tests for [CaseDefinitionServiceImpl].
 *
 * Uses [InMemoryCaseDefinitionRepository] for fast, isolated persistence.
 * Access-control filtering (DEPLOYED_TO graph) is not tested here.
 */
class CaseDefinitionServiceImplUnitSpec : StringSpec() {
    private val agentConfigService = mockk<AgentConfigService>(relaxed = true)
    private val promptService = mockk<PromptService>(relaxed = true)

    private fun newService(repo: CaseDefinitionRepository = InMemoryCaseDefinitionRepository()): CaseDefinitionServiceImpl =
        CaseDefinitionServiceImpl(repo, agentConfigService, promptService)

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
        every: Int = 1,
        unit: SchedulerUnit = SchedulerUnit.DAY,
        days: List<DayOfWeek> = emptyList(),
        timeUtc: LocalTime = LocalTime.of(8, 0),
    ) = Recurrence(every = every, unit = unit, days = days, timeUtc = timeUtc)

    private fun planning(
        startDate: LocalDate = today,
        endType: SchedulerEndType = SchedulerEndType.NEVER,
        endDate: LocalDate? = null,
        occurrenceCount: Int? = null,
    ) = Planning(startDate = startDate, endType = endType, endDate = endDate, occurrenceCount = occurrenceCount)

    private fun def(
        name: String = "my-def",
        nsId: UUID? = namespaceId,
        userId: UUID? = null,
        description: String? = null,
        enabled: Boolean = true,
        recurrence: Recurrence = recurrence(),
        planning: Planning = planning(),
        agentId: UUID = agentConfigId,
        pId: UUID = promptId,
    ) = CaseDefinition(
        metadata = EntityMetadata(id = UUID.randomUUID()),
        namespaceId = nsId,
        userId = userId,
        agentConfigId = agentId,
        promptId = pId,
        name = name,
        description = description,
        recurrence = recurrence,
        planning = planning,
        enabled = enabled,
    )

    init {
        beforeTest {
            every { agentConfigService.findById(agentConfigId) } returns defaultAgent()
            every { promptService.findById(promptId) } returns defaultPrompt()
        }

        // -------------------------------------------------------------------------
        // recurrence.every validation
        // -------------------------------------------------------------------------

        "create rejects every == 0" {
            shouldThrow<IllegalArgumentException> { newService().create(def(recurrence = recurrence(every = 0))) }
        }

        "create rejects every < 0" {
            shouldThrow<IllegalArgumentException> { newService().create(def(recurrence = recurrence(every = -1))) }
        }

        "create accepts every == 1" {
            newService().create(def(recurrence = recurrence(every = 1))).recurrence.every shouldBe 1
        }

        "create accepts every == 3" {
            newService().create(def(recurrence = recurrence(every = 3))).recurrence.every shouldBe 3
        }

        // -------------------------------------------------------------------------
        // Slug validation on create
        // -------------------------------------------------------------------------

        "create accepts valid slug names" {
            newService().create(def(name = "daily-standup")).name shouldBe "daily-standup"
        }

        "create accepts single-word slug" {
            newService().create(def(name = "standup")).name shouldBe "standup"
        }

        "create accepts slug with numbers" {
            newService().create(def(name = "weekly-sync-v2")).name shouldBe "weekly-sync-v2"
        }

        "create rejects name starting with uppercase" {
            shouldThrow<IllegalArgumentException> { newService().create(def(name = "Daily-standup")) }
        }

        "create rejects name with spaces" {
            shouldThrow<IllegalArgumentException> { newService().create(def(name = "daily standup")) }
        }

        "create rejects name starting with digit" {
            shouldThrow<IllegalArgumentException> { newService().create(def(name = "1standup")) }
        }

        "create rejects name with trailing hyphen" {
            shouldThrow<IllegalArgumentException> { newService().create(def(name = "standup-")) }
        }

        "create rejects name with double hyphens" {
            shouldThrow<IllegalArgumentException> { newService().create(def(name = "daily--standup")) }
        }

        // -------------------------------------------------------------------------
        // agentConfigId validation
        // -------------------------------------------------------------------------

        "create throws ResourceNotFoundException when agentConfigId does not exist" {
            val unknownId = UUID.randomUUID()
            every { agentConfigService.findById(unknownId) } returns null
            shouldThrow<ResourceNotFoundException> { newService().create(def(agentId = unknownId)) }
        }

        "create throws UnprocessableEntityException for filesystem-only agent" {
            val fsId = UUID.randomUUID()
            every { agentConfigService.findById(fsId) } returns AgentConfig(
                metadata = EntityMetadata(id = fsId, version = null),
                namespaceId = namespaceId,
                name = "fs-agent",
            )
            shouldThrow<UnprocessableEntityException> { newService().create(def(agentId = fsId)) }
        }

        "create throws BadRequestException when agentConfig belongs to a different namespace" {
            val otherNs = UUID.randomUUID()
            every { agentConfigService.findById(agentConfigId) } returns defaultAgent(nsId = otherNs)
            shouldThrow<BadRequestException> { newService().create(def(nsId = namespaceId)) }
        }

        "create succeeds with platform agent (namespaceId == null) from any scope" {
            every { agentConfigService.findById(agentConfigId) } returns defaultAgent(nsId = null)
            newService().create(def()).agentConfigId shouldBe agentConfigId
        }

        // -------------------------------------------------------------------------
        // promptId validation
        // -------------------------------------------------------------------------

        "create throws ResourceNotFoundException when promptId does not exist" {
            val unknownPId = UUID.randomUUID()
            every { promptService.findById(unknownPId) } returns null
            shouldThrow<ResourceNotFoundException> { newService().create(def(pId = unknownPId)) }
        }

        "create succeeds when prompt has no agentConfigId" {
            every { promptService.findById(promptId) } returns defaultPrompt(linkedAgentId = null)
            newService().create(def()).promptId shouldBe promptId
        }

        "create throws BadRequestException when prompt has agentConfigId (matching)" {
            every { promptService.findById(promptId) } returns defaultPrompt(linkedAgentId = agentConfigId)
            val ex = shouldThrow<BadRequestException> { newService().create(def()) }
            ex.message shouldContain "agentConfigId"
        }

        "create throws BadRequestException when prompt has agentConfigId (different)" {
            val otherId = UUID.randomUUID()
            every { promptService.findById(promptId) } returns defaultPrompt(linkedAgentId = otherId)
            val ex = shouldThrow<BadRequestException> { newService().create(def()) }
            ex.message shouldContain "agentConfigId"
        }

        // -------------------------------------------------------------------------
        // End condition validation (planning)
        // -------------------------------------------------------------------------

        "create throws BadRequestException when endType is ON_DATE and endDate is null" {
            val ex = shouldThrow<BadRequestException> {
                newService().create(def(planning = planning(endType = SchedulerEndType.ON_DATE, endDate = null)))
            }
            ex.message shouldContain "endDate"
        }

        "create throws BadRequestException when endDate is not after startDate" {
            val ex = shouldThrow<BadRequestException> {
                newService().create(def(planning = planning(endType = SchedulerEndType.ON_DATE, startDate = today, endDate = today)))
            }
            ex.message shouldContain "endDate"
        }

        "create succeeds when endDate is after startDate" {
            newService().create(
                def(planning = planning(endType = SchedulerEndType.ON_DATE, endDate = today.plusDays(1))),
            ).planning.endType shouldBe SchedulerEndType.ON_DATE
        }

        "create throws BadRequestException when endType is OCCURRENCES and occurrenceCount is null" {
            val ex = shouldThrow<BadRequestException> {
                newService().create(def(planning = planning(endType = SchedulerEndType.OCCURRENCES, occurrenceCount = null)))
            }
            ex.message shouldContain "occurrenceCount"
        }

        "create throws BadRequestException when occurrenceCount is 0" {
            val ex = shouldThrow<BadRequestException> {
                newService().create(def(planning = planning(endType = SchedulerEndType.OCCURRENCES, occurrenceCount = 0)))
            }
            ex.message shouldContain "occurrenceCount"
        }

        "create throws BadRequestException when occurrenceCount is negative" {
            val ex = shouldThrow<BadRequestException> {
                newService().create(def(planning = planning(endType = SchedulerEndType.OCCURRENCES, occurrenceCount = -1)))
            }
            ex.message shouldContain "occurrenceCount"
        }

        "create succeeds when endType is OCCURRENCES and occurrenceCount is 1" {
            newService().create(
                def(planning = planning(endType = SchedulerEndType.OCCURRENCES, occurrenceCount = 1)),
            ).planning.occurrenceCount shouldBe 1
        }

        "create succeeds when endType is NEVER" {
            newService().create(def(planning = planning(endType = SchedulerEndType.NEVER))).planning.endType shouldBe SchedulerEndType.NEVER
        }

        // -------------------------------------------------------------------------
        // Conflict (tripleKey uniqueness)
        // -------------------------------------------------------------------------

        "create throws ConflictException when name is already taken in the same scope" {
            val svc = newService()
            svc.create(def(name = "my-def"))
            shouldThrow<ConflictException> { svc.create(def(name = "my-def")) }
        }

        "create succeeds with same name in different namespace" {
            val otherNs = UUID.randomUUID()
            every { agentConfigService.findById(agentConfigId) } returns defaultAgent(nsId = null)
            val svc = newService()
            svc.create(def(name = "my-def", nsId = namespaceId))
            svc.create(def(name = "my-def", nsId = otherNs)).name shouldBe "my-def"
        }

        // -------------------------------------------------------------------------
        // Basic CRUD
        // -------------------------------------------------------------------------

        "create persists and returns the definition" {
            val svc = newService()
            val saved = svc.create(def("daily-standup", recurrence = recurrence(every = 2, unit = SchedulerUnit.WEEK)))
            saved.name shouldBe "daily-standup"
            saved.namespaceId shouldBe namespaceId
            saved.recurrence.every shouldBe 2
            saved.recurrence.unit shouldBe SchedulerUnit.WEEK
            saved.enabled.shouldBeTrue()
        }

        "findById returns the definition when it exists" {
            val svc = newService()
            val saved = svc.create(def())
            svc.findById(saved.id) shouldBe saved
        }

        "findById returns null when definition does not exist" {
            newService().findById(UUID.randomUUID()).shouldBeNull()
        }

        "update persists changes (slug not validated on update)" {
            val svc = newService()
            val saved = svc.create(def(name = "original"))
            val updated = svc.update(saved.copy(name = "Updated Name", enabled = false))
            updated.name shouldBe "Updated Name"
            updated.enabled.shouldBeFalse()
        }

        "update throws ResourceNotFoundException when promptId does not exist" {
            val svc = newService()
            val saved = svc.create(def())
            val unknownPId = UUID.randomUUID()
            every { promptService.findById(unknownPId) } returns null
            shouldThrow<ResourceNotFoundException> { svc.update(saved.copy(promptId = unknownPId)) }
        }

        "delete returns true and soft-deletes" {
            val svc = newService()
            val saved = svc.create(def())
            svc.delete(saved.id).shouldBeTrue()
            svc.findByParent(namespaceId).shouldBeEmpty()
        }

        "delete returns false when definition does not exist" {
            newService().delete(UUID.randomUUID()).shouldBeFalse()
        }

        "deleteByParent soft-deletes all definitions in the namespace" {
            val svc = newService()
            svc.create(def("def-1"))
            svc.create(def("def-2"))
            svc.deleteByParent(namespaceId) shouldBe 2
            svc.findByParent(namespaceId).shouldBeEmpty()
        }

        "deleteByParent does not affect other namespaces" {
            val otherNs = UUID.randomUUID()
            every { agentConfigService.findById(agentConfigId) } returns defaultAgent(nsId = null)
            val svc = newService()
            svc.create(def("in-ns", nsId = namespaceId))
            svc.create(def("other", nsId = otherNs))
            svc.deleteByParent(namespaceId)
            svc.findByParent(otherNs) shouldHaveSize 1
        }

        // -------------------------------------------------------------------------
        // findByParent and findPlatform
        // -------------------------------------------------------------------------

        "findByParent returns definitions scoped to the given namespace" {
            val otherNs = UUID.randomUUID()
            every { agentConfigService.findById(agentConfigId) } returns defaultAgent(nsId = null)
            val svc = newService()
            svc.create(def("in-ns", nsId = namespaceId))
            svc.create(def("other-ns", nsId = otherNs))
            val result = svc.findByParent(namespaceId)
            result shouldHaveSize 1
            result.first().name shouldBe "in-ns"
        }

        "findByParent returns empty list when namespace has no definitions" {
            newService().findByParent(UUID.randomUUID()).shouldBeEmpty()
        }

        "findByParent returns definitions sorted by name" {
            val svc = newService()
            svc.create(def("zeta"))
            svc.create(def("alpha"))
            svc.create(def("mu"))
            svc.findByParent(namespaceId).map { it.name } shouldBe listOf("alpha", "mu", "zeta")
        }

        "findPlatform returns only platform-level definitions" {
            every { agentConfigService.findById(agentConfigId) } returns defaultAgent(nsId = null)
            val svc = newService()
            svc.create(def("platform-def", nsId = null))
            svc.create(def("ns-def", nsId = namespaceId))
            val platform = svc.findPlatform()
            platform shouldHaveSize 1
            platform.first().name shouldBe "platform-def"
            platform.first().namespaceId shouldBe null
        }

        // -------------------------------------------------------------------------
        // toggle
        // -------------------------------------------------------------------------

        "toggle flips enabled from true to false" {
            val svc = newService()
            svc.toggle(svc.create(def(enabled = true)).id).enabled.shouldBeFalse()
        }

        "toggle flips enabled from false to true" {
            val svc = newService()
            svc.toggle(svc.create(def(enabled = false)).id).enabled.shouldBeTrue()
        }

        "toggle throws ResourceNotFoundException when definition does not exist" {
            shouldThrow<ResourceNotFoundException> { newService().toggle(UUID.randomUUID()) }
        }

        // -------------------------------------------------------------------------
        // findEffective — overlay fold
        // -------------------------------------------------------------------------

        "findEffective returns all four layers when names are distinct" {
            every { agentConfigService.findById(agentConfigId) } returns defaultAgent(nsId = null)
            val svc = newService()
            val user = UUID.randomUUID()
            svc.create(def("platform-only", nsId = null, userId = null))
            svc.create(def("user-only", nsId = null, userId = user))
            svc.create(def("ns-only", nsId = namespaceId, userId = null))
            svc.create(def("user-ns-only", nsId = namespaceId, userId = user))
            val effective = svc.findEffective(namespaceId, user)
            effective shouldHaveSize 4
            effective.map { it.name } shouldBe listOf("ns-only", "platform-only", "user-ns-only", "user-only")
        }

        "findEffective higher layer overrides lower layer by name" {
            every { agentConfigService.findById(agentConfigId) } returns defaultAgent(nsId = null)
            val svc = newService()
            val user = UUID.randomUUID()
            svc.create(def("deploy", nsId = null, userId = null))
            val nsLayer = svc.create(def("deploy", nsId = namespaceId, userId = null, recurrence = recurrence(every = 2)))
            val effective = svc.findEffective(namespaceId, user)
            effective shouldHaveSize 1
            effective.first().id shouldBe nsLayer.id
        }

        "findEffective user x namespace wins over all other layers" {
            every { agentConfigService.findById(agentConfigId) } returns defaultAgent(nsId = null)
            val svc = newService()
            val user = UUID.randomUUID()
            svc.create(def("deploy", nsId = null, userId = null))
            svc.create(def("deploy", nsId = null, userId = user))
            svc.create(def("deploy", nsId = namespaceId, userId = null))
            val winner = svc.create(def("deploy", nsId = namespaceId, userId = user, recurrence = recurrence(every = 3)))
            svc.findEffective(namespaceId, user).let {
                it shouldHaveSize 1
                it.first().id shouldBe winner.id
            }
        }

        "findEffective priority: user-global overrides platform" {
            every { agentConfigService.findById(agentConfigId) } returns defaultAgent(nsId = null)
            val svc = newService()
            val user = UUID.randomUUID()
            svc.create(def("a", nsId = null, userId = null, recurrence = recurrence(every = 1)))
            val userGlobal = svc.create(def("a", nsId = null, userId = user, recurrence = recurrence(every = 2)))
            svc.findEffective(namespaceId, user).let {
                it shouldHaveSize 1
                it.first().id shouldBe userGlobal.id
            }
        }

        "findEffective priority: namespace overrides user-global" {
            every { agentConfigService.findById(agentConfigId) } returns defaultAgent(nsId = null)
            val svc = newService()
            val user = UUID.randomUUID()
            svc.create(def("a", nsId = null, userId = user, recurrence = recurrence(every = 2)))
            val nsLayer = svc.create(def("a", nsId = namespaceId, userId = null, recurrence = recurrence(every = 3)))
            svc.findEffective(namespaceId, user).let {
                it shouldHaveSize 1
                it.first().id shouldBe nsLayer.id
            }
        }

        "findEffective excludes definitions from other namespaces" {
            every { agentConfigService.findById(agentConfigId) } returns defaultAgent(nsId = null)
            val svc = newService()
            val otherNs = UUID.randomUUID()
            val user = UUID.randomUUID()
            svc.create(def("foreign", nsId = otherNs, userId = null))
            svc.create(def("foreign-user", nsId = otherNs, userId = user))
            svc.findEffective(namespaceId, user).shouldBeEmpty()
        }

        "findEffective excludes definitions from other users" {
            every { agentConfigService.findById(agentConfigId) } returns defaultAgent(nsId = null)
            val svc = newService()
            val user = UUID.randomUUID()
            val otherUser = UUID.randomUUID()
            svc.create(def("other-user-global", nsId = null, userId = otherUser))
            svc.create(def("other-user-ns", nsId = namespaceId, userId = otherUser))
            svc.findEffective(namespaceId, user).shouldBeEmpty()
        }

        "findEffective returns results sorted by name" {
            every { agentConfigService.findById(agentConfigId) } returns defaultAgent(nsId = null)
            val svc = newService()
            val user = UUID.randomUUID()
            svc.create(def("zebra", nsId = null, userId = null))
            svc.create(def("alpha", nsId = namespaceId, userId = null))
            svc.create(def("middle", nsId = null, userId = user))
            svc.findEffective(namespaceId, user).map { it.name } shouldBe listOf("alpha", "middle", "zebra")
        }

        "findEffective returns empty when no definitions exist" {
            newService().findEffective(UUID.randomUUID(), UUID.randomUUID()).shouldBeEmpty()
        }

        // -------------------------------------------------------------------------
        // findByScope
        // -------------------------------------------------------------------------

        "findByScope returns platform-level definitions when both null" {
            every { agentConfigService.findById(agentConfigId) } returns defaultAgent(nsId = null)
            val svc = newService()
            svc.create(def("platform-def", nsId = null))
            svc.create(def("ns-def", nsId = namespaceId))
            val result = svc.findByScope(null, null, null)
            result shouldHaveSize 1
            result.first().name shouldBe "platform-def"
        }

        "findByScope filters by agentConfigIds" {
            val otherId = UUID.randomUUID()
            val otherPId = UUID.randomUUID()
            every { agentConfigService.findById(agentConfigId) } returns defaultAgent(nsId = null)
            every { agentConfigService.findById(otherId) } returns AgentConfig(
                metadata = EntityMetadata(id = otherId, version = 0L),
                namespaceId = null,
                name = "other-agent",
            )
            every { promptService.findById(otherPId) } returns Prompt(
                metadata = EntityMetadata(id = otherPId, version = 0L),
                namespaceId = null,
                name = "other-prompt",
                content = listOf("Hi"),
            )
            val svc = newService()
            svc.create(def("def-1", nsId = null, agentId = agentConfigId, pId = promptId))
            svc.create(def("def-2", nsId = null, agentId = otherId, pId = otherPId))
            val result = svc.findByScope(null, null, listOf(agentConfigId))
            result shouldHaveSize 1
            result.first().agentConfigId shouldBe agentConfigId
        }

        // -------------------------------------------------------------------------
        // Round-trips
        // -------------------------------------------------------------------------

        "recurrence round-trips" {
            val r = recurrence(every = 2, unit = SchedulerUnit.MONTH, days = listOf(DayOfWeek.MONDAY), timeUtc = LocalTime.of(14, 30))
            val saved = newService().create(def(recurrence = r))
            saved.recurrence shouldBe r
        }

        "planning NEVER round-trips" {
            val p = planning(startDate = today, endType = SchedulerEndType.NEVER)
            newService().create(def(planning = p)).planning shouldBe p
        }

        "planning ON_DATE round-trips" {
            val p = planning(endType = SchedulerEndType.ON_DATE, endDate = today.plusMonths(3))
            newService().create(def(planning = p)).planning shouldBe p
        }

        "planning OCCURRENCES round-trips" {
            val p = planning(endType = SchedulerEndType.OCCURRENCES, occurrenceCount = 10)
            newService().create(def(planning = p)).planning shouldBe p
        }

        "description round-trips" {
            newService().create(def(description = "Daily standup")).description shouldBe "Daily standup"
        }

        "promptId round-trips" {
            newService().create(def()).promptId shouldBe promptId
        }
    }
}
