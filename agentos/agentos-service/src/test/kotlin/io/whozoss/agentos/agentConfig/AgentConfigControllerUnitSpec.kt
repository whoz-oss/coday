package io.whozoss.agentos.agentConfig

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

/**
 * Unit tests for [AgentConfigController].
 *
 * Permission checks are declarative (`@PreAuthorize`) and only fire when the
 * controller is invoked through Spring AOP. In pure unit tests we call the
 * controller directly, bypassing the proxy — so authorization paths are NOT
 * exercised here. Those are covered by [io.whozoss.agentos.security.declarative.MethodSecurityIntegrationSpec].
 *
 * What this spec covers:
 * - Mapping (`toResource` / `toDomain`, including null optional fields)
 * - Inherited [io.whozoss.agentos.entity.EntityController] delegates:
 *   `getById` (found / not-found), `getByIds`, `listByParent`, `create`, `delete`
 * - `update` mass-assignment guard (server-owned `namespaceId` preserved)
 * - `update` 404-on-missing path
 */
class AgentConfigControllerUnitSpec : StringSpec({

    val service = mockk<AgentConfigService>()
    val controller = AgentConfigController(service)

    val namespaceId = UUID.randomUUID()

    fun config(
        id: UUID = UUID.randomUUID(),
        nsId: UUID = namespaceId,
        name: String = "my-agent",
        description: String? = "An agent",
        instructions: String? = "Be helpful.",
        modelName: String? = "BIG",
    ) = AgentConfig(
        metadata = EntityMetadata(id = id),
        namespaceId = nsId,
        name = name,
        description = description,
        instructions = instructions,
        modelName = modelName,
    )

    fun resource(
        id: UUID? = UUID.randomUUID(),
        nsId: UUID = namespaceId,
        name: String = "my-agent",
        description: String? = "An agent",
        instructions: String? = "Be helpful.",
        modelName: String? = "BIG",
    ) = AgentConfigResource(
        id = id,
        namespaceId = nsId,
        name = name,
        description = description,
        instructions = instructions,
        modelName = modelName,
    )

    beforeTest { clearAllMocks() }

    // -------------------------------------------------------------------------
    // toResource mapping
    // -------------------------------------------------------------------------

    "toResource maps all fields from AgentConfig to AgentConfigResource" {
        val id = UUID.randomUUID()
        val c = config(
            id = id,
            name = "coder",
            description = "Writes code",
            instructions = "Write clean code.",
            modelName = "claude-3-opus",
        )

        val result = controller.toResource(c)

        result shouldBe AgentConfigResource(
            id = id,
            namespaceId = namespaceId,
            name = "coder",
            description = "Writes code",
            instructions = "Write clean code.",
            modelName = "claude-3-opus",
        )
    }

    "toResource preserves null optional fields" {
        val c = config(description = null, instructions = null, modelName = null)

        val result = controller.toResource(c)

        result.description shouldBe null
        result.instructions shouldBe null
        result.modelName shouldBe null
    }

    // -------------------------------------------------------------------------
    // toDomain mapping
    // -------------------------------------------------------------------------

    "toDomain maps all fields from AgentConfigResource to AgentConfig" {
        val id = UUID.randomUUID()
        val r = resource(
            id = id,
            name = "reviewer",
            description = "Reviews PRs",
            instructions = "Be thorough.",
            modelName = "SMALL",
        )

        val result = controller.toDomain(r)

        result.metadata.id shouldBe id
        result.namespaceId shouldBe namespaceId
        result.name shouldBe "reviewer"
        result.description shouldBe "Reviews PRs"
        result.instructions shouldBe "Be thorough."
        result.modelName shouldBe "SMALL"
    }

    "toDomain generates a random UUID when resource id is null" {
        val first = controller.toDomain(resource(id = null))
        val second = controller.toDomain(resource(id = null))

        // Two null-id resources must produce distinct UUIDs — proves a fresh UUID is generated
        (first.metadata.id == second.metadata.id) shouldBe false
    }

    "toDomain preserves null optional fields" {
        val r = resource(description = null, instructions = null, modelName = null)

        val result = controller.toDomain(r)

        result.description shouldBe null
        result.instructions shouldBe null
        result.modelName shouldBe null
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

        shouldThrow<ResourceNotFoundException> { controller.getById(id) }
    }

    // -------------------------------------------------------------------------
    // getByIds (inherited)
    // -------------------------------------------------------------------------

    "getByIds returns matching entities mapped to resources" {
        val c1 = config(name = "agent-a")
        val c2 = config(name = "agent-b")
        every { service.findByIds(listOf(c1.id, c2.id)) } returns listOf(c1, c2)

        val result = controller.getByIds(listOf(c1.id, c2.id))

        result shouldBe listOf(controller.toResource(c1), controller.toResource(c2))
    }

    // -------------------------------------------------------------------------
    // listByParent (inherited)
    // -------------------------------------------------------------------------

    "listByParent returns configs for the given namespaceId" {
        val c1 = config(name = "alpha")
        val c2 = config(name = "beta")
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
    // update — delegate happy path + mass-assignment guard + 404
    // -------------------------------------------------------------------------

    "update delegates to service when entity exists and returns mapped resource" {
        val c = config()
        val updatedResource = resource(id = c.id, name = "updated-agent")
        val updatedDomain = controller.toDomain(updatedResource)
        every { service.findById(c.id) } returns c
        every { service.update(any()) } returns updatedDomain

        val result = controller.update(c.id, updatedResource)

        result shouldBe controller.toResource(updatedDomain)
        verify(exactly = 1) { service.update(any()) }
    }

    "update preserves the persisted namespaceId when client sends a different value" {
        val c = config()
        val otherNs = UUID.randomUUID()
        val payload = resource(id = c.id, nsId = otherNs, name = "renamed")
        every { service.findById(c.id) } returns c
        every { service.update(any()) } answers {
            val saved = firstArg<AgentConfig>()
            saved.namespaceId shouldBe namespaceId
            saved.name shouldBe "renamed"
            saved
        }

        controller.update(c.id, payload)

        verify(exactly = 1) { service.update(any()) }
    }

    "update throws 404 when the AgentConfig does not exist" {
        val id = UUID.randomUUID()
        every { service.findById(id) } returns null

        shouldThrow<ResourceNotFoundException> { controller.update(id, resource(id = id)) }
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

        shouldThrow<ResourceNotFoundException> { controller.delete(id) }
    }
})
