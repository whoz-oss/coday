package io.whozoss.agentos.plugins.factorybridge

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

internal object FactoryBridgePluginHolder {
    @Volatile private var services: FactoryBridgeServices? = null

    val current: FactoryBridgeServices
        get() = services ?: synchronized(this) {
            services ?: createServices().also { services = it }
        }

    fun start() { current }
    fun stop() {
        synchronized(this) {
            services?.close()
            services = null
        }
    }

    private fun createServices(): FactoryBridgeServices {
        val config = FactoryBridgeConfig.fromEnvironment()
        return FactoryBridgeServices(
            config = config,
            objectMapper = jacksonObjectMapper(),
            httpClient = config.httpClient(),
            signer = FactoryTrustedHeaderSigner(config.sharedSecret, config.serviceIdentityId, config.scopes),
        )
    }
}
