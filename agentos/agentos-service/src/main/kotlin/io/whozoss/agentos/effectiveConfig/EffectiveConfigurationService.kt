package io.whozoss.agentos.effectiveConfig

import io.swagger.v3.oas.annotations.media.Schema
import org.springframework.core.env.Environment
import org.springframework.stereotype.Service

enum class ConfigurationSource {
    /** An OS environment variable of the service process. */
    ENVIRONMENT,

    /** A Spring property as the running service sees it: yml defaults, profiles, overrides and relaxed binding applied. */
    PROPERTY,
}

/**
 * One setting of the running service.
 *
 * @property value The effective value, or null when it is not set **or when it is [sensitive]**.
 * @property configured Whether a value is set, including an empty one. Stays accurate for a sensitive entry,
 *   which is how the page tells "set" from "not set" without ever receiving the secret.
 * @property sensitive True when the value is withheld by the server.
 */
@Schema(name = "ConfigurationEntry")
data class ConfigurationEntry(
    val key: String,
    val source: ConfigurationSource,
    val value: String?,
    val configured: Boolean,
    val sensitive: Boolean,
    val description: String?,
)

/**
 * Reports the effective value of every setting in [KNOWN_SETTINGS], from both the Spring `Environment` and the
 * OS environment, so an administrator can see what is on, off or unset.
 *
 * Secrets are masked here and nowhere else: a sensitive entry is built without its value, so there is nothing
 * for a later layer to forget to hide.
 */
@Service
class EffectiveConfigurationService(
    private val environment: Environment,
) {
    fun list(): List<ConfigurationEntry> = entries(System.getenv())

    internal fun entries(osEnvironment: Map<String, String>): List<ConfigurationEntry> =
        KNOWN_SETTINGS.flatMap { setting ->
            listOfNotNull(
                setting.propertyKey?.let { key ->
                    entry(key, ConfigurationSource.PROPERTY, setting) {
                        // An unresolvable placeholder is a misconfiguration, reported as unset rather than failing the page.
                        runCatching { environment.getProperty(key) }.getOrNull()
                    }
                },
                setting.envVar?.let { name ->
                    entry(name, ConfigurationSource.ENVIRONMENT, setting) { osEnvironment[name] }
                },
            )
        }

    private fun entry(
        key: String,
        source: ConfigurationSource,
        setting: KnownSetting,
        read: () -> String?,
    ): ConfigurationEntry {
        val sensitive = setting.sensitive || isSensitiveName(key)
        val raw = read()
        return ConfigurationEntry(
            key = key,
            source = source,
            value = if (sensitive) null else raw,
            configured = raw != null,
            sensitive = sensitive,
            description = setting.description,
        )
    }
}
