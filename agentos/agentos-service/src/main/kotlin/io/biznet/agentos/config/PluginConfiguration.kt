package io.biznet.agentos.config

import io.biznet.agentos.config.properties.AgentOsPluginsConfigProperties
import org.pf4j.PluginManager
import org.pf4j.spring.SpringPluginManager
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.nio.file.Files
import java.nio.file.Paths

/**
 * Configuration for PF4J plugin management.
 */
@Configuration
class PluginConfiguration {
    @Bean
    fun pluginManager(pluginsConfigProperties: AgentOsPluginsConfigProperties): PluginManager {
        val pluginPath = Paths.get(pluginsConfigProperties.dir)

        // Create plugins directory if it doesn't exist
        if (!Files.exists(pluginPath)) {
            Files.createDirectories(pluginPath)
        }

        return SpringPluginManager(pluginPath).apply {
            loadPlugins()
            startPlugins()
        }
    }
}
