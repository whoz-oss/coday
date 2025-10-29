package io.biznet.agentos.plugins.api

import io.biznet.agentos.agents.service.AgentRegistry
import io.biznet.agentos.plugins.PluginInfo
import io.biznet.agentos.plugins.PluginService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

/**
 * REST API controller for plugin management
 */
@RestController
@RequestMapping("/api/plugins")
class PluginController(
    private val pluginService: PluginService,
    private val agentRegistry: AgentRegistry,
) {
    // Optional: inject debug service if available
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private var pluginDebugService: io.biznet.agentos.plugins.PluginDebugService? = null

    /**
     * Get all loaded plugins
     */
    @GetMapping
    fun getAllPlugins(): ResponseEntity<List<PluginInfo>> {
        val plugins = pluginService.getLoadedPlugins()
        return ResponseEntity.ok(plugins)
    }

    /**
     * Get a specific plugin by ID
     */
    @GetMapping("/{pluginId}")
    fun getPlugin(
        @PathVariable pluginId: String,
    ): ResponseEntity<PluginInfo> {
        val plugin = pluginService.getPluginInfo(pluginId)
        return if (plugin != null) {
            ResponseEntity.ok(plugin)
        } else {
            ResponseEntity.notFound().build()
        }
    }

    /**
     * Upload and load a new plugin
     */
    @PostMapping("/upload")
    fun uploadPlugin(
        @RequestParam("file") file: MultipartFile,
    ): ResponseEntity<PluginUploadResponse> {
        if (file.isEmpty) {
            return ResponseEntity
                .badRequest()
                .body(PluginUploadResponse(false, "File is empty", null))
        }

        if (file.originalFilename?.endsWith(".jar") == true) {
            return ResponseEntity
                .badRequest()
                .body(PluginUploadResponse(false, "Only JAR files are allowed", null))
        }

        return try {
            // Save the uploaded file to the plugins directory
            val pluginsDir = Paths.get("plugins")
            Files.createDirectories(pluginsDir)

            val targetPath = pluginsDir.resolve(file.originalFilename!!)
            Files.copy(file.inputStream, targetPath, StandardCopyOption.REPLACE_EXISTING)

            // Load the plugin
            val pluginId = pluginService.loadPlugin(targetPath)

            // Reload agents from plugins
            agentRegistry.loadAgentsFromPlugins()

            ResponseEntity
                .status(HttpStatus.CREATED)
                .body(
                    PluginUploadResponse(
                        success = true,
                        message = "Plugin uploaded and loaded successfully",
                        pluginId = pluginId,
                    ),
                )
        } catch (e: Exception) {
            ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(
                    PluginUploadResponse(
                        success = false,
                        message = "Failed to upload plugin: ${e.message}",
                        pluginId = null,
                    ),
                )
        }
    }

    /**
     * Start a plugin
     */
    @PostMapping("/{pluginId}/start")
    fun startPlugin(
        @PathVariable pluginId: String,
    ): ResponseEntity<PluginActionResponse> {
        val success = pluginService.startPlugin(pluginId)
        return if (success) {
            agentRegistry.loadAgentsFromPlugins()
            ResponseEntity.ok(PluginActionResponse(true, "Plugin started successfully"))
        } else {
            ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(PluginActionResponse(false, "Failed to start plugin"))
        }
    }

    /**
     * Stop a plugin
     */
    @PostMapping("/{pluginId}/stop")
    fun stopPlugin(
        @PathVariable pluginId: String,
    ): ResponseEntity<PluginActionResponse> {
        val success = pluginService.stopPlugin(pluginId)
        return if (success) {
            ResponseEntity.ok(PluginActionResponse(true, "Plugin stopped successfully"))
        } else {
            ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(PluginActionResponse(false, "Failed to stop plugin"))
        }
    }

    /**
     * Reload a plugin
     */
    @PostMapping("/{pluginId}/reload")
    fun reloadPlugin(
        @PathVariable pluginId: String,
    ): ResponseEntity<PluginActionResponse> {
        val success = pluginService.reloadPlugin(pluginId)
        return if (success) {
            agentRegistry.reloadPluginAgents()
            ResponseEntity.ok(PluginActionResponse(true, "Plugin reloaded successfully"))
        } else {
            ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(PluginActionResponse(false, "Failed to reload plugin"))
        }
    }

    /**
     * Unload a plugin
     */
    @DeleteMapping("/{pluginId}")
    fun unloadPlugin(
        @PathVariable pluginId: String,
    ): ResponseEntity<PluginActionResponse> {
        val success = pluginService.unloadPlugin(pluginId)
        return if (success) {
            ResponseEntity.ok(PluginActionResponse(true, "Plugin unloaded successfully"))
        } else {
            ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(PluginActionResponse(false, "Failed to unload plugin"))
        }
    }

    /**
     * Reload all agents from all plugins
     */
    @PostMapping("/reload-agents")
    fun reloadAllAgents(): ResponseEntity<PluginActionResponse> =
        try {
            agentRegistry.reloadPluginAgents()
            ResponseEntity.ok(PluginActionResponse(true, "Agents reloaded successfully"))
        } catch (e: Exception) {
            ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(PluginActionResponse(false, "Failed to reload agents: ${e.message}"))
        }

    /**
     * Debug a specific plugin
     */
    @GetMapping("/{pluginId}/debug")
    fun debugPlugin(
        @PathVariable pluginId: String,
    ): ResponseEntity<PluginActionResponse> {
        if (pluginDebugService == null) {
            return ResponseEntity
                .status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(PluginActionResponse(false, "Debug service not available"))
        }
        return try {
            pluginDebugService!!.debugPlugin(pluginId)
            ResponseEntity.ok(PluginActionResponse(true, "Debug info logged - check application logs"))
        } catch (e: Exception) {
            ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(PluginActionResponse(false, "Debug failed: ${e.message}"))
        }
    }

    /**
     * Debug all plugins
     */
    @GetMapping("/debug")
    fun debugAllPlugins(): ResponseEntity<PluginActionResponse> {
        if (pluginDebugService == null) {
            return ResponseEntity
                .status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(PluginActionResponse(false, "Debug service not available"))
        }
        return try {
            pluginDebugService!!.debugAllPlugins()
            ResponseEntity.ok(PluginActionResponse(true, "Debug info logged - check application logs"))
        } catch (e: Exception) {
            ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(PluginActionResponse(false, "Debug failed: ${e.message}"))
        }
    }
}

/**
 * Response DTO for plugin upload
 */
data class PluginUploadResponse(
    val success: Boolean,
    val message: String,
    val pluginId: String?,
)

/**
 * Response DTO for plugin actions
 */
data class PluginActionResponse(
    val success: Boolean,
    val message: String,
)
