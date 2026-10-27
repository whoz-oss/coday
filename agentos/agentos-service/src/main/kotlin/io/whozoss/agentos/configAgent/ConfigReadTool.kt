package io.whozoss.agentos.configAgent

import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolExecutionResult
import java.util.UUID

/**
 * `AGENTOS_CONFIG__read`: what the user can see of the configuration, secrets masked.
 *
 * Bound at grant time to the user the agent runs for ([callerId]) and to the run's namespace. The LLM
 * may point a read at another namespace only when that user administers it. A run without a user gets
 * an error rather than an anonymous read.
 */
class ConfigReadTool(
    private val callerId: UUID?,
    private val namespaceId: UUID,
    private val configReadService: ConfigReadService,
    private val configNamespaceDiagnosis: ConfigNamespaceDiagnosis,
    private val objectMapper: ObjectMapper,
) : StandardTool<ConfigReadTool.Input> {
    data class Input(
        val kind: String? = null,
        val id: String? = null,
        val scope: String? = null,
        val namespaceId: String? = null,
    )

    override val name: String = ConfigAgentIntegration.READ_TOOL_NAME
    override val version: String = "1.0.0"
    override val paramType: Class<Input> = Input::class.java

    override val description: String =
        """
        Read the AgentOS configuration the user can see, to prepare a change with ${ConfigAgentIntegration.PROPOSE_TOOL_NAME}
        or to diagnose a namespace. Without 'id', lists the entities of a kind (id, name, scope). With 'id', returns
        one entity in full. Secrets are always masked as "****".
        Kinds: agent, skill, prompt, integration, auth-setting, namespace (with 'id': its settings and its readiness,
        i.e. whether a message without @mention reaches an agent and, if not, why: no default agent, agent missing,
        disabled or not deployed), integration-type (the available types and, with id = the type, its parameter
        schema), deployment (which agents are deployed on the namespace), capabilities (who the user is: super-admin,
        role in the namespace).
        'namespaceId' reads another namespace the user administers instead of this one (list the namespaces first).
        Start with 'capabilities' and 'integration-type' before proposing a new integration. When a namespace does
        not answer, read it with kind 'namespace' before anything else.
        """.trimIndent()

    override val inputSchema: String =
        """
        {
          "type": "object",
          "properties": {
            "kind": {
              "type": "string",
              "enum": [${ReadKind.wireNames}]
            },
            "id": {
              "type": "string",
              "description": "Id of one entity to read in full (the type name for integration-type). Omit to list."
            },
            "scope": {
              "type": "string",
              "enum": ["all", "platform", "namespace", "mine"],
              "description": "Layers to list: platform, this namespace, the user's own entities. Default: all."
            },
            "namespaceId": {
              "type": "string",
              "description": "\"current\" (default) or the id of a namespace the user administers: the namespace whose layer, deployment or capabilities are read."
            }
          },
          "required": ["kind"]
        }
        """.trimIndent()

    override suspend fun execute(
        input: Input?,
        context: ToolContext,
    ): ToolExecutionResult {
        val kind = ReadKind.fromWire(input?.kind)
        val target = callerId?.let { targetNamespace(input?.namespaceId, it) }
        return when {
            callerId == null -> ToolExecutionResult.error("Reading the configuration needs a user: this run has none.", errorType = "NO_USER")
            kind == null ->
                ToolExecutionResult.error("Unknown kind '${input?.kind}'. Use one of: ${ReadKind.wireNames}.", errorType = "INVALID_INPUT")
            target == null ->
                ToolExecutionResult.error(
                    "namespaceId '${input?.namespaceId}' is not a namespace the user administers: use \"current\" or the id of one listed by kind 'namespace'.",
                    errorType = "INVALID_INPUT",
                )
            else ->
                read(kind, input?.id?.trim()?.takeIf { it.isNotEmpty() }, scopeOf(input?.scope), callerId, target)
                    ?.let { ToolExecutionResult.success(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(it)) }
                    ?: ToolExecutionResult.error("No ${kind.wireName} '${input?.id}' visible to the user.", errorType = "NOT_FOUND")
        }
    }

    /** The namespace a read is about: this one, or one [userId] administers; null when refused. */
    private fun targetNamespace(
        raw: String?,
        userId: UUID,
    ): UUID? {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() && !it.equals(ScopeInput.CURRENT_NAMESPACE, ignoreCase = true) }
        return when (val id = value?.let { runCatching { UUID.fromString(it) }.getOrNull() }) {
            null -> namespaceId.takeIf { value == null }
            namespaceId -> namespaceId
            else -> id.takeIf { configReadService.canAdminister(userId, it) }
        }
    }

    /** The value to return, or null when the requested entity is not visible to the user. */
    private fun read(
        kind: ReadKind,
        id: String?,
        scope: ReadScope,
        userId: UUID,
        target: UUID,
    ): Any? =
        when (kind) {
            ReadKind.CAPABILITIES -> configNamespaceDiagnosis.capabilities(userId, target)
            ReadKind.INTEGRATION_TYPE -> integrationTypes(id)
            ReadKind.DEPLOYMENT -> deployment(userId, target)
            else -> entities(kind.configKind!!, id, scope, userId, target)
        }

    private fun entities(
        kind: ConfigKind,
        id: String?,
        scope: ReadScope,
        userId: UUID,
        target: UUID,
    ): Any? =
        if (id == null) {
            configReadService.list(kind, scope, userId, target).map { summary(it, userId) }
        } else {
            runCatching { UUID.fromString(id) }
                .getOrNull()
                ?.let { configReadService.find(kind, it, userId, target) }
                ?.let { entity -> summary(entity, userId) + mapOf("value" to configReadService.masked(entity)) + readinessOf(entity) }
        }

    /** A namespace read by id also says whether a message reaches an agent there. */
    private fun readinessOf(entity: ConfigEntity): Map<String, Any?> =
        if (entity.kind == ConfigKind.NAMESPACE) mapOf("readiness" to configNamespaceDiagnosis.readiness(entity.id)) else emptyMap()

    private fun integrationTypes(type: String?): Any? =
        if (type == null) {
            configReadService.integrationTypes().map {
                mapOf("type" to it.type, "displayName" to it.displayName, "description" to it.description, "builtIn" to it.builtIn)
            }
        } else {
            configReadService.integrationType(type)
        }

    private fun deployment(
        userId: UUID,
        target: UUID,
    ): List<Map<String, Any?>> {
        val deployed = configReadService.deployedAgentIds(target)
        return configReadService.list(ConfigKind.AGENT, ReadScope.ALL, userId, target).map {
            mapOf("id" to it.id, "name" to it.name, "enabled" to it.enabled, "deployedOnNamespace" to (it.id in deployed))
        }
    }

    private fun summary(
        entity: ConfigEntity,
        userId: UUID,
    ): Map<String, Any?> =
        buildMap {
            put("id", entity.id)
            put("name", entity.name)
            put("scope", mapOf("namespaceId" to entity.namespaceId, "userId" to entity.userId))
            if (entity.kind == ConfigKind.NAMESPACE) put("administered", configReadService.canAdminister(userId, entity.id))
            entity.enabled?.let { put("enabled", it) }
            entity.integrationType?.let { put("integrationType", it) }
            if (entity.fromFilesystem) put("origin", "FILESYSTEM (read-only)")
        }

    private fun scopeOf(raw: String?): ReadScope = raw?.trim()?.uppercase()?.let { value -> ReadScope.entries.firstOrNull { it.name == value } } ?: ReadScope.ALL

}
