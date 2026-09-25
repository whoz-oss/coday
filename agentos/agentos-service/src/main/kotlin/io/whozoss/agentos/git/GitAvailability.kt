package io.whozoss.agentos.git

import io.whozoss.agentos.tool.ToolRegistryService
import org.springframework.stereotype.Component

/**
 * Whether this instance offers Git: a tool plugin of type [TOOLS_INTEGRATION_TYPE] is loaded.
 *
 * That plugin gives agents their Git tools, and its presence also enables the namespace
 * association. Without it the namespace Git settings are hidden and refused, and no new case
 * family is equipped. Workspaces created earlier keep working and are still cleaned up.
 */
@Component
class GitAvailability(
    private val toolRegistryService: ToolRegistryService,
) {
    fun isAvailable(): Boolean = toolRegistryService.findPlugin(TOOLS_INTEGRATION_TYPE) != null

    companion object {
        /** Integration type of the PF4J plugin providing the agent Git tools. */
        const val TOOLS_INTEGRATION_TYPE: String = "GIT"
    }
}
