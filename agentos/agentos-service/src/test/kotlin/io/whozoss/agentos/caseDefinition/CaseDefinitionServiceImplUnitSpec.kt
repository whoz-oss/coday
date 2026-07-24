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
import io.whozoss.agentos.sdk.entity.EntityMetadata
import java.util.UUID

/**
 * Unit tests for [CaseDefinitionServiceImpl].
 *
 * Uses [InMemoryCaseDefinitionRepository] to keep tests fast and isolated.
 * Each test builds its own service instance to guarantee full isolation.
 *
 * Access-control filtering (DEPLOYED_TO graph) is NOT tested here — that is a
 * Neo4j integration concern covered in [CaseDefinitionControllerIntegrationSpec].
 */
class CaseDefinitionServiceImplUnitSpec : StringSpec() {
    private val agentConfigService = mockk<AgentConfigService>(relaxed = true)
    private val promptService = mockk<PromptService>(relaxed = true)

    private fun newService(repo: CaseDefinitionRepository = InMemoryCaseDefinitionRepository()): CaseDefinitionServiceImpl =
        CaseDefinitionServiceImpl(repo, agentConfigService, promptService)

    private val namespaceId: UUID = UUID.randomUUID()
    private val agentConfigId: UUID = UUID.randomUUID()
    private val promptId: UUID = UUID.randomUUID()

    /** A default AgentConfig that passes all validations (namespace-scoped to [namespaceId]). */
    private fun defaultAgent(nsId: UUID? = namespaceId) = AgentConfig(
        metadata = EntityMetadata(id = agentConfigId, version = 0L),
        namespaceId = nsId,
        name = "agent",
    )

    /** A default Prompt with no agentConfigId (generic, usable with any agent). */
    private fun defaultPrompt(linkedAgentId: UUID? = null) = Prompt(
        metadata = EntityMetadata(id = promptId, version = 0L),
        namespaceId = namespaceId,
        agentConfigId = linkedAgentId,
        name = "my-prompt",
        content = listOf("Hello"),
    )

    private fun def(
        name: String = "my-def",
        nsId: UUID? = namespaceId,
        userId: UUID? = null,
        description: String? = null,
        enabled: Boolean = true,
        cronExpression: String = "0 8 * * *",
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
        cronExpression = cronExpression,
        enabled = enabled,
    )

