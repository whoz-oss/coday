package io.whozoss.agentos.prompt

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.whozoss.agentos.sdk.entity.EntityMetadata
import java.time.Instant
import java.util.UUID

/**
 * Unit tests for [PromptNode] toDomain / fromDomain round-trips.
 *
 * Verifies JSON serialization of [content] and [parameters], null handling
 * for optional fields, and metadata mapping.
 */
class PromptNodeSpec : StringSpec() {
    private val objectMapper: ObjectMapper = ObjectMapper().registerKotlinModule().findAndRegisterModules()

    private fun prompt(
        id: UUID = UUID.randomUUID(),
        namespaceId: UUID? = UUID.randomUUID(),
        name: String = "My Prompt",
        description: String? = null,
        content: List<String> = listOf("Hello {{name}}"),
        parameters: List<PromptParameter> = emptyList(),
    ) = Prompt(
        metadata = EntityMetadata(
            id = id,
            created = Instant.parse("2024-01-01T00:00:00Z"),
            createdBy = "creator@example.com",
            modified = Instant.parse("2024-06-01T12:00:00Z"),
            modifiedBy = "editor@example.com",
        ),
        namespaceId = namespaceId,
        name = name,
        description = description,
        content = content,
        parameters = parameters,
    )

    init {
        // -------------------------------------------------------------------------
        // fromDomain — field mapping
        // -------------------------------------------------------------------------

        "fromDomain maps id, namespaceId, name, description" {
            val id = UUID.randomUUID()
            val nsId = UUID.randomUUID()
            val p = prompt(id = id, namespaceId = nsId, name = "Greeting", description = "A greeting prompt")

            val node = PromptNode.fromDomain(p, objectMapper)

            node.id shouldBe id.toString()
            node.namespaceId shouldBe nsId.toString()
            node.name shouldBe "Greeting"
            node.description shouldBe "A greeting prompt"
        }

        "fromDomain sets namespaceId to null for platform-level prompts" {
            val p = prompt(namespaceId = null)

            val node = PromptNode.fromDomain(p, objectMapper)

            node.namespaceId.shouldBeNull()
        }

        "fromDomain serializes content list as JSON array" {
            val p = prompt(content = listOf("Line 1", "Line 2", "Line 3"))

            val node = PromptNode.fromDomain(p, objectMapper)

            val parsed = objectMapper.readTree(node.contentJson)
            parsed.isArray shouldBe true
            parsed.size() shouldBe 3
            parsed[0].asText() shouldBe "Line 1"
            parsed[2].asText() shouldBe "Line 3"
        }

        "fromDomain sets parametersJson to null when parameters list is empty" {
            val p = prompt(parameters = emptyList())

            val node = PromptNode.fromDomain(p, objectMapper)

            node.parametersJson.shouldBeNull()
        }

        "fromDomain serializes non-empty parameters list as JSON" {
            val params = listOf(
                PromptParameter(name = "name", description = "The name", defaultValue = "World"),
                PromptParameter(name = "language", description = null, defaultValue = null),
            )
            val p = prompt(parameters = params)

            val node = PromptNode.fromDomain(p, objectMapper)

            node.parametersJson.shouldNotBeNull()
            val parsed = objectMapper.readTree(node.parametersJson)
            parsed.isArray shouldBe true
            parsed.size() shouldBe 2
            parsed[0].get("name").asText() shouldBe "name"
            parsed[0].get("defaultValue").asText() shouldBe "World"
        }

        "fromDomain maps removed=true to removed property" {
            val p = prompt().copy(metadata = EntityMetadata(removed = true))

            val node = PromptNode.fromDomain(p, objectMapper)

            node.removed shouldBe true
        }

        "fromDomain maps removed=false to null (omit false from Neo4j storage)" {
            val p = prompt().copy(metadata = EntityMetadata(removed = false))

            val node = PromptNode.fromDomain(p, objectMapper)

            node.removed.shouldBeNull()
        }

        // -------------------------------------------------------------------------
        // toDomain — field mapping
        // -------------------------------------------------------------------------

        "toDomain maps id, namespaceId, name, description from node" {
            val id = UUID.randomUUID()
            val nsId = UUID.randomUUID()
            val node = PromptNode(
                id = id.toString(),
                namespaceId = nsId.toString(),
                name = "Greeting",
                description = "A greeting",
                contentJson = objectMapper.writeValueAsString(listOf("Hi {{name}}")),
            )

            val domain = node.toDomain(objectMapper)

            domain.id shouldBe id
            domain.namespaceId shouldBe nsId
            domain.name shouldBe "Greeting"
            domain.description shouldBe "A greeting"
        }

        "toDomain maps null namespaceId to null" {
            val node = PromptNode(
                id = UUID.randomUUID().toString(),
                namespaceId = null,
                name = "Platform",
                contentJson = objectMapper.writeValueAsString(listOf("Platform prompt")),
            )

            val domain = node.toDomain(objectMapper)

            domain.namespaceId.shouldBeNull()
        }

        "toDomain deserializes contentJson back to list" {
            val content = listOf("First", "Second")
            val node = PromptNode(
                id = UUID.randomUUID().toString(),
                name = "Test",
                contentJson = objectMapper.writeValueAsString(content),
            )

            val domain = node.toDomain(objectMapper)

            domain.content shouldBe content
        }

        "toDomain returns empty list when parametersJson is null" {
            val node = PromptNode(
                id = UUID.randomUUID().toString(),
                name = "Test",
                contentJson = objectMapper.writeValueAsString(listOf("Hello")),
                parametersJson = null,
            )

            val domain = node.toDomain(objectMapper)

            domain.parameters.shouldBeEmpty()
        }

        "toDomain deserializes parametersJson back to PromptParameter list" {
            val params = listOf(
                PromptParameter(name = "name", description = "User name", defaultValue = "World"),
            )
            val node = PromptNode(
                id = UUID.randomUUID().toString(),
                name = "Test",
                contentJson = objectMapper.writeValueAsString(listOf("Hi {{name}}")),
                parametersJson = objectMapper.writeValueAsString(params),
            )

            val domain = node.toDomain(objectMapper)

            domain.parameters shouldHaveSize 1
            domain.parameters[0].name shouldBe "name"
            domain.parameters[0].description shouldBe "User name"
            domain.parameters[0].defaultValue shouldBe "World"
        }

        "toDomain maps removed=true to metadata.removed=true" {
            val node = PromptNode(
                id = UUID.randomUUID().toString(),
                name = "Test",
                contentJson = objectMapper.writeValueAsString(listOf("Hello")),
                removed = true,
            )

            val domain = node.toDomain(objectMapper)

            domain.metadata.removed shouldBe true
        }

        "toDomain maps removed=null to metadata.removed=false" {
            val node = PromptNode(
                id = UUID.randomUUID().toString(),
                name = "Test",
                contentJson = objectMapper.writeValueAsString(listOf("Hello")),
                removed = null,
            )

            val domain = node.toDomain(objectMapper)

            domain.metadata.removed shouldBe false
        }

        // -------------------------------------------------------------------------
        // Full round-trip: fromDomain -> toDomain
        // -------------------------------------------------------------------------

        "round-trip fromDomain then toDomain preserves all fields" {
            val id = UUID.randomUUID()
            val nsId = UUID.randomUUID()
            val original = prompt(
                id = id,
                namespaceId = nsId,
                name = "Summarise",
                description = "Summarise the input",
                content = listOf("Summarise this: {{input}}", "Be concise."),
                parameters = listOf(
                    PromptParameter(name = "input", description = "Text to summarise"),
                    PromptParameter(name = "style", defaultValue = "bullet points"),
                ),
            )

            val roundTripped = PromptNode.fromDomain(original, objectMapper).toDomain(objectMapper)

            roundTripped.id shouldBe original.id
            roundTripped.namespaceId shouldBe original.namespaceId
            roundTripped.name shouldBe original.name
            roundTripped.description shouldBe original.description
            roundTripped.content shouldBe original.content
            roundTripped.parameters shouldHaveSize 2
            roundTripped.parameters[0].name shouldBe "input"
            roundTripped.parameters[0].description shouldBe "Text to summarise"
            roundTripped.parameters[1].name shouldBe "style"
            roundTripped.parameters[1].defaultValue shouldBe "bullet points"
        }

        "round-trip preserves platform-level prompt with empty parameters" {
            val original = prompt(
                namespaceId = null,
                content = listOf("You are a helpful assistant."),
                parameters = emptyList(),
            )

            val roundTripped = PromptNode.fromDomain(original, objectMapper).toDomain(objectMapper)

            roundTripped.namespaceId.shouldBeNull()
            roundTripped.content shouldBe listOf("You are a helpful assistant.")
            roundTripped.parameters.shouldBeEmpty()
        }
    }
}
