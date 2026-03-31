package io.whozoss.agentos.plugins.file

import io.whozoss.agentos.plugins.file.tools.EditFilesTool
import io.whozoss.agentos.plugins.file.tools.ListFilesTool
import io.whozoss.agentos.plugins.file.tools.MoveFileTool
import io.whozoss.agentos.plugins.file.tools.ReadFileTool
import io.whozoss.agentos.plugins.file.tools.RemoveFileTool
import io.whozoss.agentos.plugins.file.tools.SearchFilesTool
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolPlugin
import org.pf4j.Extension

/**
 * Extension that contributes file tools to the registry.
 *
 * This provider is discovered by PF4J via @Extension annotation and automatically
 * registers all 6 file tools when the plugin loads.
 *
 * Tool names follow Coday TypeScript convention: FILES__<operation>
 */
@Extension
class FileToolProvider : ToolPlugin {
    /**
     * Returns the list of file operation tools provided by this plugin.
     *
     * @return List of 6 file tools: list, read, search, edit, remove, move
     */
    override fun provideTools(): List<StandardTool<*>> =
        listOf(
            ListFilesTool(),
            ReadFileTool(),
            SearchFilesTool(),
            EditFilesTool(),
            RemoveFileTool(),
            MoveFileTool(),
        )
}
