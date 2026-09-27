package io.whozoss.factory.forge.plugin

import org.pf4j.PluginWrapper
import org.pf4j.spring.SpringPlugin
import org.pf4j.spring.SpringPluginManager
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.AnnotationConfigApplicationContext

/**
 * PF4J entry point of the Forge plugin.
 *
 * Extends [SpringPlugin] so the plugin gets its own child [ApplicationContext]
 * (with the host context as parent), in which the Forge services, the Jira HTTP
 * client and the PF4J extensions are autowired. Because the plugin is loaded
 * into the host JVM with the APD class-loading strategy, Spring, Jackson, PF4J
 * and the host's shared types are all reused from the host classloader.
 */
class ForgePlugin(wrapper: PluginWrapper) : SpringPlugin(wrapper) {

    override fun createApplicationContext(): ApplicationContext {
        val context = AnnotationConfigApplicationContext()
        context.setClassLoader(wrapper.pluginClassLoader)
        (wrapper.pluginManager as? SpringPluginManager)?.applicationContext?.let { context.parent = it }
        context.scan("io.whozoss.factory.forge")
        context.refresh()
        return context
    }
}
