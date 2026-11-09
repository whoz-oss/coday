package io.whozoss.factory.forge.plugin

import org.pf4j.ClassLoadingStrategy
import org.pf4j.CompoundPluginLoader
import org.pf4j.DefaultPluginLoader
import org.pf4j.ExtensionFactory
import org.pf4j.JarPluginLoader
import org.pf4j.PluginClassLoader
import org.pf4j.PluginDescriptor
import org.pf4j.PluginLoader
import org.pf4j.spring.SpringExtensionFactory
import org.pf4j.spring.SpringPluginManager
import java.nio.file.Path

/**
 * Test-only [SpringPluginManager] that loads the plugin JAR in a dedicated
 * child [PluginClassLoader] while only delegating `org.pf4j.*`, `io.whozoss.factory.sdk.*`,
 * `org.springframework.*` and `com.fasterxml.jackson.*` to the test classloader
 * (since in unit tests the plugin's classes are also on the test runner classpath).
 */
internal class ForgeTestPluginManager(pluginsDir: Path) : SpringPluginManager(pluginsDir) {

    override fun createExtensionFactory(): ExtensionFactory = SpringExtensionFactory(this)

    override fun createPluginLoader(): PluginLoader =
        CompoundPluginLoader()
            .add(
                object : JarPluginLoader(this) {
                    override fun loadPlugin(
                        pluginPath: Path,
                        pluginDescriptor: PluginDescriptor,
                    ): ClassLoader {
                        // Use PDA in unit tests so `io.whozoss.factory.forge.*` classes are
                        // loaded from the plugin JAR's own classloader (since the plugin JAR
                        // does not bundle SDK/Spring/PF4J, those still fall back to Application).
                        val classLoader = PluginClassLoader(
                            pluginManager,
                            pluginDescriptor,
                            javaClass.classLoader,
                            ClassLoadingStrategy.PDA,
                        )
                        classLoader.addFile(pluginPath.toFile())
                        return classLoader
                    }
                },
            )
            .add(DefaultPluginLoader(this))
}
