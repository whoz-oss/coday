package io.whozoss.factory.config

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
 * Configuration of the PF4J plugin system.
 *
 * Mirrors AgentOS's `PluginConfiguration`: a [NullSafeSpringPluginManager] with
 * the APD class-loading strategy so a plugin bundling a library already present
 * on the service classpath (e.g. Jackson, PF4J itself) reuses the shared copy
 * instead of loading a second one and triggering a [LinkageError].
 */
@Configuration
class PluginConfiguration {
    @Bean
    fun pluginManager(
        pluginsConfigProperties: FactoryPluginConfigProperties,
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
 * service classpath (e.g. Jackson, which many plugins pull in transitively) causes the JVM to
 * load two distinct copies of the same class — one from the service classloader, one from the
 * [PluginClassLoader]. Any type that crosses the plugin/service boundary (e.g. via a shared
 * interface in the SDK) then triggers a [LinkageError] (loader constraint violation).
 *
 * APD inverts the lookup order: the [PluginClassLoader] delegates to the service classloader
 * first. If the service already provides a class, the plugin uses that shared instance.
 * Only classes absent from the service classpath are loaded from the plugin JAR itself.
 */
private class NullSafeSpringPluginManager(pluginsRoot: Path) : SpringPluginManager(pluginsRoot) {
    override fun createExtensionFactory(): ExtensionFactory = NullSafeSpringExtensionFactory(this)

    override fun createPluginLoader(): PluginLoader =
        CompoundPluginLoader()
            .add(ApdJarPluginLoader(this))
            .add(ApdDefaultPluginLoader(this))
}

/**
 * [JarPluginLoader] subclass that overrides [loadPlugin] to use
 * [ClassLoadingStrategy.APD] (Application first) instead of the default PDA.
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
 * [ClassLoadingStrategy.APD] (Application first) for "exploded" plugin directories.
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
 * via the root [ApplicationContext].
 */
private class NullSafeSpringExtensionFactory(
    private val manager: SpringPluginManager,
) : SpringExtensionFactory(manager) {
    override fun <T : Any?> create(extensionClass: Class<T>): T? {
        val wrapper = manager.whichPlugin(extensionClass)
        // `Plugin.getWrapper()` is the only way to detect an extension whose plugin
        // was built with PF4J's no-arg `Plugin()` constructor (its wrapper field is
        // null). PF4J 3.13 deprecates it in favour of a custom `PluginContext`, but
        // that is an architectural migration, not a drop-in replacement, so we keep
        // the exact original behaviour and silence the deprecation locally.
        @Suppress("DEPRECATION")
        val pluginWrapper = wrapper?.plugin?.wrapper
        return if (pluginWrapper == null) {
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
