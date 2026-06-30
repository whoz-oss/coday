package io.whozoss.agentos.prompt

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.whozoss.agentos.sdk.entity.EntityMetadata
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * Unit tests for [PromptServiceImpl].
 *
 * Uses [InMemoryPromptRepository] to keep tests fast and isolated.
 * Each test builds its own service instance to guarantee full isolation.
 */
class PromptServiceImplSpec : StringSpec() {
    private fun newService(): PromptServiceImpl = PromptServiceImpl(InMemoryPromptRepository())

    private fun prompt(
        namespaceId: UUID? = UUID.randomUUID(),
        name: String = "My Prompt",
        content: List<String> = listOf("Hello {{name}}"),
        parameters: List<PromptParameter> = emptyList(),
        description: String? = null,
    ) = Prompt(
        metadata = EntityMetadata(),
        namespaceId = namespaceId,
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

        "findByNamespaceId returns only prompts for the given namespace" {
            val service = newService()
            val nsA = UUID.randomUUID()
            val nsB = UUID.randomUUID()

            service.create(prompt(namespaceId = nsA, name = "Alpha"))
            service.create(prompt(namespaceId = nsA, name = "Beta"))
            service.create(prompt(namespaceId = nsB, name = "Gamma"))

            service.findByNamespaceId(nsA) shouldHaveSize 2
            service.findByNamespaceId(nsB) shouldHaveSize 1
            service.findByNamespaceId(UUID.randomUUID()).shouldBeEmpty()
        }

        "findPlatform returns only platform-level prompts (namespaceId == null)" {
            val service = newService()
            service.create(prompt(namespaceId = null, name = "Platform Prompt"))
            service.create(prompt(namespaceId = UUID.randomUUID(), name = "NS Prompt"))

            val platform = service.findPlatform()
            platform shouldHaveSize 1
            platform.first().name shouldBe "Platform Prompt"
            platform.first().namespaceId shouldBe null
        }

        "findByParent delegates to findByNamespaceId" {
            val service = newService()
            val nsId = UUID.randomUUID()
            service.create(prompt(namespaceId = nsId, name = "Scoped"))

            service.findByParent(nsId) shouldHaveSize 1
        }

        // -------------------------------------------------------------------------
        // Content validation
        // -------------------------------------------------------------------------

        "create rejects prompt with a blank content element" {
            val service = newService()

            val ex = shouldThrow<ResponseStatusException> {
                service.create(prompt(content = listOf("Hello", "   ", "World")))
            }
            ex.statusCode.value() shouldBe 400
            ex.reason?.contains("content[1]") shouldBe true
        }

        "create rejects prompt with an empty string content element" {
            val service = newService()

            val ex = shouldThrow<ResponseStatusException> {
                service.create(prompt(content = listOf("")))
            }
            ex.statusCode.value() shouldBe 400
        }

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
                PromptParameter(name = "name"),
                PromptParameter(name = "language"),
                PromptParameter(name = "name"),
            )

            val ex = shouldThrow<ResponseStatusException> {
                service.create(prompt(parameters = params))
            }
            ex.statusCode.value() shouldBe 400
            ex.reason?.contains("name") shouldBe true
        }

        "create accepts prompt with empty parameters list" {
            val service = newService()
            val saved = service.create(prompt(parameters = emptyList()))
            saved.parameters.shouldBeEmpty()
        }

        "create accepts prompt with unique parameter names" {
            val service = newService()
            val params = listOf(
                PromptParameter(name = "name", description = "The name"),
                PromptParameter(name = "language", defaultValue = "English"),
            )
            val saved = service.create(prompt(parameters = params))
            saved.parameters shouldHaveSize 2
        }

        // -------------------------------------------------------------------------
        // Update validation mirrors create validation
        // -------------------------------------------------------------------------

        "update rejects blank content element" {
            val service = newService()
            val saved = service.create(prompt())

            val ex = shouldThrow<ResponseStatusException> {
                service.update(saved.copy(content = listOf("Good", "")))
            }
            ex.statusCode.value() shouldBe 400
        }

        "update rejects duplicate parameter names" {
            val service = newService()
            val saved = service.create(prompt())

            val ex = shouldThrow<ResponseStatusException> {
                service.update(
                    saved.copy(
                        parameters = listOf(
                            PromptParameter(name = "city"),
                            PromptParameter(name = "city"),
                        ),
                    ),
                )
            }
            ex.statusCode.value() shouldBe 400
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
            service.findByNamespaceId(nsId).shouldBeEmpty()
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
            service.findByNamespaceId(nsId).shouldBeEmpty()
        }

        "deleteByParent does not affect other namespaces" {
            val service = newService()
            val nsA = UUID.randomUUID()
            val nsB = UUID.randomUUID()
            service.create(prompt(namespaceId = nsA, name = "A"))
            service.create(prompt(namespaceId = nsB, name = "B"))

            service.deleteByParent(nsA)

            service.findByNamespaceId(nsA).shouldBeEmpty()
            service.findByNamespaceId(nsB) shouldHaveSize 1
        }
    }
}
