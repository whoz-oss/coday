package io.whozoss.agentos.aiProvider

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.sdk.aiProvider.AiApiType
import io.whozoss.agentos.sdk.aiProvider.AiProvider
import io.whozoss.agentos.sdk.entity.EntityMetadata
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * Unit tests for [AiProviderController].
 *
 * See [io.whozoss.agentos.agentConfig.AgentConfigControllerUnitSpec] for the rationale.
 *
 * One special case: `create` rejects user-scoped payloads with 400 BEFORE `@PreAuthorize`
 * (it's a payload validation, not an authorization check). This path runs in a unit test
 * and is asserted here.
 */
class AiProviderControllerSpec : StringSpec({

    val service = mockk<AiProviderService>()
    val controller = AiProviderController(service)

    val namespaceId = UUID.randomUUID()

    fun config(
        id: UUID = UUID.randomUUID(),
        nsId: UUID? = namespaceId,
        uId: UUID? = null,
        name: String = "anthropic",
        apiKey: String? = null,
    ) = AiProvider(
        metadata = EntityMetadata(id = id),
        namespaceId = nsId,
        userId = uId,
        name = name,
        apiType = AiApiType.Anthropic,
        apiKey = apiKey,
    )

    fun resource(
        id: UUID? = UUID.randomUUID(),
        nsId: UUID? = namespaceId,
        uId: UUID? = null,
        name: String = "anthropic",
        apiKey: String? = null,
    ) = AiProviderResource(
        id = id,
        namespaceId = nsId,
        userId = uId,
        name = name,
        apiType = AiApiType.Anthropic,
        apiKey = apiKey,
    )

    beforeTest { clearAllMocks() }

    // -------------------------------------------------------------------------
    // Mapping
    // -------------------------------------------------------------------------

    "toResource passes the apiKey through unchanged" {
        controller.toResource(config(apiKey = "sk-ant-api03-abcdefghijklmnop")).apiKey shouldBe
            "sk-ant-api03-abcdefghijklmnop"
    }

    "toResource maps namespaceId and userId" {
        val uid = UUID.randomUUID()
        val r = controller.toResource(config(nsId = namespaceId, uId = uid))
        r.namespaceId shouldBe namespaceId
        r.userId shouldBe uid
    }

    // -------------------------------------------------------------------------
    // create — payload validation: user-scoped refused with 400
    // -------------------------------------------------------------------------

    "create throws 403 when the entity is user-scoped (namespaceId null) — legacy path #809" {
        // Note: in production @PreAuthorize fires first and returns 403 due to null target;
        // in this unit test (no AOP) the body's defense-in-depth fail-closed throw fires.
        val r = resource(id = null, nsId = null, uId = UUID.randomUUID())

        val ex = shouldThrow<ResponseStatusException> { controller.create(r) }

        ex.statusCode shouldBe HttpStatus.FORBIDDEN
        (ex.reason ?: "") shouldBe "namespace-scoped AiProvider required (user-scoped deprecated, see #809)"
        verify(exactly = 0) { service.create(any()) }
    }

    "toDomain forces userId=null for namespace-scoped creation (anti-spoofing)" {
        val spoofedUserId = UUID.randomUUID()
        val r = resource(id = null, nsId = UUID.randomUUID(), uId = spoofedUserId)

        controller.toDomain(r).userId shouldBe null
    }

    "toDomain preserves userId for user-scoped paths (legacy reads only — create rejects this)" {
        val legacyUserId = UUID.randomUUID()
        val r = resource(id = null, nsId = null, uId = legacyUserId)

        controller.toDomain(r).userId shouldBe legacyUserId
    }

    "create succeeds when the entity is namespace-scoped" {
        val r = resource(id = null, name = "openai")
        val saved = config(name = "openai")
        every { service.create(any()) } returns saved

        val result = controller.create(r)

        result.id shouldBe saved.metadata.id
        verify(exactly = 1) { service.create(any()) }
    }

    // -------------------------------------------------------------------------
    // update — server-owned-field preservation (mass-assignment guard)
    // -------------------------------------------------------------------------

    "update preserves the persisted namespaceId and userId when client sends different values" {
        val existing = config(apiKey = "real-key")
        val otherNs = UUID.randomUUID()
        val otherUser = UUID.randomUUID()
        val payload = resource(id = existing.metadata.id, nsId = otherNs, uId = otherUser, name = "renamed")
        every { service.findById(existing.metadata.id) } returns existing
        every { service.update(any()) } answers {
            val saved = firstArg<AiProvider>()
            saved.namespaceId shouldBe namespaceId
            saved.userId shouldBe null
            saved.name shouldBe "renamed"
            saved
        }

        controller.update(existing.metadata.id, payload)

        verify(exactly = 1) { service.update(any()) }
    }

    "update keeps the persisted apiKey when client sends a blank apiKey" {
        val existing = config(apiKey = "real-key")
        val payload = resource(id = existing.metadata.id, apiKey = "")
        every { service.findById(existing.metadata.id) } returns existing
        every { service.update(any()) } answers {
            val saved = firstArg<AiProvider>()
            saved.apiKey shouldBe "real-key"
            saved
        }

        controller.update(existing.metadata.id, payload)
    }

    "update replaces the persisted apiKey when client sends a non-blank apiKey" {
        val existing = config(apiKey = "old-key")
        val payload = resource(id = existing.metadata.id, apiKey = "new-key")
        every { service.findById(existing.metadata.id) } returns existing
        every { service.update(any()) } answers {
            val saved = firstArg<AiProvider>()
            saved.apiKey shouldBe "new-key"
            saved
        }

        controller.update(existing.metadata.id, payload)
    }

    "update throws 404 when the AiProvider does not exist" {
        val id = UUID.randomUUID()
        every { service.findById(id) } returns null

        shouldThrow<ResourceNotFoundException> { controller.update(id, resource(id = id)) }
    }
})
