package io.whozoss.agentos.prompt

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.whozoss.agentos.agentConfig.AgentConfig
import io.whozoss.agentos.agentConfig.AgentConfigService
import io.whozoss.agentos.exception.BadRequestException
import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.sdk.entity.EntityMetadata
import java.util.UUID

/**
 * Unit tests for [PromptServiceImpl].
 *
 * Uses [InMemoryPromptRepository] to keep tests fast and isolated.
 * Each test builds its own service instance to guarantee full isolation.
 *
 * Blank content element validation is enforced by Bean Validation at the controller
 * layer (List<@NotBlank String> with -Xemit-jvm-type-annotations) and is not tested here.
 */
class PromptServiceImplSpec : StringSpec() {
    private val agentConfigService = mockk<AgentConfigService>(relaxed = true)
    private fun newService(): PromptServiceImpl = PromptServiceImpl(InMemoryPromptRepository(), agentConfigService)

    private fun prompt(
        namespaceId: UUID? = UUID.randomUUID(),
        userId: UUID? = null,
        agentConfigId: UUID? = null,
        name: String = "My Prompt",
        content: List<String> = listOf("Hello {{name}}"),
        parameters: List<PromptParameter> = emptyList(),
        description: String? = null,
    ) = Prompt(
        metadata = EntityMetadata(),
        namespaceId = namespaceId,
        userId = userId,
        agentConfigId = agentConfigId,
        name = name,
        description = description,
        content = content,
        parameters = parameters,
    )

