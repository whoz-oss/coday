package io.biznet.agentos.service.config

import org.pf4j.PluginManager
import org.pf4j.spring.SpringPluginManager
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.nio.file.Paths

/**
 * Configuration for PF4J plugin management.
 */
@Configuration
class PluginConfiguration {
    
    @Bean
    fun pluginManager(): PluginManager {
        val pluginsDir = System.getProperty("pf4j.pluginsDir", "plugins")
        return SpringPluginManager(Paths.get(pluginsDir)).apply {
            loadPlugins()
            startPlugins()
        }
    }
}
