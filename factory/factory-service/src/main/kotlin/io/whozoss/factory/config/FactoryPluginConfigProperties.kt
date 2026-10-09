package io.whozoss.factory.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Default root directory scanned by PF4J for plugin archives.
 */
const val DEFAULT_FACTORY_PLUGINS_DIR = "plugins/"

/**
 * Strongly-typed binding of the `factory.plugins.*` configuration tree.
 *
 * The directory is created automatically at startup when missing so the service
 * always boots cleanly, even with zero plugins installed.
 */
@ConfigurationProperties(prefix = "factory.plugins")
data class FactoryPluginConfigProperties(
    val dir: String = DEFAULT_FACTORY_PLUGINS_DIR,
)
