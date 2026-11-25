package io.whozoss.agentos.integrationConfig

import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import org.springframework.security.access.AccessDeniedException
import java.util.UUID

/**
 * Mechanism of [IntegrationConfigScopePolicy], independent of the default list of denied types.
 * The denial per scope is covered end to end by `IntegrationConfigControllerMvcIntegrationSpec`.
 */
class IntegrationConfigScopePolicyUnitSpec : StringSpec({
    val userId = UUID.randomUUID()
    val policy = IntegrationConfigScopePolicy(IntegrationsProperties(userScopeDeniedTypes = listOf("JIRA")))

    "the match is exact, like the plugin registry lookup" {
        shouldThrow<AccessDeniedException> { policy.requireScopeAllowed(userId = userId, integrationType = "JIRA") }
        shouldNotThrowAny { policy.requireScopeAllowed(userId = userId, integrationType = "jira") }
    }

    "a configured list replaces the default one" {
        val defaultType = IntegrationsProperties().userScopeDeniedTypes.first()
        val defaultPolicy = IntegrationConfigScopePolicy(IntegrationsProperties())

        shouldThrow<AccessDeniedException> { defaultPolicy.requireScopeAllowed(userId = userId, integrationType = defaultType) }
        shouldNotThrowAny { policy.requireScopeAllowed(userId = userId, integrationType = defaultType) }
    }
})
