package io.whozoss.agentos.plugin

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import org.pf4j.DefaultPluginManager

class PluginServiceTest :
    DescribeSpec({

        describe("PluginService") {

            it("should create plugin service") {
                val pluginManager = DefaultPluginManager()
                val pluginService = PluginService(pluginManager)

                pluginService.shouldNotBeNull()
            }

            it("should get empty plugin list initially") {
                val pluginManager = DefaultPluginManager()
                val pluginService = PluginService(pluginManager)

                val plugins = pluginService.getLoadedPlugins()
                plugins shouldHaveSize 0
            }
        }
    })
