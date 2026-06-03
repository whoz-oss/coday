package io.whozoss.agentos.casePlugin

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.whozoss.agentos.sdk.caseEvent.CaseEvent
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolPlugin
import mu.KLogging
import java.util.UUID

/**
 * Internal Spring-managed [ToolPlugin] that provides the CASE integration.
 *
 * Like [io.whozoss.agentos.redirect.RedirectToolPlugin], this class is NOT annotated
 * with `@Component` — it is instantiated as a `@Bean` in [CasePluginConfiguration]
 * so that the [caseEventsLoader] lambda can be wired without a circular Spring
 * dependency. [io.whozoss.agentos.tool.ToolRegistryService] collects it alongside
 * PF4J-loaded plugins via `List<ToolPlugin>` constructor injection.
 *
 * An admin creates an [io.whozoss.agentos.integrationConfig.IntegrationConfig] of
 * type `CASE` and assigns it to the relevant
 * [io.whozoss.agentos.agentConfig.AgentConfig] via `integrations`. The tool is
 * then available to that agent for reading other cases in the same namespace.
 *
 * @param caseEventsLoader Lambda injected by [CasePluginConfiguration] that loads
 *   the events of a target case. Returns null when the case does not exist or when
 *   its namespace does not match [namespaceId], enforcing namespace isolation.
 */
class CaseToolPlugin(
    private val caseEventsLoader: (caseId: UUID, namespaceId: UUID) -> List<CaseEvent>?,
) : ToolPlugin {

    override val integrationType: String = INTEGRATION_TYPE
    override val configSchema: JsonNode = CONFIG_SCHEMA

    override fun provideTools(
        config: JsonNode?,
        configName: String?,
        context: ToolContext?,
    ): List<StandardTool<*>> {
        val namespaceId = context?.namespaceId
        if (namespaceId == null) {
            logger.warn { "[CaseToolPlugin] No namespaceId in context, cannot provide ReadCaseTool" }
            return emptyList()
        }

        val includesTechnicalEvents = config
            ?.get("includesTechnicalEvents")
            ?.asBoolean(true)
            ?: true

        return listOf(ReadCaseTool(configName, includesTechnicalEvents, caseEventsLoader))
    }

    companion object : KLogging() {
        const val INTEGRATION_TYPE = "CASE"

        val CONFIG_SCHEMA: JsonNode = jacksonObjectMapper().readTree(
            """
            {
                "type": "object",
                "title": "Case Plugin Configuration",
                "description": "Allows an agent to read the transcript of another case in the same namespace.",
                "properties": {
                    "includesTechnicalEvents": {
                        "type": "boolean",
                        "title": "Include Technical Events",
                        "description": "When true (default), the transcript includes all event types (tool calls, agent selections, status changes, etc.). When false, only conversational events are included (messages, questions, answers, warnings, errors).",
                        "default": true
                    }
                },
                "additionalProperties": false
            }
            """.trimIndent()
        )
    }
}
