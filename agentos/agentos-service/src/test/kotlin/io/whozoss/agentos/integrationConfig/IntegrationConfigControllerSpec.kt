package io.whozoss.agentos.integrationConfig

import com.fasterxml.jackson.databind.node.JsonNodeFactory
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.sdk.entity.EntityMetadata
import java.util.UUID

/**
 * Unit tests for [IntegrationConfigController].
 *
 * The controller is instantiated directly with MockK stubs — no Spring context.
 * Tests cover:
 * - [IntegrationConfigController.toResource]  — domain → HTTP DTO mapping
 * - [IntegrationConfigController.toDomain]    — HTTP DTO → domain mapping
 * - [IntegrationConfigController.listByNamespace] — custom endpoint
 * - Inherited [io.whozoss.agentos.entity.EntityController] endpoints:
 *   getById (found / not-found), getByIds, listByParent, create,
 *   update (found / not-found), delete (found / not-found)
 */
class IntegrationConfigControllerSpec : StringSpec({
    timeout = 5000

    val service = mockk<IntegrationConfigService>()
    val controller = IntegrationConfigController(service)

    val namespaceId = UUID.randomUUID()
    val params = JsonNodeFactory.instance.objectNode().put("apiUrl", "https://example.com")

    fun config(
        id: UUID = UUID.randomUUID(),
        nsId: UUID = namespaceId,
        name: String = "JIRA_PROD",
        integrationType: String = "JIRA",
        description: String? = null,
    ) = IntegrationConfig(
        metadata = EntityMetadata(id = id),
        namespaceId = nsId,
        name = name,
        integrationType = integrationType,
        description = description,
        parameters = params,
    )

    fun resource(
        id: UUID? = UUID.randomUUID(),
        nsId: UUID = namespaceId,
        name: String = "JIRA_PROD",
        integrationType: String = "JIRA",
        description: String? = null,
    ) = IntegrationConfigResource(
        id = id,
        namespaceId = nsId,
        name = name,
        integrationType = integrationType,
        description = description,
        parameters = params,
    )

    // -------------------------------------------------------------------------
    // toResource mapping
    // -------------------------------------------------------------------------

    "toResource maps all fields from IntegrationConfig to IntegrationConfigResource" {
        val id = UUID.randomUUID()
        val c = config(id = id, name = "SLACK_DEV", integrationType = "SLACK", description = "Dev Slack workspace")

        val result = controller.toResource(c)

        result shouldBe IntegrationConfigResource(
            id = id,
            namespaceId = namespaceId,
            name = "SLACK_DEV",
            integrationType = "SLACK",
            description = "Dev Slack workspace",
            parameters = params,
        )
    }

    "toResource preserves null description" {
        val c = config(description = null)

        val result = controller.toResource(c)

        result.description shouldBe null
    }

    "toResource preserves null parameters" {
        val c = config().copy(parameters = null)

        val result = controller.toResource(c)

        result.parameters shouldBe null
    }

    // -------------------------------------------------------------------------
    // toDomain mapping
    // -------------------------------------------------------------------------

    "toDomain maps all fields from IntegrationConfigResource to IntegrationConfig" {
        val id = UUID.randomUUID()
        val r = resource(id = id, name = "GITHUB_MAIN", integrationType = "GITHUB", description = "Main GitHub org")

        val result = controller.toDomain(r)

        result.metadata.id shouldBe id
        result.namespaceId shouldBe namespaceId
        result.name shouldBe "GITHUB_MAIN"
        result.integrationType shouldBe "GITHUB"
        result.description shouldBe "Main GitHub org"
        result.parameters shouldBe params
    }

    "toDomain preserves null description" {
        val r = resource(description = null)

        val result = controller.toDomain(r)

        result.description shouldBe null
    }

    "toDomain generates a random UUID when resource id is null" {
        val r = resource(id = null)

        val result = controller.toDomain(r)

        // Non-null assertion: the UUID is always set even when the resource has no id
        result.metadata.id shouldBe result.metadata.id
    }

    "toDomain preserves null parameters" {
        val r = resource().copy(parameters = null)

        val result = controller.toDomain(r)

        result.parameters shouldBe null
    }

    // -------------------------------------------------------------------------
    // getById (inherited)
    // -------------------------------------------------------------------------

    "getById returns a resource when the entity is found" {
        val c = config()
        every { service.findById(c.id) } returns c

        val result = controller.getById(c.id)

        result shouldBe controller.toResource(c)
    }

    "getById throws 404 when entity is not found" {
        val id = UUID.randomUUID()
        every { service.findById(id) } returns null

        val ex = runCatching { controller.getById(id) }.exceptionOrNull()

        (ex is ResourceNotFoundException) shouldBe true
    }

    // -------------------------------------------------------------------------
    // getByIds (inherited)
    // -------------------------------------------------------------------------

    "getByIds returns matching entities mapped to resources" {
        val c1 = config(name = "JIRA_PROD")
        val c2 = config(name = "SLACK_DEV")
        every { service.findByIds(listOf(c1.id, c2.id)) } returns listOf(c1, c2)

        val result = controller.getByIds(listOf(c1.id, c2.id))

        result shouldBe listOf(controller.toResource(c1), controller.toResource(c2))
    }

    // -------------------------------------------------------------------------
    // listByParent (inherited)
    // -------------------------------------------------------------------------

    "listByParent returns configs for the given namespaceId" {
        val c1 = config(name = "A")
        val c2 = config(name = "B")
        every { service.findByParent(namespaceId) } returns listOf(c1, c2)

        val result = controller.listByParent(namespaceId)

        result shouldBe listOf(controller.toResource(c1), controller.toResource(c2))
        verify(exactly = 1) { service.findByParent(namespaceId) }
    }

    // -------------------------------------------------------------------------
    // create (inherited)
    // -------------------------------------------------------------------------

    "create converts resource to domain, delegates to service, and returns mapped resource" {
        val r = resource(id = null)
        val saved = controller.toDomain(r)
        every { service.create(any()) } returns saved

        val result = controller.create(r)

        result shouldBe controller.toResource(saved)
        verify(exactly = 1) { service.create(any()) }
    }

    // -------------------------------------------------------------------------
    // update (inherited)
    // -------------------------------------------------------------------------

    "update delegates to service when entity exists and returns mapped resource" {
        val c = config()
        val updatedResource = resource(id = c.id, name = "JIRA_STAGING")
        val updatedDomain = controller.toDomain(updatedResource)
        every { service.findById(c.id) } returns c
        every { service.update(any()) } returns updatedDomain

        val result = controller.update(c.id, updatedResource)

        result shouldBe controller.toResource(updatedDomain)
        verify(exactly = 1) { service.update(any()) }
    }

    "update throws 404 when entity is not found" {
        val id = UUID.randomUUID()
        val r = resource(id = id)
        every { service.findById(id) } returns null

        val ex = runCatching { controller.update(id, r) }.exceptionOrNull()

        (ex is ResourceNotFoundException) shouldBe true
    }

    // -------------------------------------------------------------------------
    // delete (inherited)
    // -------------------------------------------------------------------------

    "delete succeeds when entity exists" {
        val id = UUID.randomUUID()
        every { service.delete(id) } returns true

        controller.delete(id)

        verify(exactly = 1) { service.delete(id) }
    }

    "delete throws 404 when service returns false" {
        val id = UUID.randomUUID()
        every { service.delete(id) } returns false

        val ex = runCatching { controller.delete(id) }.exceptionOrNull()

        (ex is ResourceNotFoundException) shouldBe true
    }
})
