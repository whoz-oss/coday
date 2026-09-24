package io.whozoss.agentos.plugins.factorybridge

import io.whozoss.agentos.sdk.tool.StandardTool

/**
 * Shared test fixtures for the Factory Bridge plugin.
 *
 * All extensions/plugins resolve their collaborators through a `() -> FactoryBridgeServices`
 * lambda; tests inject a fully-formed, in-memory services instance instead of relying on
 * [FactoryBridgePluginHolder] lifecycle.
 */
internal object FactoryTestFixtures {
    fun services(
        baseUrl: String = "http://localhost:3141",
        runtimeId: String = "test-runtime",
    ): FactoryBridgeServices = FactoryBridgeServices.create(FactoryBridgeConfig(baseUrl, runtimeId))

    fun tools(baseUrl: String = "http://localhost:3141"): List<StandardTool<*>> = buildFactoryTools(services(baseUrl))

    fun grantService(baseUrl: String = "http://localhost:3141"): FactoryToolGrantService =
        FactoryToolGrantService { tools(baseUrl) }
}
