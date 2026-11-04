package io.whozoss.agentos.effectiveConfig

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.springframework.mock.env.MockEnvironment

class EffectiveConfigurationServiceSpec :
    StringSpec({
        timeout = 5000

        fun List<ConfigurationEntry>.find(
            key: String,
            source: ConfigurationSource,
        ) = single { it.key == key && it.source == source }

        "a sensitive setting never exposes its value, but still says it is set" {
            val environment =
                MockEnvironment()
                    .withProperty("agentos.encryption.key", "topsecret-property")
                    .withProperty("spring.ai.openai.api-key", "topsecret-openai")
            val os = mapOf("AGENTOS_ENCRYPTION_KEY" to "topsecret-env", "OPENAI_API_KEY" to "topsecret-openai-env")

            val entries = EffectiveConfigurationService(environment).entries(os)

            listOf(
                entries.find("agentos.encryption.key", ConfigurationSource.PROPERTY),
                entries.find("AGENTOS_ENCRYPTION_KEY", ConfigurationSource.ENVIRONMENT),
                entries.find("spring.ai.openai.api-key", ConfigurationSource.PROPERTY),
                entries.find("OPENAI_API_KEY", ConfigurationSource.ENVIRONMENT),
            ).forEach {
                it.sensitive shouldBe true
                it.configured shouldBe true
                it.value shouldBe null
            }
            // toString of a data class lists every field: no secret may appear anywhere in the answer.
            entries.toString() shouldNotContain "topsecret"
        }

        "a sensitive setting that is not set is reported as not set" {
            val entries = EffectiveConfigurationService(MockEnvironment()).entries(emptyMap())

            entries.find("agentos.encryption.salt", ConfigurationSource.PROPERTY).configured shouldBe false
        }

        "a known setting that is not set is listed with no value" {
            val entries = EffectiveConfigurationService(MockEnvironment()).entries(emptyMap())

            val entry = entries.find("agentos.git.workspaces.enabled", ConfigurationSource.PROPERTY)
            entry.configured shouldBe false
            entry.value shouldBe null
            entry.sensitive shouldBe false
        }

        "the property and the environment variable of a setting are reported side by side" {
            val environment = MockEnvironment().withProperty("agentos.git.workspaces.enabled", "false")
            val os = mapOf("AGENTOS_GIT_WORKSPACES_ENABLED" to "false", "AGENTOS_HTTP_API_DISALLOW_UNSECURE" to "true")

            val entries = EffectiveConfigurationService(environment).entries(os)

            entries.find("agentos.git.workspaces.enabled", ConfigurationSource.PROPERTY).value shouldBe "false"
            entries.find("AGENTOS_GIT_WORKSPACES_ENABLED", ConfigurationSource.ENVIRONMENT).value shouldBe "false"
            entries.find("AGENTOS_HTTP_API_DISALLOW_UNSECURE", ConfigurationSource.ENVIRONMENT).value shouldBe "true"
        }

        "an empty value counts as set" {
            val environment = MockEnvironment().withProperty("agentos.limits.run-cost-threshold", "")

            val entry =
                EffectiveConfigurationService(environment).entries(emptyMap())
                    .find("agentos.limits.run-cost-threshold", ConfigurationSource.PROPERTY)

            entry.configured shouldBe true
            entry.value shouldBe ""
        }

        "names that look like secrets are recognised, others are not" {
            listOf("OPENAI_API_KEY", "agentos.encryption.salt", "GIT_TOKEN", "db.password", "client-secret").forEach {
                isSensitiveName(it) shouldBe true
            }
            listOf("agentos.git.workspaces.enabled", "AGENTOS_HTTP_API_DISALLOW_UNSECURE", "agentos.exchange.mount-root").forEach {
                isSensitiveName(it) shouldBe false
            }
        }

        "no known setting is registered twice" {
            val keys = KNOWN_SETTINGS.flatMap { listOfNotNull(it.propertyKey, it.envVar) }
            keys.size shouldBe keys.toSet().size
        }
    })
