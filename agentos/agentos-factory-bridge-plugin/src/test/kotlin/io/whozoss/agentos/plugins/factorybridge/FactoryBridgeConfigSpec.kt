package io.whozoss.agentos.plugins.factorybridge

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.string.shouldNotContain

class FactoryBridgeConfigSpec : StringSpec({
    "configuration does not expose the M2M secret" {
        FactoryBridgeConfig("https://factory", "top-secret", "bridge", listOf("workflow:write"))
            .toString() shouldNotContain "top-secret"
    }
})
