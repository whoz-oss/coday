<<<<<<<< HEAD:agentos/agentos-service/src/main/kotlin/io/whozoss/agentos/config/properties/PluginsConfigProperties.kt
package io.whozoss.agentos.config.properties
========
package io.whozoss.agentos.service.config
>>>>>>>> origin/master:agentos/agentos-service/src/main/kotlin/io/whozoss/agentos/service/config/PluginsConfigProperties.kt

import org.springframework.boot.context.properties.ConfigurationProperties

const val DEFAULT_PLUGINS_DIR = "plugins/"

@ConfigurationProperties("agentos.plugins")
data class AgentOsPluginsConfigProperties(
    val dir: String = DEFAULT_PLUGINS_DIR,
)