    init {
        // -------------------------------------------------------------------------
        // Setup default mocks for happy path
        // -------------------------------------------------------------------------

        beforeTest {
            every { agentConfigService.findById(agentConfigId) } returns defaultAgent()
            every { promptService.findById(promptId) } returns defaultPrompt()
        }

        // -------------------------------------------------------------------------
        // Slug validation on create
        // -------------------------------------------------------------------------

        "create accepts valid slug names" {
            val svc = newService()
            val saved = svc.create(def(name = "daily-standup"))
            saved.name shouldBe "daily-standup"
        }

        "create accepts single-word slug" {
            val svc = newService()
            svc.create(def(name = "standup")).name shouldBe "standup"
        }

        "create accepts slug with numbers" {
            val svc = newService()
            svc.create(def(name = "weekly-sync-v2")).name shouldBe "weekly-sync-v2"
        }

        "create rejects name starting with uppercase" {
            val svc = newService()
            shouldThrow<IllegalArgumentException> { svc.create(def(name = "Daily-standup")) }
        }

        "create rejects name with spaces" {
            val svc = newService()
            shouldThrow<IllegalArgumentException> { svc.create(def(name = "daily standup")) }
        }

        "create rejects name starting with digit" {
            val svc = newService()
            shouldThrow<IllegalArgumentException> { svc.create(def(name = "1standup")) }
        }

        "create rejects name with trailing hyphen" {
            val svc = newService()
            shouldThrow<IllegalArgumentException> { svc.create(def(name = "standup-")) }
        }

        "create rejects name with double hyphens" {
            val svc = newService()
            shouldThrow<IllegalArgumentException> { svc.create(def(name = "daily--standup")) }
        }

        // -------------------------------------------------------------------------
        // agentConfigId validation
        // -------------------------------------------------------------------------

        "create throws ResourceNotFoundException when agentConfigId does not exist" {
            val unknownId = UUID.randomUUID()
            every { agentConfigService.findById(unknownId) } returns null
            val svc = newService()
            shouldThrow<ResourceNotFoundException> { svc.create(def(agentId = unknownId)) }
        }

        "create throws UnprocessableEntityException for filesystem-only agent (version == null)" {
            val fsAgentId = UUID.randomUUID()
            every { agentConfigService.findById(fsAgentId) } returns AgentConfig(
                metadata = EntityMetadata(id = fsAgentId, version = null),
                namespaceId = namespaceId,
                name = "fs-agent",
            )
            val svc = newService()
            shouldThrow<UnprocessableEntityException> { svc.create(def(agentId = fsAgentId)) }
        }

        "create throws BadRequestException when agentConfig belongs to a different namespace" {
            val otherNs = UUID.randomUUID()
            every { agentConfigService.findById(agentConfigId) } returns defaultAgent(nsId = otherNs)
            val svc = newService()
            shouldThrow<BadRequestException> { svc.create(def(nsId = namespaceId)) }
        }

        "create succeeds with platform agent (namespaceId == null) from any scope" {
            every { agentConfigService.findById(agentConfigId) } returns defaultAgent(nsId = null)
            val svc = newService()
            val saved = svc.create(def())
            saved.agentConfigId shouldBe agentConfigId
        }

        "create succeeds with agent from same namespace" {
            val svc = newService()
            val saved = svc.create(def())
            saved.agentConfigId shouldBe agentConfigId
        }

        // -------------------------------------------------------------------------
        // promptId validation
        // -------------------------------------------------------------------------

        "create throws ResourceNotFoundException when promptId does not exist" {
            val unknownPromptId = UUID.randomUUID()
            every { promptService.findById(unknownPromptId) } returns null
            val svc = newService()
            shouldThrow<ResourceNotFoundException> { svc.create(def(pId = unknownPromptId)) }
        }

        "create succeeds when prompt has no agentConfigId (generic prompt)" {
            every { promptService.findById(promptId) } returns defaultPrompt(linkedAgentId = null)
            val svc = newService()
            val saved = svc.create(def())
            saved.promptId shouldBe promptId
        }

        "create throws BadRequestException when prompt has any agentConfigId (matching agent)" {
            every { promptService.findById(promptId) } returns defaultPrompt(linkedAgentId = agentConfigId)
            val svc = newService()
            val ex = shouldThrow<BadRequestException> { svc.create(def()) }
            ex.message shouldContain "agentConfigId"
        }

        "create throws BadRequestException when prompt has any agentConfigId (different agent)" {
            val otherAgentId = UUID.randomUUID()
            every { promptService.findById(promptId) } returns defaultPrompt(linkedAgentId = otherAgentId)
            val svc = newService()
            val ex = shouldThrow<BadRequestException> { svc.create(def()) }
            ex.message shouldContain "agentConfigId"
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
            every { agentConfigService.findById(agentConfigId) } returns defaultAgent(nsId = null) // platform agent
            val svc = newService()
            svc.create(def(name = "my-def", nsId = namespaceId))
            svc.create(def(name = "my-def", nsId = otherNs)).name shouldBe "my-def"
        }

        // -------------------------------------------------------------------------
        // Basic CRUD
        // -------------------------------------------------------------------------

        "create persists and returns the definition" {
            val svc = newService()
            val saved = svc.create(def("daily-standup", cronExpression = "0 9 * * *"))
            saved.name shouldBe "daily-standup"
            saved.namespaceId shouldBe namespaceId
            saved.cronExpression shouldBe "0 9 * * *"
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
            val repo = InMemoryCaseDefinitionRepository()
            val svc = newService(repo)
            val saved = svc.create(def(name = "original"))
            // Update with a non-slug name — slug is NOT validated on update
            val updated = svc.update(saved.copy(name = "Updated Name", enabled = false))
            updated.name shouldBe "Updated Name"
            updated.enabled.shouldBeFalse()
        }

        "update throws ResourceNotFoundException when promptId does not exist" {
            val repo = InMemoryCaseDefinitionRepository()
            val svc = newService(repo)
            val saved = svc.create(def())
            val unknownPromptId = UUID.randomUUID()
            every { promptService.findById(unknownPromptId) } returns null
            shouldThrow<ResourceNotFoundException> { svc.update(saved.copy(promptId = unknownPromptId)) }
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

        "findPlatform returns only platform-level definitions (namespaceId == null)" {
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
            val saved = svc.create(def(enabled = true))
            svc.toggle(saved.id).enabled.shouldBeFalse()
        }

        "toggle flips enabled from false to true" {
            val svc = newService()
            val saved = svc.create(def(enabled = false))
            svc.toggle(saved.id).enabled.shouldBeTrue()
        }

        "toggle throws ResourceNotFoundException when definition does not exist" {
            shouldThrow<ResourceNotFoundException> { newService().toggle(UUID.randomUUID()) }
        }

        // -------------------------------------------------------------------------
        // findEffective — overlay fold (access control not tested here, no graph)
        // -------------------------------------------------------------------------

        "findEffective returns all four layers when names are distinct" {
            every { agentConfigService.findById(agentConfigId) } returns defaultAgent(nsId = null)
            val svc = newService()
            val ns = namespaceId
            val user = UUID.randomUUID()
            svc.create(def("platform-only", nsId = null, userId = null))
            svc.create(def("user-only", nsId = null, userId = user))
            svc.create(def("ns-only", nsId = ns, userId = null))
            svc.create(def("user-ns-only", nsId = ns, userId = user))

            val effective = svc.findEffective(ns, user)
            effective shouldHaveSize 4
            effective.map { it.name } shouldBe listOf("ns-only", "platform-only", "user-ns-only", "user-only")
        }

        "findEffective higher layer overrides lower layer by name" {
            every { agentConfigService.findById(agentConfigId) } returns defaultAgent(nsId = null)
            val svc = newService()
            val ns = namespaceId
            val user = UUID.randomUUID()
            svc.create(def("deploy", nsId = null, userId = null))
            val nsLayer = svc.create(def("deploy", nsId = ns, userId = null, cronExpression = "0 10 * * *"))

            val effective = svc.findEffective(ns, user)
            effective shouldHaveSize 1
            effective.first().name shouldBe "deploy"
            effective.first().id shouldBe nsLayer.id
        }

        "findEffective user x namespace wins over all other layers" {
            every { agentConfigService.findById(agentConfigId) } returns defaultAgent(nsId = null)
            val svc = newService()
            val ns = namespaceId
            val user = UUID.randomUUID()
            svc.create(def("deploy", nsId = null, userId = null))
            svc.create(def("deploy", nsId = null, userId = user))
            svc.create(def("deploy", nsId = ns, userId = null))
            val winner = svc.create(def("deploy", nsId = ns, userId = user, cronExpression = "0 12 * * *"))

            val effective = svc.findEffective(ns, user)
            effective shouldHaveSize 1
            effective.first().id shouldBe winner.id
        }

        "findEffective priority: user-global overrides platform" {
            every { agentConfigService.findById(agentConfigId) } returns defaultAgent(nsId = null)
            val svc = newService()
            val ns = namespaceId
            val user = UUID.randomUUID()
            svc.create(def("a", nsId = null, userId = null, cronExpression = "0 8 * * *"))
            val userGlobal = svc.create(def("a", nsId = null, userId = user, cronExpression = "0 9 * * *"))

            val effective = svc.findEffective(ns, user)
            effective shouldHaveSize 1
            effective.first().id shouldBe userGlobal.id
        }

        "findEffective priority: namespace overrides user-global" {
            every { agentConfigService.findById(agentConfigId) } returns defaultAgent(nsId = null)
            val svc = newService()
            val ns = namespaceId
            val user = UUID.randomUUID()
            svc.create(def("a", nsId = null, userId = user, cronExpression = "0 9 * * *"))
            val nsLayer = svc.create(def("a", nsId = ns, userId = null, cronExpression = "0 10 * * *"))

            val effective = svc.findEffective(ns, user)
            effective shouldHaveSize 1
            effective.first().id shouldBe nsLayer.id
        }

        "findEffective excludes definitions from other namespaces" {
            every { agentConfigService.findById(agentConfigId) } returns defaultAgent(nsId = null)
            val svc = newService()
            val ns = namespaceId
            val otherNs = UUID.randomUUID()
            val user = UUID.randomUUID()
            svc.create(def("foreign", nsId = otherNs, userId = null))
            svc.create(def("foreign-user", nsId = otherNs, userId = user))
            svc.findEffective(ns, user).shouldBeEmpty()
        }

        "findEffective excludes definitions from other users" {
            every { agentConfigService.findById(agentConfigId) } returns defaultAgent(nsId = null)
            val svc = newService()
            val ns = namespaceId
            val user = UUID.randomUUID()
            val otherUser = UUID.randomUUID()
            svc.create(def("other-user-global", nsId = null, userId = otherUser))
            svc.create(def("other-user-ns", nsId = ns, userId = otherUser))
            svc.findEffective(ns, user).shouldBeEmpty()
        }

        "findEffective returns results sorted by name" {
            every { agentConfigService.findById(agentConfigId) } returns defaultAgent(nsId = null)
            val svc = newService()
            val ns = namespaceId
            val user = UUID.randomUUID()
            svc.create(def("zebra", nsId = null, userId = null))
            svc.create(def("alpha", nsId = ns, userId = null))
            svc.create(def("middle", nsId = null, userId = user))
            svc.findEffective(ns, user).map { it.name } shouldBe listOf("alpha", "middle", "zebra")
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
            val otherAgentId = UUID.randomUUID()
            every { agentConfigService.findById(agentConfigId) } returns defaultAgent(nsId = null)
            every { agentConfigService.findById(otherAgentId) } returns AgentConfig(
                metadata = EntityMetadata(id = otherAgentId, version = 0L),
                namespaceId = null,
                name = "other-agent",
            )
            val otherPromptId = UUID.randomUUID()
            every { promptService.findById(otherPromptId) } returns Prompt(
                metadata = EntityMetadata(id = otherPromptId, version = 0L),
                namespaceId = null,
                name = "other-prompt",
                content = listOf("Hi"),
            )
            val svc = newService()
            svc.create(def("def-agent-1", nsId = null, agentId = agentConfigId, pId = promptId))
            svc.create(def("def-agent-2", nsId = null, agentId = otherAgentId, pId = otherPromptId))

            val result = svc.findByScope(null, null, listOf(agentConfigId))
            result shouldHaveSize 1
            result.first().agentConfigId shouldBe agentConfigId
        }

        // -------------------------------------------------------------------------
        // Round-trips
        // -------------------------------------------------------------------------

        "namespaceId round-trips through service" {
            val nsId = UUID.randomUUID()
            every { agentConfigService.findById(agentConfigId) } returns defaultAgent(nsId = nsId)
            val svc = newService()
            svc.create(def(nsId = nsId)).namespaceId shouldBe nsId
        }

        "userId round-trips through service" {
            val uid = UUID.randomUUID()
            val svc = newService()
            svc.create(def(userId = uid)).userId shouldBe uid
        }

        "agentConfigId round-trips through service" {
            val svc = newService()
            svc.create(def()).agentConfigId shouldBe agentConfigId
        }

        "promptId round-trips through service" {
            val svc = newService()
            svc.create(def()).promptId shouldBe promptId
        }

        "description round-trips" {
            val svc = newService()
            svc.create(def(description = "Daily standup")).description shouldBe "Daily standup"
        }

        "DAILY cron round-trips" {
            val svc = newService()
            svc.create(def(cronExpression = "0 9 * * *")).cronExpression shouldBe "0 9 * * *"
        }

        "WEEKLY cron round-trips" {
            val svc = newService()
            svc.create(def(cronExpression = "30 14 * * FRI")).cronExpression shouldBe "30 14 * * FRI"
        }
    }
}
