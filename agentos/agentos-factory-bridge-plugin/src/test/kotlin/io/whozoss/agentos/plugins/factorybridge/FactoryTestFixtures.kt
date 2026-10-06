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
        baseUrl: String = "http://localhost:8141",
        runtimeId: String = "test-runtime",
        dataDir: String? = null,
        secret: String? = null,
    ): FactoryBridgeServices =
        FactoryBridgeServices.create(
            FactoryBridgeConfig(baseUrl = baseUrl, runtimeId = runtimeId, dataDir = dataDir, secret = secret),
        )

    /** Tools exposed by [FactoryWorkstreamToolPlugin] against a fake Factory [baseUrl]. */
    fun workstreamTools(baseUrl: String = "http://localhost:8141"): List<StandardTool<*>> =
        FactoryWorkstreamToolPlugin { services(baseUrl) }.provideTools(null, null, null)

    /** Tools exposed by [FactoryWorkerToolPlugin] against a fake Factory [baseUrl]. */
    fun workerTools(baseUrl: String = "http://localhost:8141"): List<StandardTool<*>> =
        FactoryWorkerToolPlugin { services(baseUrl) }.provideTools(null, null, null)
}
