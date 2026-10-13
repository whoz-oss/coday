package io.whozoss.agentos.plugins.factorybridge

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

data class FactoryBridgeConfig(
    val baseUrl: String,
    val sharedSecret: String?,
    val serviceIdentityId: String,
    val scopes: List<String>,
) {
    fun httpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

    override fun toString(): String =
        "FactoryBridgeConfig(baseUrl=$baseUrl, sharedSecret=<redacted>, serviceIdentityId=$serviceIdentityId, scopes=$scopes)"

    companion object {
        fun fromEnvironment(): FactoryBridgeConfig = FactoryBridgeConfig(
            baseUrl = resolve("agentos.factory.base-url", "AGENTOS_FACTORY_BASE_URL", "http://localhost:8141"),
            sharedSecret = resolve("agentos.factory-bridge.secret", "AGENTOS_FACTORY_BRIDGE_SECRET", "").ifBlank { null },
            serviceIdentityId = resolve(
                "agentos.factory-bridge.service-identity-id",
                "AGENTOS_FACTORY_BRIDGE_SERVICE_IDENTITY_ID",
                "agentos-factory-bridge",
            ),
            scopes = resolve("agentos.factory-bridge.scopes", "AGENTOS_FACTORY_BRIDGE_SCOPES", "workflow:write")
                .split(',').map(String::trim).filter(String::isNotEmpty),
        )

        private fun resolve(property: String, environment: String, default: String): String =
            System.getProperty(property)?.takeIf(String::isNotBlank)
                ?: System.getenv(environment)?.takeIf(String::isNotBlank)
                ?: default
    }
}
