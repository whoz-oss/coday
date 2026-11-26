package io.whozoss.agentos.integrationConfig

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver
import org.springframework.boot.context.properties.source.ConfigurationPropertySources
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.MutablePropertySources
import org.springframework.core.io.ClassPathResource

/**
 * How `agentos.integrations.environment-tokens` binds: the names of the tokens must reach
 * [IntegrationsProperties] exactly as written, which Spring only does for the bracket notation of a map
 * key. The shipped `application.yml` is loaded for real, so that a regression of its own syntax is noticed.
 */
class IntegrationsPropertiesBindingSpec :
    StringSpec({
        fun bind(environment: Map<String, Any> = emptyMap()): IntegrationsProperties {
            val sources = MutablePropertySources()
            sources.addFirst(MapPropertySource("environment", environment))
            YamlPropertySourceLoader()
                .load("application", ClassPathResource("application.yml"))
                .forEach { sources.addLast(it) }
            return Binder(
                ConfigurationPropertySources.from(sources),
                PropertySourcesPlaceholdersResolver(sources),
            ).bind("agentos.integrations", Bindable.of(IntegrationsProperties::class.java)).get()
        }

        "the shipped configuration declares FACTORY_URL with its exact name, empty without an environment variable" {
            bind().environmentTokens shouldBe mapOf("FACTORY_URL" to "")
        }

        "the shipped FACTORY_URL takes the value of the environment variable" {
            bind(mapOf("FACTORY_URL" to "http://127.0.0.1:3003")).environmentTokens shouldBe
                mapOf("FACTORY_URL" to "http://127.0.0.1:3003")
        }

        "the other shipped settings keep their defaults" {
            bind().autoGrantableTypes shouldBe listOf(IntegrationTypeConstraints.QUERY_USER_TYPE)
            bind().userScopeDeniedTypes shouldBe IntegrationsProperties().userScopeDeniedTypes
        }

        "a bracket key keeps its upper case and underscores" {
            val bound =
                Binder(
                    org.springframework.boot.context.properties.source.MapConfigurationPropertySource(
                        mapOf("agentos.integrations.environment-tokens.[FACTORY_URL]" to "http://f"),
                    ),
                ).bind("agentos.integrations", Bindable.of(IntegrationsProperties::class.java)).get()

            bound.environmentTokens shouldBe mapOf("FACTORY_URL" to "http://f")
        }

        "the environment variable form binds, and is folded back to the token name by the customizer" {
            val bound =
                Binder(
                    org.springframework.boot.context.properties.source.MapConfigurationPropertySource(
                        mapOf("agentos.integrations.environment-tokens.namespace.path" to "/data/ns"),
                    ),
                ).bind("agentos.integrations", Bindable.of(IntegrationsProperties::class.java)).get()

            bound.environmentTokens.values.single() shouldBe "/data/ns"
        }
    })
