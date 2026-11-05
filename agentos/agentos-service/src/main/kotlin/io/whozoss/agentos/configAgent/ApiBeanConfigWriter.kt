package io.whozoss.agentos.configAgent

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import io.whozoss.agentos.authSetting.AuthSettingApi
import io.whozoss.agentos.authSetting.AuthSettingDto
import io.whozoss.agentos.exception.BadRequestException
import io.whozoss.agentos.sdk.api.agentConfig.AgentConfigApi
import io.whozoss.agentos.sdk.api.agentConfig.AgentConfigDto
import io.whozoss.agentos.sdk.api.configChange.ConfigKind
import io.whozoss.agentos.sdk.api.integrationConfig.IntegrationConfigApi
import io.whozoss.agentos.sdk.api.integrationConfig.IntegrationConfigDto
import io.whozoss.agentos.sdk.api.namespace.NamespaceAgentDeployRequest
import io.whozoss.agentos.sdk.api.namespace.NamespaceApi
import io.whozoss.agentos.sdk.api.namespace.NamespaceDto
import io.whozoss.agentos.sdk.api.prompt.PromptApi
import io.whozoss.agentos.sdk.api.prompt.PromptDto
import io.whozoss.agentos.sdk.api.skill.SkillApi
import io.whozoss.agentos.sdk.api.skill.SkillDto
import io.whozoss.agentos.security.declarative.AgentOsAuthentication
import jakarta.validation.Validator
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Phase 1 [ConfigWriter]: calls the REST controllers in process, through their public `*Api`
 * interfaces, whose only implementation is the controller's method-security proxy.
 *
 * Must run with the user who applies authenticated on the thread ([AgentOsAuthentication]): the user
 * of the HTTP request, or the user a messaging chat acts for. `@PreAuthorize` and the inline guards
 * (`requireSuperAdmin`, scope policies, through `UserService.getCurrentUser`) all read that identity, so
 * every write is authorized exactly like the same call made by the browser. No access rule is copied
 * here. `ApiBeanBridgeAuthorizationSpec` proves the bridge. Every method checks that a user is
 * authenticated first, so an asynchronous caller fails with a clear message rather than with an
 * authentication error from the proxy.
 *
 * `@Valid` does not apply to in-process calls, so every DTO is validated here with the same Bean
 * Validation constraints before it reaches the controller, and refused as a 400 would be.
 */
@Component
class ApiBeanConfigWriter(
    private val agentConfigApi: AgentConfigApi,
    private val skillApi: SkillApi,
    private val promptApi: PromptApi,
    private val integrationConfigApi: IntegrationConfigApi,
    private val authSettingApi: AuthSettingApi,
    private val namespaceApi: NamespaceApi,
    private val objectMapper: ObjectMapper,
    private val validator: Validator,
) : ConfigWriter {
    override fun create(
        kind: ConfigKind,
        dto: ObjectNode,
    ): UUID =
        asAuthenticatedUser {
            // Every DTO's id is nullable, so the check covers all the branches.
            when (kind) {
                ConfigKind.AGENT -> agentConfigApi.create(validated(dto, AgentConfigDto::class.java)).id
                ConfigKind.SKILL -> skillApi.create(validated(dto, SkillDto::class.java)).id
                ConfigKind.PROMPT -> promptApi.create(validated(dto, PromptDto::class.java)).id
                ConfigKind.INTEGRATION -> integrationConfigApi.create(validated(dto, IntegrationConfigDto::class.java)).id
                ConfigKind.AUTH_SETTING -> authSettingApi.create(validated(dto, AuthSettingDto::class.java)).id
                ConfigKind.NAMESPACE -> namespaceApi.create(validated(dto, NamespaceDto::class.java)).id
            } ?: throw IllegalStateException("The REST API created a $kind without returning its id")
        }

    override fun read(
        kind: ConfigKind,
        id: UUID,
    ): ObjectNode =
        asAuthenticatedUser {
            objectMapper.valueToTree(
                when (kind) {
                    ConfigKind.AGENT -> agentConfigApi.getById(id)
                    ConfigKind.SKILL -> skillApi.getById(id)
                    ConfigKind.PROMPT -> promptApi.getById(id)
                    ConfigKind.INTEGRATION -> integrationConfigApi.getById(id)
                    ConfigKind.AUTH_SETTING -> authSettingApi.getById(id)
                    ConfigKind.NAMESPACE -> namespaceApi.getById(id)
                },
            )
        }

    override fun update(
        kind: ConfigKind,
        id: UUID,
        change: (ObjectNode) -> Unit,
    ): Unit = asAuthenticatedUser {
        val current = read(kind, id).also(change)
        when (kind) {
            ConfigKind.AGENT -> agentConfigApi.update(id, validated(current, AgentConfigDto::class.java))
            ConfigKind.SKILL -> skillApi.update(id, validated(current, SkillDto::class.java))
            ConfigKind.PROMPT -> promptApi.update(id, validated(current, PromptDto::class.java))
            ConfigKind.INTEGRATION -> integrationConfigApi.update(id, validated(current, IntegrationConfigDto::class.java))
            ConfigKind.AUTH_SETTING -> authSettingApi.update(id, validated(current, AuthSettingDto::class.java))
            ConfigKind.NAMESPACE -> namespaceApi.update(id, validated(current, NamespaceDto::class.java))
        }
    }

    override fun setEnabled(
        agentId: UUID,
        enabled: Boolean,
    ): Unit =
        asAuthenticatedUser {
            if (enabled) agentConfigApi.enable(agentId) else agentConfigApi.disable(agentId)
        }

    override fun setDeployed(
        namespaceId: UUID,
        agentId: UUID,
        deployed: Boolean,
    ): Unit = asAuthenticatedUser {
        val request = NamespaceAgentDeployRequest(listOf(agentId))
        if (deployed) namespaceApi.deployAgents(namespaceId, request) else namespaceApi.undeployAgents(namespaceId, request)
    }

    /** Runs [block] once sure a user is authenticated on the thread: the identity the proxies read. */
    private fun <T> asAuthenticatedUser(block: () -> T): T {
        check(SecurityContextHolder.getContext().authentication is AgentOsAuthentication) { NO_AUTHENTICATED_USER }
        return block()
    }

    private fun <T : Any> validated(
        dto: ObjectNode,
        type: Class<T>,
    ): T =
        objectMapper.treeToValue(dto, type).also { value ->
            val violations = validator.validate(value)
            if (violations.isNotEmpty()) {
                throw BadRequestException(violations.sortedBy { it.propertyPath.toString() }.joinToString("; ") { "${it.propertyPath}: ${it.message}" })
            }
        }

    private companion object {
        const val NO_AUTHENTICATED_USER = "ConfigWriter must run with the user who applies the plan authenticated"
    }
}
