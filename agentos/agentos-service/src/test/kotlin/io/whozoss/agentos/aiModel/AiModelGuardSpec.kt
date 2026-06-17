package io.whozoss.agentos.aiModel

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.whozoss.agentos.aiProvider.AiProviderService
import io.whozoss.agentos.permissions.Action
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.sdk.aiProvider.AiApiType
import io.whozoss.agentos.sdk.aiProvider.AiProvider
import io.whozoss.agentos.sdk.api.aiProvider.AiModelDto
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.user.User
import io.whozoss.agentos.user.UserService
import java.util.UUID

/**
 * Unit tests for [AiModelGuard].
 *
 * Covers the SpEL-callable `canCreate` and `canListByProvider` predicates that gate
 * `@PreAuthorize("@aiModelGuard.canCreate(...)")` on [AiModelController].
 */
class AiModelGuardSpec : StringSpec({

    val aiProviderService = mockk<AiProviderService>()
    val permissionService = mockk<PermissionService>()
    val userService = mockk<UserService>()
    val guard = AiModelGuard(aiProviderService, permissionService, userService)

    val callerId = UUID.randomUUID()
    val caller = User(
        metadata = EntityMetadata(id = callerId),
        externalId = "alice@example.com",
        email = "alice@example.com",
        isAdmin = false,
    )
    val namespaceId = UUID.randomUUID()
    val aiProviderId = UUID.randomUUID()

    fun provider(nsId: UUID? = namespaceId, uId: UUID? = null) = AiProvider(
        metadata = EntityMetadata(id = aiProviderId),
        namespaceId = nsId,
        userId = uId,
        name = "anthropic",
        apiType = AiApiType.Anthropic,
    )

    fun resource(providerId: UUID? = aiProviderId) = AiModelDto(
        id = null,
        aiProviderId = providerId,
        namespaceId = null,
        apiModelName = "claude-haiku-4-5",
    )

    "canCreate returns true when caller has WRITE on the parent provider's namespace" {
        every { userService.getCurrentUser() } returns caller
        every { aiProviderService.findById(aiProviderId) } returns provider()
        every {
            permissionService.hasPermission(callerId.toString(), EntityType.NAMESPACE, namespaceId.toString(), Action.WRITE)
        } returns true

        guard.canCreate(resource()) shouldBe true
    }

    "canCreate returns false when caller lacks WRITE on the parent provider's namespace" {
        every { userService.getCurrentUser() } returns caller
        every { aiProviderService.findById(aiProviderId) } returns provider()
        every {
            permissionService.hasPermission(callerId.toString(), EntityType.NAMESPACE, namespaceId.toString(), Action.WRITE)
        } returns false

        guard.canCreate(resource()) shouldBe false
    }

    "canCreate returns false when aiProviderId is null in the payload" {
        guard.canCreate(resource(providerId = null)) shouldBe false
    }

    "canCreate returns false when the provider does not exist" {
        every { aiProviderService.findById(aiProviderId) } returns null

        guard.canCreate(resource()) shouldBe false
    }

    "canCreate returns false when the provider is user-scoped (namespaceId null)" {
        every { aiProviderService.findById(aiProviderId) } returns provider(nsId = null, uId = UUID.randomUUID())

        guard.canCreate(resource()) shouldBe false
    }

    "canListByProvider returns true when caller has READ on the parent provider's namespace" {
        every { userService.getCurrentUser() } returns caller
        every { aiProviderService.findById(aiProviderId) } returns provider()
        every {
            permissionService.hasPermission(callerId.toString(), EntityType.NAMESPACE, namespaceId.toString(), Action.READ)
        } returns true

        guard.canListByProvider(aiProviderId) shouldBe true
    }

    "canListByProvider returns false when caller lacks READ on the parent provider's namespace" {
        every { userService.getCurrentUser() } returns caller
        every { aiProviderService.findById(aiProviderId) } returns provider()
        every {
            permissionService.hasPermission(callerId.toString(), EntityType.NAMESPACE, namespaceId.toString(), Action.READ)
        } returns false

        guard.canListByProvider(aiProviderId) shouldBe false
    }

    "canListByProvider returns false when the provider does not exist (fail-closed)" {
        every { aiProviderService.findById(aiProviderId) } returns null

        guard.canListByProvider(aiProviderId) shouldBe false
    }

    "canListByProvider returns false when the provider is user-scoped (closes legacy listing leak)" {
        // The controller body would otherwise call findByAiProviderId without an owner
        // filter and return AiModels owned by another user. fail-closed → 403.
        every { aiProviderService.findById(aiProviderId) } returns provider(nsId = null, uId = UUID.randomUUID())

        guard.canListByProvider(aiProviderId) shouldBe false
    }
})
