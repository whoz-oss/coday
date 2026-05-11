package io.whozoss.agentos.security.declarative

import io.whozoss.agentos.aiProvider.AiProviderService
import io.whozoss.agentos.integrationConfig.IntegrationConfigService
import io.whozoss.agentos.permissions.EntityType
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Resolves the owner (`userId`) of a scope-aware entity by id, for the ownership
 * branch of [AgentOsPermissionEvaluator].
 *
 * Extracted from the evaluator to break a potential Spring dependency cycle: the
 * evaluator is wired early in Spring Security ; the entity services arrive later.
 * Keeping the dispatcher in a separate `@Component` lets Spring break the wiring
 * chain cleanly. If `@Lazy` is needed in the evaluator's injection of this
 * resolver, it can be added there without touching this class.
 *
 * Supported entity types : [EntityType.AI_PROVIDER], [EntityType.INTEGRATION_CONFIG].
 * [EntityType.AI_MODEL] is intentionally not supported here — AiModel resolves
 * its scope from its parent provider (denormalized) and is handled by the
 * follow-up quick-spec dedicated to AiModel.
 */
@Component
class OwnershipResolver(
    private val aiProviderService: AiProviderService,
    private val integrationConfigService: IntegrationConfigService,
) {
    fun resolveOwner(entityType: EntityType, targetId: UUID): UUID? = when (entityType) {
        EntityType.AI_PROVIDER -> aiProviderService.findById(targetId)?.userId
        EntityType.INTEGRATION_CONFIG -> integrationConfigService.findById(targetId)?.userId
        else -> null
    }
}
