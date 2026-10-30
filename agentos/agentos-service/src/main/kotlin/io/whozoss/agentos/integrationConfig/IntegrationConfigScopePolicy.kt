package io.whozoss.agentos.integrationConfig

import org.springframework.security.access.AccessDeniedException
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Enforces [IntegrationsProperties.userScopeDeniedTypes] at the API edge: a user-scoped
 * [IntegrationConfig] (any non-null `userId`, with or without a namespace) of a denied type is
 * refused with an [AccessDeniedException], which the security layer renders as 403 (or 404 on
 * endpoints marked `@HideOnAccessDenied`).
 */
@Component
class IntegrationConfigScopePolicy(
    private val properties: IntegrationsProperties,
) {
    /**
     * Throws [AccessDeniedException] when [userId] is non-null and [integrationType] is denied in
     * user scopes. Shared scopes (`userId == null`) are always allowed.
     */
    fun requireScopeAllowed(
        userId: UUID?,
        integrationType: String,
    ) {
        if (userId == null || integrationType !in properties.userScopeDeniedTypes) return
        throw AccessDeniedException(
            "IntegrationConfig of type '$integrationType' cannot be user-scoped: " +
                "it is listed in agentos.integrations.user-scope-denied-types",
        )
    }
}
