package io.biznet.agentos.plugins

import org.pf4j.PluginManager
import org.pf4j.spring.SpringPluginManager
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.ApplicationContext
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
    fun pluginManager(applicationContext: ApplicationContext): PluginManager {
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

        logger.info("Initializing Spring plugin manager with directory: $pluginsPath")

        // Use SpringPluginManager to enable Spring support in plugins
        // This allows plugins to use @Configuration, @Bean, @Autowired, etc.
        val pluginManager = SpringPluginManager(pluginsPath)
        pluginManager.applicationContext = applicationContext
        
        logger.info("Spring plugin manager initialized with application context")

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

        // Don't call loadPlugins/startPlugins manually!
        // SpringPluginManager has @PostConstruct init() method that will do this
        // and also process Spring configurations in plugins
        
        logger.info("SpringPluginManager will auto-initialize via @PostConstruct")

        return pluginManager
    }
}
