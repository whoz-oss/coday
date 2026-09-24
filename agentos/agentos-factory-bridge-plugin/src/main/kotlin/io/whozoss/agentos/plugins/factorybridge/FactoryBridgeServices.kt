package io.whozoss.agentos.plugins.factorybridge

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import okhttp3.OkHttpClient

/**
 * Plugin-wide collaborators shared by every Factory Bridge [org.pf4j.Extension].
 *
 * Extensions are instantiated independently by the PF4J/Spring extension factory, so
 * shared state (HTTP client, Jackson mapper, the volatile step-result binding registry)
 * must live in the plugin classloader rather than in any single extension instance.
 */
data class FactoryBridgeServices(
    val config: FactoryBridgeConfig,
    val objectMapper: ObjectMapper,
    val httpClient: OkHttpClient,
    val stepResultBindings: FactoryStepResultBindingRegistry,
) {
    companion object {
        fun create(config: FactoryBridgeConfig = FactoryBridgeConfig.fromEnvironment()): FactoryBridgeServices =
            FactoryBridgeServices(
                config = config,
                objectMapper = jacksonObjectMapper(),
                httpClient = config.httpClient(),
                stepResultBindings = FactoryStepResultBindingRegistry(),
            )
    }
}
