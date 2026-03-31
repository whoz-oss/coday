package io.whozoss.agentos.plugins.file

import mu.KLogging
import org.pf4j.Plugin
import org.pf4j.PluginWrapper

/**
 * Plugin that provides file system operation tools.
 *
 * This plugin implements the file tools from Coday TypeScript in functional parity for AgentOS V1:
 * - ListFiles (FILES__ls)
 * - ReadFile (FILES__readFile)
 * - SearchFiles (FILES__searchFiles)
 * - EditFiles (FILES__editFiles)
 * - RemoveFile (FILES__remove)
 * - MoveFile (FILES__moveFile)
 *
 * Security features:
 * - Boundary path resolution with segment-by-segment traversal
 * - Symlink validation at each step (TOCTOU-safe)
 * - Deny-list for sensitive files (.env, *.key, credentials.json, etc.)
 * - Atomic writes with cleanup (no orphan .tmp files)
 * - Ripgrep sanitization (injection-safe)
 * - Read-only mode support via ToolExecutionContext
 */
class FilePlugin(wrapper: PluginWrapper) : Plugin(wrapper) {
    override fun start() {
        logger.info { "File Plugin started!" }
    }

    override fun stop() {
        logger.info { "File Plugin stopped!" }
    }

    companion object : KLogging()
}
