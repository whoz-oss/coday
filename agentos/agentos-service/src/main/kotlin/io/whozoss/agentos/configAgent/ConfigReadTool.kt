package io.whozoss.agentos.configAgent

import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolExecutionResult
import java.util.UUID

/**
 * `AGENTOS_CONFIG__read`: what the user can see of the configuration, secrets masked.
 *
 * Bound at grant time to the user the agent runs for ([callerId]) and to the run's namespace: the
 * LLM cannot widen either. A run without a user gets an error rather than an anonymous read.
 */
class ConfigReadTool(
    private val callerId: UUID?,
    private val namespaceId: UUID,
    private val configReadService: ConfigReadService,
    private val objectMapper: ObjectMapper,
) : StandardTool<ConfigReadTool.Input> {
    data class Input(
        val kind: String? = null,
        val id: String? = null,
        val scope: String? = null,
    )

    override val name: String = ConfigAgentIntegration.READ_TOOL_NAME
    override val version: String = "1.0.0"
    override val paramType: Class<Input> = Input::class.java

    override val description: String =
        """
        Read the AgentOS configuration the user can see, to prepare a change with ${ConfigAgentIntegration.PROPOSE_TOOL_NAME}.
        Without 'id', lists the entities of a kind (id, name, scope). With 'id', returns one entity in full.
        Secrets are always masked as "****".
        Kinds: agent, skill, prompt, integration, auth-setting, integration-type (the available types and,
        with id = the type, its parameter schema), deployment (which agents are deployed on this namespace),
        capabilities (who the user is: super-admin, role in the namespace).
        Start with 'capabilities' and 'integration-type' before proposing a new integration.
        """.trimIndent()

    override val inputSchema: String =
        """
        {
          "type": "object",
          "properties": {
            "kind": {
              "type": "string",
              "enum": ["agent", "skill", "prompt", "integration", "auth-setting", "integration-type", "deployment", "capabilities"]
            },
            "id": {
              "type": "string",
              "description": "Id of one entity to read in full (the type name for integration-type). Omit to list."
            },
            "scope": {
              "type": "string",
              "enum": ["all", "platform", "namespace", "mine"],
              "description": "Layers to list: platform, this namespace, the user's own entities. Default: all."
            }
          },
          "required": ["kind"]
        }
        """.trimIndent()

    override suspend fun execute(
        input: Input?,
        context: ToolContext,
    ): ToolExecutionResult {
        val userId = callerId ?: return ToolExecutionResult.error("Reading the configuration needs a user: this run has none.", errorType = "NO_USER")
        val kind = input?.kind?.trim()?.lowercase()
        val scope =
            input?.scope?.trim()?.uppercase()?.let { raw -> ReadScope.entries.firstOrNull { it.name == raw } }
                ?: ReadScope.ALL
        val id = input?.id?.trim()?.takeIf { it.isNotEmpty() }
        val result: Any =
            when (kind) {
                "capabilities" -> configReadService.capabilities(userId, namespaceId)
                "integration-type" -> integrationTypes(id) ?: return notFound("integration type '$id'")
                "deployment" -> deployment(userId)
                else -> {
                    val configKind =
                        kindOf(kind) ?: return ToolExecutionResult.error(
                            "Unknown kind '${input?.kind}'. Use one of: agent, skill, prompt, integration, auth-setting, integration-type, deployment, capabilities.",
                            errorType = "INVALID_INPUT",
                        )
                    if (id == null) {
                        configReadService.list(configKind, scope, userId, namespaceId).map { summary(it) }
                    } else {
                        val uuid = runCatching { UUID.fromString(id) }.getOrNull() ?: return notFound("$kind '$id'")
                        val entity = configReadService.find(configKind, uuid, userId, namespaceId) ?: return notFound("$kind '$id'")
                        summary(entity) + mapOf("value" to configReadService.masked(entity))
                    }
                }
            }
        return ToolExecutionResult.success(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result))
    }

    private fun integrationTypes(type: String?): Any? =
        if (type == null) {
            configReadService.integrationTypes().map {
                mapOf("type" to it.type, "displayName" to it.displayName, "description" to it.description, "builtIn" to it.builtIn)
            }
        } else {
            configReadService.integrationType(type)
        }

    private fun deployment(userId: UUID): List<Map<String, Any?>> {
        val deployed = configReadService.deployedAgentIds(namespaceId)
        return configReadService.list(ConfigKind.AGENT, ReadScope.ALL, userId, namespaceId).map {
            mapOf("id" to it.id, "name" to it.name, "enabled" to it.enabled, "deployedOnNamespace" to (it.id in deployed))
        }
    }

    private fun summary(entity: ConfigEntity): Map<String, Any?> =
        buildMap {
            put("id", entity.id)
            put("name", entity.name)
            put("scope", mapOf("namespaceId" to entity.namespaceId, "userId" to entity.userId))
            entity.enabled?.let { put("enabled", it) }
            entity.integrationType?.let { put("integrationType", it) }
            if (entity.fromFilesystem) put("origin", "FILESYSTEM (read-only)")
        }

    private fun notFound(what: String) = ToolExecutionResult.error("No $what visible to the user.", errorType = "NOT_FOUND")

    companion object {
        fun kindOf(raw: String?): ConfigKind? =
            when (raw) {
                "agent" -> ConfigKind.AGENT
                "skill" -> ConfigKind.SKILL
                "prompt" -> ConfigKind.PROMPT
                "integration" -> ConfigKind.INTEGRATION
                "auth-setting", "auth_setting", "authsetting" -> ConfigKind.AUTH_SETTING
                else -> null
            }
    }
}
