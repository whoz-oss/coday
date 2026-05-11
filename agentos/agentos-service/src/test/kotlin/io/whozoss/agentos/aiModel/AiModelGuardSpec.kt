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
import io.whozoss.agentos.sdk.entity.EntityMetadata
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.Authentication
import java.util.UUID

class AiModelGuardSpec : StringSpec({

    val aiProviderService = mockk<AiProviderService>()
    val permissionService = mockk<PermissionService>()
    val guard = AiModelGuard(aiProviderService, permissionService)

    val callerId = UUID.randomUUID()
    val otherId = UUID.randomUUID()
    val namespaceId = UUID.randomUUID()
    val aiProviderId = UUID.randomUUID()

    fun auth(userId: UUID = callerId): Authentication =
        UsernamePasswordAuthenticationToken(userId.toString(), "n/a", emptyList())

    fun provider(nsId: UUID? = namespaceId, uId: UUID? = null) = AiProvider(
        metadata = EntityMetadata(id = aiProviderId),
        namespaceId = nsId,
        userId = uId,
        name = "anthropic",
        apiType = AiApiType.Anthropic,
    )

    fun resource(providerId: UUID? = aiProviderId) = AiModelResource(
        id = null,
        aiProviderId = providerId,
        apiModelName = "claude-haiku-4-5",
    )

    // -------------------------------------------------------------------------
    // canCreateVerdict — 7 tests
    // -------------------------------------------------------------------------

    "canCreateVerdict returns Ok when caller has WRITE on parent NS-shared provider" {
        every { aiProviderService.findById(aiProviderId) } returns provider()
        every { permissionService.hasPermission(callerId.toString(), EntityType.NAMESPACE, namespaceId.toString(), Action.READ) } returns true
        every { permissionService.hasPermission(callerId.toString(), EntityType.NAMESPACE, namespaceId.toString(), Action.WRITE) } returns true
        guard.canCreateVerdict(resource(), auth()) shouldBe AiModelGuard.CreateVerdict.Ok
    }

    "canCreateVerdict returns ParentNotWritable when caller has READ but lacks WRITE on parent NS-shared (SF1 : 403 honest)" {
        every { aiProviderService.findById(aiProviderId) } returns provider()
        every { permissionService.hasPermission(callerId.toString(), EntityType.NAMESPACE, namespaceId.toString(), Action.READ) } returns true
        every { permissionService.hasPermission(callerId.toString(), EntityType.NAMESPACE, namespaceId.toString(), Action.WRITE) } returns false
        guard.canCreateVerdict(resource(), auth()) shouldBe AiModelGuard.CreateVerdict.ParentNotWritable
    }

    "canCreateVerdict returns ParentInvisible when aiProviderId is null in the payload" {
        guard.canCreateVerdict(resource(providerId = null), auth()) shouldBe AiModelGuard.CreateVerdict.ParentInvisible
    }

    "canCreateVerdict returns ParentInvisible when the provider does not exist" {
        every { aiProviderService.findById(aiProviderId) } returns null
        guard.canCreateVerdict(resource(), auth()) shouldBe AiModelGuard.CreateVerdict.ParentInvisible
    }

    "canCreateVerdict returns Ok when caller owns the parent (parent user-scope)" {
        every { aiProviderService.findById(aiProviderId) } returns provider(nsId = namespaceId, uId = callerId)
        guard.canCreateVerdict(resource(), auth(callerId)) shouldBe AiModelGuard.CreateVerdict.Ok
    }

    "canCreateVerdict returns ParentInvisible when parent user-scope owned by another user (cross-user -> 404)" {
        every { aiProviderService.findById(aiProviderId) } returns provider(nsId = null, uId = otherId)
        guard.canCreateVerdict(resource(), auth(callerId)) shouldBe AiModelGuard.CreateVerdict.ParentInvisible
    }

    "canCreateVerdict returns ParentInvisible when parent NS-shared but caller is non-member (SF1 fix : 404 not 403)" {
        every { aiProviderService.findById(aiProviderId) } returns provider(nsId = namespaceId, uId = null)
        every { permissionService.hasPermission(callerId.toString(), EntityType.NAMESPACE, namespaceId.toString(), Action.READ) } returns false
        guard.canCreateVerdict(resource(), auth(callerId)) shouldBe AiModelGuard.CreateVerdict.ParentInvisible
    }

    // -------------------------------------------------------------------------
    // canListByProvider / canSeeProvider — 5 tests
    // -------------------------------------------------------------------------

    "canListByProvider returns true when caller has READ on the parent provider's namespace" {
        every { aiProviderService.findById(aiProviderId) } returns provider()
        every { permissionService.hasPermission(callerId.toString(), EntityType.NAMESPACE, namespaceId.toString(), Action.READ) } returns true
        guard.canListByProvider(aiProviderId, auth()) shouldBe true
    }

    "canListByProvider returns false when caller lacks READ on the parent provider's namespace" {
        every { aiProviderService.findById(aiProviderId) } returns provider()
        every { permissionService.hasPermission(callerId.toString(), EntityType.NAMESPACE, namespaceId.toString(), Action.READ) } returns false
        guard.canListByProvider(aiProviderId, auth()) shouldBe false
    }

    "canListByProvider returns false when the provider does not exist (fail-closed)" {
        every { aiProviderService.findById(aiProviderId) } returns null
        guard.canListByProvider(aiProviderId, auth()) shouldBe false
    }

    "canListByProvider returns true when the provider is user-scoped AND owned by caller (SF5 fix)" {
        every { aiProviderService.findById(aiProviderId) } returns provider(nsId = null, uId = callerId)
        guard.canListByProvider(aiProviderId, auth(callerId)) shouldBe true
    }

    "canListByProvider returns false when the provider is user-scoped AND owned by another user" {
        every { aiProviderService.findById(aiProviderId) } returns provider(nsId = null, uId = otherId)
        guard.canListByProvider(aiProviderId, auth(callerId)) shouldBe false
    }
})
