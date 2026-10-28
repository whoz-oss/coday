package io.whozoss.agentos.security.declarative

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.whozoss.agentos.permissions.EntityType
import java.util.UUID

class OwnershipResolverSpec : StringSpec({

    val ownerId = UUID.randomUUID()

    val aiProviderAware = object : OwnershipAware {
        override val ownershipEntityType = EntityType.AI_PROVIDER
        override fun resolveOwner(targetId: UUID): UUID? = ownerId
    }

    val resolver = OwnershipResolver(listOf(aiProviderAware))

    "supportedTypes returns entity types from all OwnershipAware beans" {
        resolver.supportedTypes shouldContainExactlyInAnyOrder listOf(
            EntityType.AI_PROVIDER,
        )
    }

    "resolveOwner delegates to the correct OwnershipAware by entity type" {
        val targetId = UUID.randomUUID()
        resolver.resolveOwner(EntityType.AI_PROVIDER, targetId) shouldBe ownerId
    }

    "resolveOwner returns null for unsupported entity types" {
        val targetId = UUID.randomUUID()
        resolver.resolveOwner(EntityType.NAMESPACE, targetId) shouldBe null
        resolver.resolveOwner(EntityType.AGENT_CONFIG, targetId) shouldBe null
        // IntegrationConfig authorization now lives in IntegrationConfigServiceImpl's *ForCaller
        // surface (via ScopedAccessPolicy) and is not registered with the OwnershipResolver.
        resolver.resolveOwner(EntityType.INTEGRATION_CONFIG, targetId) shouldBe null
    }

    "supportedTypes is empty when no OwnershipAware beans are registered" {
        val emptyResolver = OwnershipResolver(emptyList())
        emptyResolver.supportedTypes shouldBe emptySet()
    }
})
