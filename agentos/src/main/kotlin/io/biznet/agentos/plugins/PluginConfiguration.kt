package io.biznet.agentos.plugins

import org.pf4j.DefaultPluginManager
import org.pf4j.PluginManager
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Configuration for the PF4J plugin system
 */
@Configuration
class PluginConfiguration {
    private val logger = LoggerFactory.getLogger(PluginConfiguration::class.java)

    @Value("\${agentos.plugins.directory:plugins}")
    private lateinit var pluginsDirectory: String

    @Value("\${agentos.plugins.autoLoad:true}")
    private var autoLoad: Boolean = true

    @Bean
    fun pluginManager(): PluginManager {
        val pluginsPath = Paths.get(pluginsDirectory).toAbsolutePath()

        logger.info("Working directory: ${System.getProperty("user.dir")}")
        logger.info("Plugins directory (configured): $pluginsDirectory")
        logger.info("Plugins path (absolute): $pluginsPath")
        logger.info("Plugins directory exists: ${Files.exists(pluginsPath)}")

        // Create plugins directory if it doesn't exist
        if (!Files.exists(pluginsPath)) {
            logger.info("Creating plugins directory: $pluginsPath")
            Files.createDirectories(pluginsPath)
        }

        // List files in plugins directory
        if (Files.exists(pluginsPath)) {
            val files = Files.list(pluginsPath).toList()
            logger.info("Files in plugins directory: ${files.size}")
            files.forEach { file ->
                logger.info("  - ${file.fileName} (${Files.size(file)} bytes)")
            }
        }

        logger.info("Initializing plugin manager with directory: $pluginsPath")

        // Use JarPluginManager which expects JAR files directly in plugins directory
        val pluginManager = DefaultPluginManager(pluginsPath)

        // Extension finder is protected, can't access directly

        // Debug: Try to find extensions.idx files
        if (Files.exists(pluginsPath)) {
            Files.walk(pluginsPath, 3).use { paths ->
                paths
                    .filter { it.toString().contains("extensions.idx") }
                    .forEach { path ->
                        logger.info("Found extensions.idx at: $path")
                        try {
                            val content = Files.readString(path)
                            logger.info("  Content: $content")
                        } catch (e: Exception) {
                            logger.warn("  Could not read file: ${e.message}")
                        }
                    }
            }
        }

        if (autoLoad) {
            logger.info("Auto-loading plugins...")
            pluginManager.loadPlugins()
            pluginManager.startPlugins()

            val loadedPlugins = pluginManager.plugins
            logger.info("Loaded ${loadedPlugins.size} plugin(s)")
            loadedPlugins.forEach { plugin ->
                logger.info("  - ${plugin.pluginId} v${plugin.descriptor.version} (${plugin.pluginState})")
            }
        }

        return pluginManager
    }
}
