package io.whozoss.agentos.config

import io.whozoss.agentos.service.config.AgentOsPluginsConfigProperties
import mu.KLogging
import org.pf4j.ClassLoadingStrategy
import org.pf4j.CompoundPluginLoader
import org.pf4j.DefaultPluginLoader
import org.pf4j.ExtensionFactory
import org.pf4j.JarPluginLoader
import org.pf4j.PluginClassLoader
import org.pf4j.PluginDescriptor
import org.pf4j.PluginLoader
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
 * [SpringPluginManager] subclass that:
 * 1. Overrides the extension factory with a null-safe variant (see [NullSafeSpringExtensionFactory]).
 * 2. Overrides the plugin loader to use [ClassLoadingStrategy.APD] (Application → Plugin → Dependencies)
 *    instead of the PF4J default [ClassLoadingStrategy.PDA] (Plugin → Dependencies → Application).
 *
 * ## Why APD matters
 *
 * With the default PDA strategy, a plugin JAR that bundles a library already present on the
 * service classpath (e.g. Jackson, which the MCP SDK pulls in transitively) causes the JVM to
 * load two distinct copies of the same class — one from the service classloader, one from the
 * [PluginClassLoader]. Any type that crosses the plugin/service boundary (e.g. via a shared
 * interface in the SDK) then triggers a [LinkageError] (loader constraint violation).
 *
 * APD inverts the lookup order: the [PluginClassLoader] delegates to the service classloader
 * first. If the service already provides a class, the plugin uses that shared instance.
 * Only classes absent from the service classpath are loaded from the plugin JAR itself.
 * This is the standard behaviour of managed runtimes (OSGi, JEE) and eliminates the
 * duplicate-class problem without requiring plugins to carefully exclude every shared lib.
 */
private class NullSafeSpringPluginManager(pluginsRoot: Path) : SpringPluginManager(pluginsRoot) {
    override fun createExtensionFactory(): ExtensionFactory =
        NullSafeSpringExtensionFactory(this)

    override fun createPluginLoader(): PluginLoader =
        CompoundPluginLoader()
            .add(ApdJarPluginLoader(this))
            .add(ApdDefaultPluginLoader(this))
}

/**
 * [JarPluginLoader] subclass that overrides [loadPlugin] to use
 * [ClassLoadingStrategy.APD] (Application first) instead of the default PDA.
 *
 * [JarPluginLoader.loadPlugin] creates a [PluginClassLoader] with the 3-argument
 * constructor (which defaults to PDA), then adds the JAR file. We replicate this
 * logic but pass [ClassLoadingStrategy.APD] via the 4-argument constructor.
 */
private class ApdJarPluginLoader(private val manager: PluginManager) : JarPluginLoader(manager) {
    override fun loadPlugin(pluginPath: Path, pluginDescriptor: PluginDescriptor): ClassLoader {
        val classLoader = PluginClassLoader(manager, pluginDescriptor, javaClass.classLoader, ClassLoadingStrategy.APD)
        classLoader.addFile(pluginPath.toFile())
        return classLoader
    }
}

/**
 * [DefaultPluginLoader] subclass that overrides [createPluginClassLoader] to use
 * [ClassLoadingStrategy.APD] (Application first).
 *
 * This loader handles "exploded" plugin directories (development mode) where classes
 * live in a `classes/` subdirectory and JARs in a `lib/` subdirectory.
 */
private class ApdDefaultPluginLoader(pluginManager: PluginManager) : DefaultPluginLoader(pluginManager) {
    override fun createPluginClassLoader(pluginPath: Path, pluginDescriptor: PluginDescriptor): PluginClassLoader =
        PluginClassLoader(pluginManager, pluginDescriptor, javaClass.classLoader, ClassLoadingStrategy.APD)
}

/**
 * [SpringExtensionFactory] that guards against null [org.pf4j.PluginWrapper] values.
 *
 * When [create] is called for an extension class that lives on the application
 * classpath (not inside a plugin JAR), [org.pf4j.Plugin.getWrapper] returns null.
 * The standard [SpringExtensionFactory] does not guard against this and throws
 * [NullPointerException] in `nameOf()` via `getWrapper().getPluginId()`.
 *
 * Fix: check [PluginManager.whichPlugin] before delegating to [SpringExtensionFactory].
 * If no plugin owns the extension class, bypass the parent and instantiate directly
 * via the root [ApplicationContext]. This avoids catching NPE broadly, which would
 * mask unrelated failures inside [SpringExtensionFactory.create].
 */
private class NullSafeSpringExtensionFactory(
    private val manager: SpringPluginManager,
) : SpringExtensionFactory(manager) {
    override fun <T : Any?> create(extensionClass: Class<T>): T? {
        // SpringExtensionFactory.create() calls nameOf() which calls
        // plugin.getWrapper().getPluginId(). When the extension was discovered
        // via the app-classpath extensions.idx (e.g. from pf4j-spring itself)
        // rather than from a plugin JAR, getWrapper() returns null and NPEs.
        //
        // We replicate the plugin-lookup that SpringExtensionFactory does and
        // short-circuit to the root ApplicationContext when no owning plugin
        // wrapper exists, avoiding the NPE without catching exceptions broadly.
        val wrapper = manager.whichPlugin(extensionClass)
        return if (wrapper?.plugin?.wrapper == null) {
            logger.debug {
                "Extension ${extensionClass.name} has no initialised plugin wrapper; " +
                    "instantiating via root ApplicationContext"
            }
            manager.applicationContext.autowireCapableBeanFactory
                .createBean(extensionClass)
        } else {
            super.create(extensionClass)
        }
    }

    companion object : KLogging()
}
