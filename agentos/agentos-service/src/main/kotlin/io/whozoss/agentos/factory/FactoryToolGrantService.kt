package io.whozoss.agentos.factory

import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import org.springframework.stereotype.Service

/** Explicit-only grant: absence or an empty allowlist grants nothing. */
@Service
class FactoryToolGrantService(private val plugin: FactoryToolPlugin) {
    fun isGranted(integrations: Map<String, List<String>?>?): Boolean {
        if (integrations?.containsKey(FactoryToolPlugin.INTEGRATION_TYPE) != true) return false
        val allowed = integrations[FactoryToolPlugin.INTEGRATION_TYPE]
        return allowed == null || allowed.any { it == "publish_projection" || it == "FACTORY__publish_projection" }
    }

    fun grantTools(context: ToolContext): List<StandardTool<*>> = plugin.provideTools(null, null, context)
}
