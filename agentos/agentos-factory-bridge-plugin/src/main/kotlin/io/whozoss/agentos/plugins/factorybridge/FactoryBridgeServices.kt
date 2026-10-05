package io.whozoss.agentos.plugins.factorybridge

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.whozoss.agentos.plugins.factorybridge.persistence.FactoryBridgeStateStore
import io.whozoss.agentos.plugins.factorybridge.persistence.FactorySseHighWaterMarkStore
import okhttp3.OkHttpClient
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Plugin-wide collaborators shared by every Factory Bridge [org.pf4j.Extension].
 *
 * Extensions are instantiated independently by the PF4J/Spring extension factory, so
 * shared state (HTTP client, Jackson mapper, the durable step-result binding registry,
 * the open human-checkpoint interactions) must live in the plugin classloader rather than
 * in any single extension instance.
 *
 * @param pendingCheckpoints Open human-checkpoint interactions keyed by controlling case id.
 *   Populated by the human-decision tool when it opens an interaction and consumed by
 *   [FactoryAnswerInterceptor] when the user answers. Backed by the durable
 *   [FactoryBridgeStateStore] so pending approvals survive an AgentOS restart.
 * @param stateStore restart-safe backing store for bindings, leases and pending
 *   checkpoints.
 * @param sseHighWaterMarks restart-safe store for the bridge-side SSE `(timestamp, id)`
 *   high-water mark per `(case, attempt)`.
 */
data class FactoryBridgeServices(
    val config: FactoryBridgeConfig,
    val objectMapper: ObjectMapper,
    val httpClient: OkHttpClient,
    val trustedHeaderSigner: FactoryTrustedHeaderSigner,
    val stepResultBindings: FactoryStepResultBindingRegistry,
    val pendingCheckpoints: MutableMap<UUID, FactoryCheckpointRef> = ConcurrentHashMap(),
    val stateStore: FactoryBridgeStateStore? = null,
    val sseHighWaterMarks: FactorySseHighWaterMarkStore? = null,
) {
    companion object {
        fun create(config: FactoryBridgeConfig = FactoryBridgeConfig.fromEnvironment()): FactoryBridgeServices {
            val mapper = jacksonObjectMapper()
            val store = FactoryBridgeStateStore.open(config.dataDir, mapper)
            return FactoryBridgeServices(
                config = config,
                objectMapper = mapper,
                httpClient = config.httpClient(),
                trustedHeaderSigner = FactoryTrustedHeaderSigner(config.secret, config.serviceIdentityId, config.scopes),
                stepResultBindings = FactoryStepResultBindingRegistry(store = store),
                pendingCheckpoints = store.checkpointMap,
                stateStore = store,
                sseHighWaterMarks = FactorySseHighWaterMarkStore.open(config.dataDir, mapper),
            )
        }
    }
}
