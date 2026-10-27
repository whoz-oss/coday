package io.whozoss.factory.config

import org.springframework.boot.context.properties.ConfigurationProperties

/** Configuration of the Factory Cockpit assets served under `/factory`. */
@ConfigurationProperties(prefix = "factory.cockpit")
data class FactoryCockpitProperties(
    /** Optional external directory overriding the cockpit embedded in the JAR. */
    val assetsDir: String? = null,
)