    init {
        // -------------------------------------------------------------------------
        // Create / Read
        // -------------------------------------------------------------------------

        "create and findById returns the same prompt" {
            val service = newService()
            val saved = service.create(prompt())

            val found = service.findById(saved.id)

            found.shouldNotBeNull()
            found.id shouldBe saved.id
            found.name shouldBe saved.name
        }

        "findById returns null for unknown id" {
            val service = newService()
            service.findById(UUID.randomUUID()).shouldBeNull()
        }

        // -------------------------------------------------------------------------
        // Scope queries
        // -------------------------------------------------------------------------

        "findPlatform returns only platform-level prompts (namespaceId == null)" {
            val service = newService()
            service.create(prompt(namespaceId = null, name = "Platform Prompt"))
            service.create(prompt(namespaceId = UUID.randomUUID(), name = "NS Prompt"))

            val platform = service.findPlatform()
            platform shouldHaveSize 1
            platform.first().name shouldBe "Platform Prompt"
            platform.first().namespaceId shouldBe null
        }

        "findByParent returns only prompts for the given namespace" {
            val service = newService()
            val nsA = UUID.randomUUID()
            val nsB = UUID.randomUUID()

            service.create(prompt(namespaceId = nsA, name = "Alpha"))
            service.create(prompt(namespaceId = nsA, name = "Beta"))
            service.create(prompt(namespaceId = nsB, name = "Gamma"))

            service.findByParent(nsA) shouldHaveSize 2
            service.findByParent(nsB) shouldHaveSize 1
            service.findByParent(UUID.randomUUID()).shouldBeEmpty()
        }

        // -------------------------------------------------------------------------
        // Content
        // -------------------------------------------------------------------------

        "create accepts prompt with a single non-blank content element" {
            val service = newService()
            val saved = service.create(prompt(content = listOf("Single line prompt")))
            saved.content shouldBe listOf("Single line prompt")
        }

        "create accepts prompt with multiple non-blank content elements" {
            val service = newService()
            val saved = service.create(prompt(content = listOf("Line one", "Line two", "Line three")))
            saved.content shouldHaveSize 3
        }

        // -------------------------------------------------------------------------
        // Parameter name uniqueness
        // -------------------------------------------------------------------------

        "create rejects prompt with duplicate parameter names" {
            val service = newService()
            val params = listOf(
                PromptParameter(name = "name", defaultValue = ""),
                PromptParameter(name = "language", defaultValue = "English"),
                PromptParameter(name = "name", defaultValue = ""),
            )

            val ex = shouldThrow<BadRequestException> {
                service.create(prompt(parameters = params))
            }
            ex.message shouldContain "name"
        }

        "create accepts prompt with empty parameters list" {
            val service = newService()
            val saved = service.create(prompt(parameters = emptyList()))
            saved.parameters.shouldBeEmpty()
        }

        "create accepts prompt with unique parameter names" {
            val service = newService()
            val params = listOf(
                PromptParameter(name = "name", description = "The name", defaultValue = ""),
                PromptParameter(name = "language", defaultValue = "English"),
            )
            val saved = service.create(prompt(parameters = params))
            saved.parameters shouldHaveSize 2
        }

        // -------------------------------------------------------------------------
        // agentConfigId validation
        // -------------------------------------------------------------------------

        "create with non-existent agentConfigId throws ResourceNotFoundException" {
            val service = newService()
            val unknownId = UUID.randomUUID()
            every { agentConfigService.findById(unknownId) } returns null

            shouldThrow<ResourceNotFoundException> {
                service.create(prompt(agentConfigId = unknownId))
            }
        }

        "create with existing agentConfigId succeeds" {
            val service = newService()
            val agentId = UUID.randomUUID()
            every { agentConfigService.findById(agentId) } returns AgentConfig(
                metadata = EntityMetadata(id = agentId),
                namespaceId = null,
                name = "agent",
            )

            val saved = service.create(prompt(agentConfigId = agentId))
            saved.agentConfigId shouldBe agentId
        }

        // -------------------------------------------------------------------------
        // Update validation
        // -------------------------------------------------------------------------

        "update rejects duplicate parameter names" {
            val service = newService()
            val saved = service.create(prompt())

            shouldThrow<BadRequestException> {
                service.update(
                    saved.copy(
                        parameters = listOf(
                            PromptParameter(name = "city", defaultValue = ""),
                            PromptParameter(name = "city", defaultValue = ""),
                        ),
                    ),
                )
            }
        }

        "update with valid data persists changes" {
            val service = newService()
            val saved = service.create(prompt(name = "Original"))

            val updated = service.update(saved.copy(name = "Renamed", description = "Now described"))

            updated.name shouldBe "Renamed"
            updated.description shouldBe "Now described"
            service.findById(saved.id)?.name shouldBe "Renamed"
        }

        // -------------------------------------------------------------------------
        // Delete
        // -------------------------------------------------------------------------

        "delete soft-deletes the prompt" {
            val service = newService()
            val nsId = UUID.randomUUID()
            val saved = service.create(prompt(namespaceId = nsId))

            service.delete(saved.id) shouldBe true

            service.findById(saved.id).shouldBeNull()
            service.findByParent(nsId).shouldBeEmpty()
        }

        "delete returns false for unknown id" {
            val service = newService()
            service.delete(UUID.randomUUID()) shouldBe false
        }

        "deleteByParent removes all prompts for a namespace" {
            val service = newService()
            val nsId = UUID.randomUUID()
            service.create(prompt(namespaceId = nsId, name = "A"))
            service.create(prompt(namespaceId = nsId, name = "B"))

            val count = service.deleteByParent(nsId)

            count shouldBe 2
            service.findByParent(nsId).shouldBeEmpty()
        }

        "deleteByParent does not affect other namespaces" {
            val service = newService()
            val nsA = UUID.randomUUID()
            val nsB = UUID.randomUUID()
            service.create(prompt(namespaceId = nsA, name = "A"))
            service.create(prompt(namespaceId = nsB, name = "B"))

            service.deleteByParent(nsA)

            service.findByParent(nsA).shouldBeEmpty()
            service.findByParent(nsB) shouldHaveSize 1
        }

        // -------------------------------------------------------------------------
        // findEffective — overlay fold
        // -------------------------------------------------------------------------

        "findEffective returns all four layers when names are distinct" {
            val service = newService()
            val ns = UUID.randomUUID()
            val user = UUID.randomUUID()

            service.create(prompt(namespaceId = null, userId = null, name = "platform-only"))
            service.create(prompt(namespaceId = null, userId = user, name = "user-only"))
            service.create(prompt(namespaceId = ns, userId = null, name = "ns-only"))
            service.create(prompt(namespaceId = ns, userId = user, name = "user-ns-only"))

            val effective = service.findEffective(ns, user)
            effective shouldHaveSize 4
            effective.map { it.name } shouldBe listOf("ns-only", "platform-only", "user-ns-only", "user-only")
        }

        "findEffective higher layer overrides lower layer by name" {
            val service = newService()
            val ns = UUID.randomUUID()
            val user = UUID.randomUUID()

            service.create(prompt(namespaceId = null, userId = null, name = "deploy", content = listOf("platform")))
            val nsPrompt = service.create(prompt(namespaceId = ns, userId = null, name = "deploy", content = listOf("namespace")))

            val effective = service.findEffective(ns, user)
            effective shouldHaveSize 1
            effective.first().name shouldBe "deploy"
            effective.first().content shouldBe listOf("namespace")
            effective.first().id shouldBe nsPrompt.id
        }

        "findEffective user x namespace wins over all other layers" {
            val service = newService()
            val ns = UUID.randomUUID()
            val user = UUID.randomUUID()

            service.create(prompt(namespaceId = null, userId = null, name = "deploy", content = listOf("platform")))
            service.create(prompt(namespaceId = null, userId = user, name = "deploy", content = listOf("user-global")))
            service.create(prompt(namespaceId = ns, userId = null, name = "deploy", content = listOf("namespace")))
            val winner = service.create(prompt(namespaceId = ns, userId = user, name = "deploy", content = listOf("user-ns")))

            val effective = service.findEffective(ns, user)
            effective shouldHaveSize 1
            effective.first().content shouldBe listOf("user-ns")
            effective.first().id shouldBe winner.id
        }

        "findEffective priority: user-global overrides platform" {
            val service = newService()
            val ns = UUID.randomUUID()
            val user = UUID.randomUUID()

            service.create(prompt(namespaceId = null, userId = null, name = "a", content = listOf("platform")))
            service.create(prompt(namespaceId = null, userId = user, name = "a", content = listOf("user-global")))

            val effective = service.findEffective(ns, user)
            effective shouldHaveSize 1
            effective.first().content shouldBe listOf("user-global")
        }

        "findEffective priority: namespace overrides user-global" {
            val service = newService()
            val ns = UUID.randomUUID()
            val user = UUID.randomUUID()

            service.create(prompt(namespaceId = null, userId = user, name = "a", content = listOf("user-global")))
            service.create(prompt(namespaceId = ns, userId = null, name = "a", content = listOf("namespace")))

            val effective = service.findEffective(ns, user)
            effective shouldHaveSize 1
            effective.first().content shouldBe listOf("namespace")
        }

        "findEffective excludes prompts from other namespaces" {
            val service = newService()
            val ns = UUID.randomUUID()
            val otherNs = UUID.randomUUID()
            val user = UUID.randomUUID()

            service.create(prompt(namespaceId = otherNs, userId = null, name = "foreign"))
            service.create(prompt(namespaceId = otherNs, userId = user, name = "foreign-user"))

            val effective = service.findEffective(ns, user)
            effective.shouldBeEmpty()
        }

        "findEffective excludes prompts from other users" {
            val service = newService()
            val ns = UUID.randomUUID()
            val user = UUID.randomUUID()
            val otherUser = UUID.randomUUID()

            service.create(prompt(namespaceId = null, userId = otherUser, name = "other-user-global"))
            service.create(prompt(namespaceId = ns, userId = otherUser, name = "other-user-ns"))

            val effective = service.findEffective(ns, user)
            effective.shouldBeEmpty()
        }

        "findEffective returns results sorted by name" {
            val service = newService()
            val ns = UUID.randomUUID()
            val user = UUID.randomUUID()

            service.create(prompt(namespaceId = null, userId = null, name = "zebra"))
            service.create(prompt(namespaceId = ns, userId = null, name = "alpha"))
            service.create(prompt(namespaceId = null, userId = user, name = "middle"))

            val effective = service.findEffective(ns, user)
            effective.map { it.name } shouldBe listOf("alpha", "middle", "zebra")
        }

        "findEffective returns empty when no prompts exist" {
            val service = newService()
            service.findEffective(UUID.randomUUID(), UUID.randomUUID()).shouldBeEmpty()
        }

        "findEffective partial override leaves non-overridden prompts intact" {
            val service = newService()
            val ns = UUID.randomUUID()
            val user = UUID.randomUUID()

            service.create(prompt(namespaceId = null, userId = null, name = "shared", content = listOf("platform")))
            service.create(prompt(namespaceId = null, userId = null, name = "only-platform", content = listOf("stays")))
            service.create(prompt(namespaceId = ns, userId = user, name = "shared", content = listOf("user-ns override")))

            val effective = service.findEffective(ns, user)
            effective shouldHaveSize 2
            val byName = effective.associateBy { it.name }
            byName["shared"]!!.content shouldBe listOf("user-ns override")
            byName["only-platform"]!!.content shouldBe listOf("stays")
        }
    }
}
