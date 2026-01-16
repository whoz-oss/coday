package io.whozoss.agentos.service.plugins

import io.whozoss.agentos.sdk.agent.AgentPlugin
import org.pf4j.PluginManager
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.jar.JarFile

/**
 * Debug service to diagnose plugin extension discovery issues
 */
@Service
class PluginDebugService(
    private val pluginManager: PluginManager,
) {
    private val logger = LoggerFactory.getLogger(PluginDebugService::class.java)

    /**
     * Debug extension discovery for a specific plugin
     */
    fun debugPlugin(pluginId: String) {
        logger.info("=== Debugging Plugin: $pluginId ===")

        val plugin = pluginManager.getPlugin(pluginId)
        if (plugin == null) {
            logger.error("Plugin not found: $pluginId")
            return
        }

        logger.info("Plugin path: ${plugin.pluginPath}")
        logger.info("Plugin state: ${plugin.pluginState}")
        logger.info("Plugin classloader: ${plugin.pluginClassLoader.javaClass.name}")

        // Check if extensions.idx exists in the JAR
        try {
            val jarPath = plugin.pluginPath
            logger.info("Checking JAR: $jarPath")

            JarFile(jarPath.toFile()).use { jar ->
                val extensionsEntry = jar.getEntry("META-INF/extensions.idx")
                if (extensionsEntry != null) {
                    logger.info("✓ Found META-INF/extensions.idx")

                    // Read the contents
                    jar.getInputStream(extensionsEntry).use { input ->
                        val content = BufferedReader(InputStreamReader(input)).readText()
                        logger.info("Contents of extensions.idx:")
                        content.lines().forEach { line ->
                            logger.info("  - $line")

                            // Try to load each class
                            try {
                                val clazz = plugin.pluginClassLoader.loadClass(line.trim())
                                logger.info("    ✓ Class loaded: ${clazz.name}")
                                logger.info("    Interfaces: ${clazz.interfaces.joinToString { it.name }}")
                                logger.info("    Annotations: ${clazz.annotations.joinToString { it.annotationClass.simpleName ?: "?" }}")

                                // Check if it implements AgentPlugin
                                val implementsAgentPlugin = clazz.interfaces.any { it.name.contains("AgentPlugin") }
                                logger.info("    Implements AgentPlugin: $implementsAgentPlugin")

                                // Check for @Extension annotation
                                val hasExtension = clazz.annotations.any { it.annotationClass.simpleName == "Extension" }
                                logger.info("    Has @Extension: $hasExtension")
                            } catch (e: ClassNotFoundException) {
                                logger.error("    ✗ Could not load class: ${e.message}")
                            } catch (e: Exception) {
                                logger.error("    ✗ Error loading class: ${e.message}")
                            }
                        }
                    }
                } else {
                    logger.error("✗ META-INF/extensions.idx NOT FOUND in JAR")
                    logger.info("Listing META-INF contents:")
                    jar
                        .entries()
                        .asSequence()
                        .filter { it.name.startsWith("META-INF") }
                        .forEach { logger.info("  - ${it.name}") }
                }
            }
        } catch (e: Exception) {
            logger.error("Error reading JAR: ${e.message}", e)
        }

        // Check what PF4J sees
        logger.info("PluginManager type: ${pluginManager.javaClass.name}")

        // Try to get extensions
        val extensions = pluginManager.getExtensions(AgentPlugin::class.java, pluginId)
        logger.info("Extensions found by PF4J: ${extensions.size}")

        logger.info("=== End Debug ===")
    }

    /**
     * Debug all loaded plugins
     */
    fun debugAllPlugins() {
        pluginManager.plugins.forEach { plugin ->
            debugPlugin(plugin.pluginId)
        }
    }
}
