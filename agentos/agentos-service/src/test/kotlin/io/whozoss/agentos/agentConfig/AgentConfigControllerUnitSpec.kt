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
 * - Mapping (toResource / toDomain)
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
    ) = AgentConfig(
        metadata = EntityMetadata(id = id),
        namespaceId = nsId,
        name = name,
        description = "An agent",
        instructions = "Be helpful.",
        modelName = "BIG",
    )

    fun resource(
        id: UUID? = UUID.randomUUID(),
        nsId: UUID = namespaceId,
        name: String = "my-agent",
    ) = AgentConfigResource(
        id = id,
        namespaceId = nsId,
        name = name,
    )

    beforeTest { clearAllMocks() }

    // -------------------------------------------------------------------------
    // Mapping
    // -------------------------------------------------------------------------

    "toResource maps all fields" {
        val c = config(name = "coder").copy(description = "Writes code", modelName = "claude-3-opus")
        val r = controller.toResource(c)

        r.id shouldBe c.metadata.id
        r.namespaceId shouldBe namespaceId
        r.name shouldBe "coder"
        r.description shouldBe "Writes code"
        r.modelName shouldBe "claude-3-opus"
    }

    "toDomain maps all fields and generates an id when null" {
        val first = controller.toDomain(resource(id = null))
        val second = controller.toDomain(resource(id = null))

        // Two null-id resources must produce distinct UUIDs — proves a fresh UUID is generated
        (first.metadata.id == second.metadata.id) shouldBe false
    }

    // -------------------------------------------------------------------------
    // update — mass-assignment guard (server-owned namespaceId preserved)
    // -------------------------------------------------------------------------

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
})
