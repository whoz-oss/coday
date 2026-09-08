package io.whozoss.agentos.integrationConfig

import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.string.shouldContain
import org.springframework.security.access.AccessDeniedException
import java.util.UUID

class IntegrationConfigScopePolicyUnitSpec : StringSpec({
    val userId = UUID.randomUUID()
    val defaultPolicy = IntegrationConfigScopePolicy(IntegrationsProperties())

    "the default denied list covers every network-reaching integration type" {
        IntegrationsProperties().userScopeDeniedTypes shouldContainExactly listOf("HTTP_API", "MCP_STDIO", "MCP_HTTP")
    }

    listOf("HTTP_API", "MCP_STDIO", "MCP_HTTP").forEach { type ->
        "a user-scoped $type config is denied with an AccessDeniedException naming the type" {
            val exception =
                shouldThrow<AccessDeniedException> {
                    defaultPolicy.requireScopeAllowed(userId = userId, integrationType = type)
                }
            exception.message shouldContain type
        }
    }

    "a shared (non user-scoped) config of a denied type is allowed" {
        shouldNotThrowAny { defaultPolicy.requireScopeAllowed(userId = null, integrationType = "MCP_HTTP") }
    }

    "a user-scoped config of a type outside the list is allowed" {
        shouldNotThrowAny { defaultPolicy.requireScopeAllowed(userId = userId, integrationType = "JIRA") }
    }

    "the match is exact, like the plugin registry lookup" {
        shouldNotThrowAny { defaultPolicy.requireScopeAllowed(userId = userId, integrationType = "mcp_http") }
    }

    "a configured list replaces the default one" {
        val policy = IntegrationConfigScopePolicy(IntegrationsProperties(userScopeDeniedTypes = listOf("JIRA")))

        shouldThrow<AccessDeniedException> { policy.requireScopeAllowed(userId = userId, integrationType = "JIRA") }
        shouldNotThrowAny { policy.requireScopeAllowed(userId = userId, integrationType = "MCP_HTTP") }
    }
})
