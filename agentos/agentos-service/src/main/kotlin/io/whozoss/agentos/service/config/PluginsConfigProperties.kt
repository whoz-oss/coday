package io.whozoss.agentos.service.config

import org.springframework.boot.context.properties.ConfigurationProperties

const val DEFAULT_PLUGINS_DIR = "plugins/"

@ConfigurationProperties("agentos.plugins")
data class AgentOsPluginsConfigProperties(
    val dir: String = DEFAULT_PLUGINS_DIR,
)
