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
import io.whozoss.agentos.sdk.api.common.EntityCrudApi
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
            ConfigKind.AGENT -> agentConfigApi.create(read(dto, AgentConfigDto::class.java)).id
            ConfigKind.SKILL -> skillApi.create(read(dto, SkillDto::class.java)).id
            ConfigKind.PROMPT -> promptApi.create(read(dto, PromptDto::class.java)).id
            ConfigKind.INTEGRATION -> integrationConfigApi.create(read(dto, IntegrationConfigDto::class.java)).id
            ConfigKind.AUTH_SETTING -> authSettingApi.create(read(dto, AuthSettingDto::class.java)).id
            ConfigKind.NAMESPACE -> namespaceApi.create(read(dto, NamespaceDto::class.java)).id
        } ?: throw IllegalStateException("The REST API created a $kind without returning its id")

    override fun update(
        kind: ConfigKind,
        id: UUID,
        change: (ObjectNode) -> Unit,
    ) {
        when (kind) {
            ConfigKind.AGENT -> update(agentConfigApi, id, AgentConfigDto::class.java, change)
            ConfigKind.SKILL -> update(skillApi, id, SkillDto::class.java, change)
            ConfigKind.PROMPT -> update(promptApi, id, PromptDto::class.java, change)
            ConfigKind.INTEGRATION -> update(integrationConfigApi, id, IntegrationConfigDto::class.java, change)
            ConfigKind.AUTH_SETTING -> update(authSettingApi, id, AuthSettingDto::class.java, change)
            ConfigKind.NAMESPACE -> update(namespaceApi, id, NamespaceDto::class.java, change)
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

    private fun <T : Any> update(
        api: EntityCrudApi<T>,
        id: UUID,
        type: Class<T>,
        change: (ObjectNode) -> Unit,
    ) {
        val current = objectMapper.valueToTree<ObjectNode>(api.getById(id))
        change(current)
        api.update(id, read(current, type))
    }

    private fun <T : Any> read(
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
