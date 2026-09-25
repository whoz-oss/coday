package io.whozoss.agentos.plugins.factorybridge

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import okhttp3.OkHttpClient
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Plugin-wide collaborators shared by every Factory Bridge [org.pf4j.Extension].
 *
 * Extensions are instantiated independently by the PF4J/Spring extension factory, so
 * shared state (HTTP client, Jackson mapper, the volatile step-result binding registry,
 * the open human-checkpoint interactions) must live in the plugin classloader rather than
 * in any single extension instance.
 *
 * @param pendingCheckpoints Open human-checkpoint interactions keyed by controlling case id.
 *   Populated by the human-decision tool when it opens an interaction and consumed by
 *   [FactoryAnswerInterceptor] when the user answers. Keeping this state in the plugin means
 *   neither the SDK nor the host runtime needs to know about Factory checkpoints.
 */
data class FactoryBridgeServices(
    val config: FactoryBridgeConfig,
    val objectMapper: ObjectMapper,
    val httpClient: OkHttpClient,
    val stepResultBindings: FactoryStepResultBindingRegistry,
    val pendingCheckpoints: MutableMap<UUID, FactoryCheckpointRef> = ConcurrentHashMap(),
) {
    companion object {
        fun create(config: FactoryBridgeConfig = FactoryBridgeConfig.fromEnvironment()): FactoryBridgeServices =
            FactoryBridgeServices(
                config = config,
                objectMapper = jacksonObjectMapper(),
                httpClient = config.httpClient(),
                stepResultBindings = FactoryStepResultBindingRegistry(),
                pendingCheckpoints = ConcurrentHashMap(),
            )
    }
}
