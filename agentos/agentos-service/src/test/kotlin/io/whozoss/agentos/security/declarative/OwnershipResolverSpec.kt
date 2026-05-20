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

    val integrationConfigAware = object : OwnershipAware {
        override val ownershipEntityType = EntityType.INTEGRATION_CONFIG
        override fun resolveOwner(targetId: UUID): UUID? = null
    }

    val resolver = OwnershipResolver(listOf(aiProviderAware, integrationConfigAware))

    "supportedTypes returns entity types from all OwnershipAware beans" {
        resolver.supportedTypes shouldContainExactlyInAnyOrder listOf(
            EntityType.AI_PROVIDER,
            EntityType.INTEGRATION_CONFIG,
        )
    }

    "resolveOwner delegates to the correct OwnershipAware by entity type" {
        val targetId = UUID.randomUUID()
        resolver.resolveOwner(EntityType.AI_PROVIDER, targetId) shouldBe ownerId
    }

    "resolveOwner returns null when the OwnershipAware returns null" {
        val targetId = UUID.randomUUID()
        resolver.resolveOwner(EntityType.INTEGRATION_CONFIG, targetId) shouldBe null
    }

    "resolveOwner returns null for unsupported entity types" {
        val targetId = UUID.randomUUID()
        resolver.resolveOwner(EntityType.NAMESPACE, targetId) shouldBe null
        resolver.resolveOwner(EntityType.AGENT_CONFIG, targetId) shouldBe null
    }

    "supportedTypes is empty when no OwnershipAware beans are registered" {
        val emptyResolver = OwnershipResolver(emptyList())
        emptyResolver.supportedTypes shouldBe emptySet()
    }
})
