package io.whozoss.agentos.factory

import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import org.springframework.stereotype.Service

/** Explicit-only grant: absence or an empty allowlist grants nothing. */
@Service
class FactoryToolGrantService(private val plugin: FactoryToolPlugin) {
    fun isGranted(integrations: Map<String, List<String>?>?): Boolean = grantedSuffixes(integrations).isNotEmpty()

    fun grantTools(
        context: ToolContext,
        integrations: Map<String, List<String>?>? = mapOf(FactoryToolPlugin.INTEGRATION_TYPE to listOf("publish_projection")),
    ): List<StandardTool<*>> {
        val suffixes = grantedSuffixes(integrations)
        return plugin.provideTools(null, null, context).filter { tool -> suffixes.contains(tool.name.removePrefix("FACTORY__")) }
    }

    private fun grantedSuffixes(integrations: Map<String, List<String>?>?): Set<String> {
        if (integrations?.containsKey(FactoryToolPlugin.INTEGRATION_TYPE) != true) return emptySet()
        return integrations[FactoryToolPlugin.INTEGRATION_TYPE].orEmpty().mapNotNull {
            when (it) {
                "get_workflow", "FACTORY__get_workflow" -> "get_workflow"
                "provision_environment", "FACTORY__provision_environment" -> "provision_environment"
                "start_workflow", "FACTORY__start_workflow" -> "start_workflow"
                "publish_projection", "FACTORY__publish_projection" -> "publish_projection"
                "record_agent_result", "FACTORY__record_agent_result" -> "record_agent_result"
                "record_artifact", "FACTORY__record_artifact" -> "record_artifact"
                "request_human_decision", "FACTORY__request_human_decision" -> "request_human_decision"
                "request_transition", "FACTORY__request_transition" -> "request_transition"
                "transition_workflow", "FACTORY__transition_workflow" -> "transition_workflow"
                else -> null
            }
        }.toSet()
    }
}
