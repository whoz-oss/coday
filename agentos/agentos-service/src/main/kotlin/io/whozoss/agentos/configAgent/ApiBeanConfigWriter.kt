package io.whozoss.agentos.configAgent

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import io.whozoss.agentos.authSetting.AuthSettingApi
import io.whozoss.agentos.authSetting.AuthSettingDto
import io.whozoss.agentos.exception.BadRequestException
import io.whozoss.agentos.namespace.NamespaceAgentDeployRequest
import io.whozoss.agentos.namespace.NamespaceController
import io.whozoss.agentos.sdk.api.agentConfig.AgentConfigApi
import io.whozoss.agentos.sdk.api.agentConfig.AgentConfigDto
import io.whozoss.agentos.sdk.api.integrationConfig.IntegrationConfigApi
import io.whozoss.agentos.sdk.api.integrationConfig.IntegrationConfigDto
import io.whozoss.agentos.sdk.api.namespace.NamespaceApi
import io.whozoss.agentos.sdk.api.namespace.NamespaceDto
import io.whozoss.agentos.sdk.api.prompt.PromptApi
import io.whozoss.agentos.sdk.api.prompt.PromptDto
import io.whozoss.agentos.sdk.api.skill.SkillApi
import io.whozoss.agentos.sdk.api.skill.SkillDto
import jakarta.validation.Validator
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Phase 1 [ConfigWriter]: calls the REST controllers in process, through their public `*Api`
 * interfaces, whose only implementation is the controller's method-security proxy.
 *
 * Must run inside the HTTP request of the user who applies: `@PreAuthorize` and the inline guards
 * (`requireSuperAdmin`, scope policies) read the identity of that request, so every write is
 * authorized exactly like the same call made by the browser. No access rule is copied here.
 * `ApiBeanBridgeAuthorizationSpec` proves the bridge; outside a request the calls are refused.
 *
 * Deployment has no `*Api` method yet, so it goes through [NamespaceController], the same proxy.
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
    private val namespaceController: NamespaceController,
    private val objectMapper: ObjectMapper,
    private val validator: Validator,
) : ConfigWriter {
    override fun create(
        kind: ConfigKind,
        dto: ObjectNode,
    ): UUID =
        when (kind) {
            ConfigKind.AGENT -> agentConfigApi.create(validated(dto, AgentConfigDto::class.java)).id
            ConfigKind.SKILL -> skillApi.create(validated(dto, SkillDto::class.java)).id
            ConfigKind.PROMPT -> promptApi.create(validated(dto, PromptDto::class.java)).id
            ConfigKind.INTEGRATION -> integrationConfigApi.create(validated(dto, IntegrationConfigDto::class.java)).id
            ConfigKind.AUTH_SETTING -> authSettingApi.create(validated(dto, AuthSettingDto::class.java)).id
            ConfigKind.NAMESPACE -> namespaceApi.create(validated(dto, NamespaceDto::class.java)).id
        } ?: throw IllegalStateException("The REST API created a $kind without returning its id")

    override fun read(
        kind: ConfigKind,
        id: UUID,
    ): ObjectNode =
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

    override fun update(
        kind: ConfigKind,
        id: UUID,
        change: (ObjectNode) -> Unit,
    ) {
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
    ) {
        if (enabled) agentConfigApi.enable(agentId) else agentConfigApi.disable(agentId)
    }

    override fun setDeployed(
        namespaceId: UUID,
        agentId: UUID,
        deployed: Boolean,
    ) {
        val request = NamespaceAgentDeployRequest(listOf(agentId))
        if (deployed) namespaceController.deployAgents(namespaceId, request) else namespaceController.undeployAgents(namespaceId, request)
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
}
