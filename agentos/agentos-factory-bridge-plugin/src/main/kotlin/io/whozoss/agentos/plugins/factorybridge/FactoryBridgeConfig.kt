package io.whozoss.agentos.plugins.factorybridge

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Runtime configuration for the Factory Bridge plugin, resolved from JVM system
 * properties and environment variables.
 *
 * The plugin runs inside the AgentOS host process and cannot rely on Spring's
 * `@Value` injection, so configuration is read from:
 *
 * | Value       | System property                | Environment variable            | Default                 |
 * |-------------|--------------------------------|---------------------------------|-------------------------|
 * | base URL    | `agentos.factory.base-url`     | `AGENTOS_FACTORY_BASE_URL`       | `http://localhost:3141` |
 * | runtime id  | `agentos.factory.runtime-id`   | `AGENTOS_FACTORY_RUNTIME_ID`     | `agentos-primary`       |
 */
data class FactoryBridgeConfig(
    val baseUrl: String,
    val runtimeId: String,
) {
    /**
     * Shared OkHttp client with conservative timeouts matching the historical
     * AgentOS Factory integration so behaviour is unchanged after extraction.
     */
    fun httpClient(): OkHttpClient =
        OkHttpClient
            .Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .build()

    companion object {
        const val DEFAULT_BASE_URL = "http://localhost:3141"
        const val DEFAULT_RUNTIME_ID = "agentos-primary"

        fun fromEnvironment(): FactoryBridgeConfig =
            FactoryBridgeConfig(
                baseUrl = resolve("agentos.factory.base-url", "AGENTOS_FACTORY_BASE_URL", DEFAULT_BASE_URL),
                runtimeId = resolve("agentos.factory.runtime-id", "AGENTOS_FACTORY_RUNTIME_ID", DEFAULT_RUNTIME_ID),
            )

        private fun resolve(
            systemProperty: String,
            environmentVariable: String,
            default: String,
        ): String =
            System.getProperty(systemProperty)
                ?.takeIf { it.isNotBlank() }
                ?: System.getenv(environmentVariable)?.takeIf { it.isNotBlank() }
                ?: default
    }
}
