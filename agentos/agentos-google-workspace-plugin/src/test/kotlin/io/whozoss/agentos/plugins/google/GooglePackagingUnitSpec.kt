package io.whozoss.agentos.plugins.google

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.whozoss.agentos.sdk.tool.ToolPlugin
import org.pf4j.*
import java.nio.file.Path

/** Real packaged JAR loading with plugin implementation classes hidden from the host parent. */
class GooglePackagingUnitSpec : StringSpec({
    "packaged provider loads starts discovers and stops with HTTP dependency" {
        val parent = object : ClassLoader(ToolPlugin::class.java.classLoader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                if (name.startsWith("io.whozoss.agentos.plugins.google.") || name.startsWith("io.whozoss.agentos.plugins.http.")) throw ClassNotFoundException(name)
                return super.loadClass(name, resolve)
            }
        }
        val manager = object : DefaultPluginManager(Path.of("build/packaging-test-unused")) {
            override fun createPluginLoader(): PluginLoader {
                val manager = this
                return object : JarPluginLoader(manager) {
                override fun loadPlugin(path: Path, descriptor: PluginDescriptor): ClassLoader =
                    PluginClassLoader(manager, descriptor, parent, ClassLoadingStrategy.APD).apply { addFile(path.toFile()) }
            }
            }
        }
        try {
            val version = System.getProperty("google.plugin.version")
            manager.loadPlugin(Path.of("../agentos-http-plugin/build/libs/agentos-http-plugin-$version.jar")) shouldBe "agentos-http-plugin"
            manager.loadPlugin(Path.of("build/libs/agentos-google-workspace-plugin-$version.jar")) shouldBe "agentos-google-workspace-plugin"
            manager.startPlugins()
            manager.getPlugin("agentos-google-workspace-plugin").pluginState shouldBe PluginState.STARTED
            val provider = manager.getExtensions(ToolPlugin::class.java, "agentos-google-workspace-plugin").single { it.integrationType == "GOOGLE_WORKSPACE" }
            provider.javaClass.classLoader.javaClass shouldBe PluginClassLoader::class.java
            provider.provideTools(jacksonObjectMapper().readTree("{}"), "GW", null).size shouldBe 9
            provider.configSchema!!.path("additionalProperties").booleanValue() shouldBe false
        } finally { manager.stopPlugins(); manager.unloadPlugins() }
    }
})
