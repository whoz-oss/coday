package io.whozoss.agentos.service.config

import mu.KLogging
import org.pf4j.PluginManager
import org.pf4j.spring.SpringPluginManager
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.nio.file.Files
import java.nio.file.Paths

/**
 * Configuration for the PF4J plugin system
 */
@Configuration
class PluginConfiguration {
    @Bean
    fun pluginManager(
        pluginsConfigProperties: AgentOsPluginsConfigProperties,
        applicationContext: ApplicationContext,
    ): PluginManager {
        val pluginPath = Paths.get(pluginsConfigProperties.dir)

        // Create plugins directory if it doesn't exist
        if (!Files.exists(pluginPath)) {
            Files.createDirectories(pluginPath)
        }
        logger.info { "Plugin path: $pluginPath" }
        return SpringPluginManager(pluginPath).also { pluginManager -> pluginManager.applicationContext = applicationContext }
    }

    companion object : KLogging() {}
}
