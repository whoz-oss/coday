package io.whozoss.agentos.plugin

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import org.pf4j.DefaultPluginManager

class PluginServiceUnitSpec : StringSpec({

    "should create plugin service" {
        val pluginManager = DefaultPluginManager()
        val pluginService = PluginService(pluginManager)

        pluginService.shouldNotBeNull()
    }

    "should get empty plugin list initially" {
        val pluginManager = DefaultPluginManager()
        val pluginService = PluginService(pluginManager)

        pluginService.getLoadedPlugins() shouldHaveSize 0
    }
})
