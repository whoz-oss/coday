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
 * | Value            | System property                     | Environment variable              | Default                 |
 * |------------------|-------------------------------------|-----------------------------------|-------------------------|
 * | base URL         | `agentos.factory.base-url`          | `AGENTOS_FACTORY_BASE_URL`        | `http://localhost:8141` |
 * | runtime id       | `agentos.factory.runtime-id`        | `AGENTOS_FACTORY_RUNTIME_ID`      | `agentos-primary`       |
 * | data dir         | `agentos.factory-bridge.data-dir`   | `AGENTOS_FACTORY_BRIDGE_DATA_DIR` | `data/factory-bridge`   |
 * | shared secret    | `agentos.factory-bridge.secret`     | `AGENTOS_FACTORY_BRIDGE_SECRET`   | *(empty → disabled)*    |
 * | service identity | `agentos.factory-bridge.service-identity-id` | `AGENTOS_FACTORY_BRIDGE_SERVICE_IDENTITY_ID` | `agentos-factory-bridge` |
 * | service scopes   | `agentos.factory-bridge.scopes`     | `AGENTOS_FACTORY_BRIDGE_SCOPES`   | `workflow:write`        |
 * | binding TTL (s)  | `agentos.factory-bridge.binding-ttl`| `AGENTOS_FACTORY_BRIDGE_BINDING_TTL` | `3600`             |
 *
 * @property dataDir directory used for the restart-safe JSON state store. `null` keeps
 *   all state in memory (useful for tests).
 * @property secret pre-shared secret the Factory presents when it binds a case; an empty
 *   secret disables the binding endpoint (fail-closed).
 * @property bindingTtlSeconds fallback validity window applied when a binding is accepted
 *   without an explicit expiry.
 */
data class FactoryBridgeConfig(
    val baseUrl: String,
    val runtimeId: String,
    val dataDir: String? = null,
    val secret: String? = null,
    val serviceIdentityId: String = DEFAULT_SERVICE_IDENTITY_ID,
    val scopes: List<String> = DEFAULT_SCOPES,
    val bindingTtlSeconds: Long = DEFAULT_BINDING_TTL_SECONDS,
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
        const val DEFAULT_BASE_URL = "http://localhost:8141"
        const val DEFAULT_RUNTIME_ID = "agentos-primary"
        const val DEFAULT_SERVICE_IDENTITY_ID = "agentos-factory-bridge"
        val DEFAULT_SCOPES = listOf("workflow:write")
        const val DEFAULT_BINDING_TTL_SECONDS = 3600L

        fun fromEnvironment(): FactoryBridgeConfig =
            FactoryBridgeConfig(
                baseUrl = resolve("agentos.factory.base-url", "AGENTOS_FACTORY_BASE_URL", DEFAULT_BASE_URL),
                runtimeId = resolve("agentos.factory.runtime-id", "AGENTOS_FACTORY_RUNTIME_ID", DEFAULT_RUNTIME_ID),
                dataDir = resolve("agentos.factory-bridge.data-dir", "AGENTOS_FACTORY_BRIDGE_DATA_DIR", "data/factory-bridge"),
                secret = resolve("agentos.factory-bridge.secret", "AGENTOS_FACTORY_BRIDGE_SECRET", ""),
                serviceIdentityId = resolve(
                    "agentos.factory-bridge.service-identity-id",
                    "AGENTOS_FACTORY_BRIDGE_SERVICE_IDENTITY_ID",
                    DEFAULT_SERVICE_IDENTITY_ID,
                ),
                scopes = resolve(
                    "agentos.factory-bridge.scopes",
                    "AGENTOS_FACTORY_BRIDGE_SCOPES",
                    DEFAULT_SCOPES.joinToString(","),
                ).split(",").map { it.trim() }.filter { it.isNotEmpty() },
                bindingTtlSeconds =
                    resolve("agentos.factory-bridge.binding-ttl", "AGENTOS_FACTORY_BRIDGE_BINDING_TTL", DEFAULT_BINDING_TTL_SECONDS.toString())
                        .toLongOrNull()
                        ?.takeIf { it > 0 }
                        ?: DEFAULT_BINDING_TTL_SECONDS,
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
