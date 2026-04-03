package io.whozoss.agentos.config

import io.whozoss.agentos.service.config.AgentOsPluginsConfigProperties
import mu.KLogging
import org.pf4j.ExtensionFactory
import org.pf4j.PluginManager
import org.pf4j.spring.SpringExtensionFactory
import org.pf4j.spring.SpringPluginManager
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Configuration for the PF4J plugin system.
 */
@Configuration
class PluginConfiguration {
    @Bean
    fun pluginManager(
        pluginsConfigProperties: AgentOsPluginsConfigProperties,
        applicationContext: ApplicationContext,
    ): PluginManager {
        val pluginPath = Paths.get(pluginsConfigProperties.dir)

        if (!Files.exists(pluginPath)) {
            Files.createDirectories(pluginPath)
        }
        logger.info("Plugin path: $pluginPath (absolute path is : ${pluginPath.toAbsolutePath()})")

        return NullSafeSpringPluginManager(pluginPath).also { it.applicationContext = applicationContext }
    }

    companion object : KLogging()
}

/**
 * [SpringPluginManager] subclass that overrides the extension factory with a
 * null-safe variant.
 *
 * ## Why this is needed
 * [SpringExtensionFactory] calls [org.pf4j.Plugin.getWrapper] when resolving the
 * application context for an extension class. When PF4J discovers an extension on
 * the application classpath (rather than inside a plugin JAR), the wrapper is null
 * and [SpringExtensionFactory.nameOf] throws [NullPointerException].
 *
 * This override returns the root [ApplicationContext] for any extension whose plugin
 * wrapper is null, which is the correct behaviour for app-classpath extensions.
 */
private class NullSafeSpringPluginManager(pluginsRoot: Path) : SpringPluginManager(pluginsRoot) {
    override fun createExtensionFactory(): ExtensionFactory =
        NullSafeSpringExtensionFactory(this)
}

/**
 * [SpringExtensionFactory] that guards against null [org.pf4j.PluginWrapper] values.
 *
 * When [create] is called for an extension class that lives on the application
 * classpath (not inside a plugin JAR), [org.pf4j.Plugin.getWrapper] returns null.
 * The standard [SpringExtensionFactory] does not guard against this and throws
 * [NullPointerException] in `nameOf()`. This subclass falls back to the root
 * [ApplicationContext] in that case, which is the appropriate context for
 * app-classpath extensions.
 */
private class NullSafeSpringExtensionFactory(
    private val manager: SpringPluginManager,
) : SpringExtensionFactory(manager) {
    override fun <T : Any?> create(extensionClass: Class<T>): T? =
        try {
            super.create(extensionClass)
        } catch (e: NullPointerException) {
            // Plugin wrapper is null — extension is on the app classpath, not in a JAR.
            // Fall back to instantiating via the root application context.
            logger.warn {
                "Extension ${extensionClass.name} has no plugin wrapper (app-classpath extension); " +
                    "falling back to root ApplicationContext"
            }
            manager.applicationContext.autowireCapableBeanFactory
                .createBean(extensionClass)
        }

    companion object : KLogging()
}
