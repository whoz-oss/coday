package io.whozoss.agentos.plugins.factorybridge

import com.fasterxml.jackson.databind.ObjectMapper
import okhttp3.OkHttpClient

data class FactoryBridgeServices(
    val config: FactoryBridgeConfig,
    val objectMapper: ObjectMapper,
    val httpClient: OkHttpClient,
    val signer: FactoryTrustedHeaderSigner,
) : AutoCloseable {
    override fun close() {
        httpClient.dispatcher.executorService.shutdown()
        httpClient.connectionPool.evictAll()
        httpClient.cache?.close()
    }
}
