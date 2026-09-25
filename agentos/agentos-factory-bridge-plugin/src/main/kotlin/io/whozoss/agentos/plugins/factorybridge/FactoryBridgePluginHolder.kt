package io.whozoss.agentos.plugins.factorybridge

/**
 * Singleton holder for the [FactoryBridgeServices], living in the plugin classloader:
 * created once when the plugin starts, released when it stops.
 *
 * Mirrors the holder pattern used by the other AgentOS PF4J plugins
 * (`HttpApiPluginHolder`). Extensions resolve services through a lambda
 * (`() -> FactoryBridgeServices = { FactoryBridgePluginHolder.services }`), which keeps
 * them unit-testable with an injected fake while sharing production state.
 */
object FactoryBridgePluginHolder {
    @Volatile
    private var services: FactoryBridgeServices? = null

    val current: FactoryBridgeServices
        get() = checkNotNull(services) { "Factory Bridge plugin is not started" }

    fun start() {
        services = FactoryBridgeServices.create()
    }

    fun shutdown() {
        services?.httpClient?.dispatcher?.executorService?.shutdown()
        services?.httpClient?.connectionPool?.evictAll()
        services = null
    }
}
