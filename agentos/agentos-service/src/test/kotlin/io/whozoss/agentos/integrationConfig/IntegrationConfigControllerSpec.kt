package io.whozoss.agentos.integrationConfig

import com.fasterxml.jackson.databind.node.JsonNodeFactory
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
 * Unit tests for [IntegrationConfigController] (Story 5.2 — declarative migration).
 *
 * See [AgentConfigControllerUnitSpec] for the rationale on what this spec covers
 * vs what is covered by [io.whozoss.agentos.security.declarative.MethodSecurityIntegrationSpec].
 */
class IntegrationConfigControllerSpec : StringSpec({

    val service = mockk<IntegrationConfigService>()
    val controller = IntegrationConfigController(service)

    val namespaceId = UUID.randomUUID()
    val params = JsonNodeFactory.instance.objectNode().put("apiUrl", "https://example.com")

    fun config(
        id: UUID = UUID.randomUUID(),
        nsId: UUID = namespaceId,
        name: String = "JIRA_PROD",
        integrationType: String = "JIRA",
    ) = IntegrationConfig(
        metadata = EntityMetadata(id = id),
        namespaceId = nsId,
        name = name,
        integrationType = integrationType,
        description = null,
        parameters = params,
    )

    fun resource(
        id: UUID? = UUID.randomUUID(),
        nsId: UUID? = namespaceId,
        name: String = "JIRA_PROD",
        integrationType: String = "JIRA",
    ) = IntegrationConfigResource(
        id = id,
        namespaceId = nsId,
        name = name,
        integrationType = integrationType,
        description = null,
        parameters = params,
    )

    beforeTest { clearAllMocks() }

    "toResource maps all fields" {
        val c = config(name = "SLACK_DEV", integrationType = "SLACK").copy(description = "Dev Slack")
        val r = controller.toResource(c)

        r.name shouldBe "SLACK_DEV"
        r.integrationType shouldBe "SLACK"
        r.description shouldBe "Dev Slack"
        r.parameters shouldBe params
    }

    "toDomain maps all fields and generates UUID when id is null" {
        val first = controller.toDomain(resource(id = null))
        val second = controller.toDomain(resource(id = null))

        (first.metadata.id == second.metadata.id) shouldBe false
    }

    "update preserves the persisted namespaceId when client sends a different value" {
        val c = config()
        val otherNs = UUID.randomUUID()
        val payload = resource(id = c.id, nsId = otherNs, name = "RENAMED")
        every { service.findById(c.id) } returns c
        every { service.update(any()) } answers {
            val saved = firstArg<IntegrationConfig>()
            saved.namespaceId shouldBe namespaceId
            saved.name shouldBe "RENAMED"
            saved
        }

        controller.update(c.id, payload)

        verify(exactly = 1) { service.update(any()) }
    }

    "update throws 404 when the IntegrationConfig does not exist" {
        val id = UUID.randomUUID()
        every { service.findById(id) } returns null

        shouldThrow<ResourceNotFoundException> { controller.update(id, resource(id = id)) }
    }
})
