package io.whozoss.agentos.integrationConfig

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.whozoss.agentos.agent.AgentExecutionContext
import io.whozoss.agentos.sdk.entity.EntityMetadata
import java.time.Instant
import java.util.UUID

/**
 * Unit tests for [EnvironmentTokenRunIntegration]: which tokens are resolved, what an unset value does to
 * the integration that carries it, and which declarations are ignored.
 */
class EnvironmentTokenRunIntegrationUnitSpec : StringSpec() {
    private val namespaceId = UUID.randomUUID()
    private val run =
        AgentExecutionContext(namespaceId = namespaceId, caseId = UUID.randomUUID(), caseCreatedAt = Instant.now())
    private val preview = AgentExecutionContext(namespaceId = namespaceId)

    private fun customizer(vararg tokens: Pair<String, String>) =
        EnvironmentTokenRunIntegration(IntegrationsProperties(environmentTokens = mapOf(*tokens)))

    private fun config(
        name: String,
        parameters: String?,
    ) = IntegrationConfig(
        metadata = EntityMetadata(id = UUID.randomUUID()),
        namespaceId = namespaceId,
        name = name,
        integrationType = "HTTP_API",
        parameters = parameters?.let { jacksonObjectMapper().readTree(it) },
    )

    private fun JsonNode?.text(field: String): String? = this?.get(field)?.asText()

    init {
        "with no token declared, the very same list is returned" {
            val configs = listOf(config("api", """{"baseUrl": "{{FACTORY_URL}}"}"""))

            customizer().customize(configs, run) shouldBe configs
            (customizer().customize(configs, run) === configs) shouldBe true
        }

        "a config without a declared token is returned as the same instance" {
            val plain = config("plain", """{"baseUrl": "https://api.example.com"}""")
            val none = config("none", null)

            val result = customizer("FACTORY_URL" to "http://factory").customize(listOf(plain, none), run)

            result shouldHaveSize 2
            (result[0] === plain) shouldBe true
            (result[1] === none) shouldBe true
        }

        "a declared token is substituted, positionally and repeated inside a string" {
            val configs =
                listOf(config("api", """{"baseUrl": "{{FACTORY_URL}}/api", "cmd": "curl {{FACTORY_URL}}/a {{FACTORY_URL}}/b"}"""))

            val parameters = customizer("FACTORY_URL" to "http://127.0.0.1:3003").customize(configs, run).single().parameters

            parameters.text("baseUrl") shouldBe "http://127.0.0.1:3003/api"
            parameters.text("cmd") shouldBe "curl http://127.0.0.1:3003/a http://127.0.0.1:3003/b"
        }

        "tokens are substituted in nested objects and arrays, and numbers are preserved" {
            val configs =
                listOf(
                    config(
                        "nested",
                        """
                        {
                          "timeoutSeconds": 30,
                          "enabled": true,
                          "servers": [
                            { "url": "{{FACTORY_URL}}/mcp", "retries": 3 },
                            "{{FACTORY_URL}}"
                          ]
                        }
                        """.trimIndent(),
                    ),
                )

            val parameters = customizer("FACTORY_URL" to "http://f").customize(configs, run).single().parameters

            parameters.text("timeoutSeconds") shouldBe "30"
            parameters?.get("timeoutSeconds")?.isInt shouldBe true
            parameters?.get("enabled")?.isBoolean shouldBe true
            parameters?.get("servers")?.get(0)?.text("url") shouldBe "http://f/mcp"
            parameters?.get("servers")?.get(0)?.get("retries")?.isInt shouldBe true
            parameters?.get("servers")?.get(1)?.asText() shouldBe "http://f"
        }

        "several declared tokens are each resolved to their own value" {
            val configs = listOf(config("api", """{"a": "{{FACTORY_URL}}", "b": "{{NAMESPACE_PATH}}/x"}"""))

            val parameters =
                customizer("FACTORY_URL" to "http://f", "NAMESPACE_PATH" to "/data/ns")
                    .customize(configs, run)
                    .single()
                    .parameters

            parameters.text("a") shouldBe "http://f"
            parameters.text("b") shouldBe "/data/ns/x"
        }

        "the saved configuration is never rewritten" {
            val original = config("api", """{"baseUrl": "{{FACTORY_URL}}"}""")

            customizer("FACTORY_URL" to "http://f").customize(listOf(original), run)

            original.parameters.text("baseUrl") shouldBe "{{FACTORY_URL}}"
        }

        "a blank value removes the integration that carries the token and no other" {
            val configs =
                listOf(
                    config("api", """{"baseUrl": "{{FACTORY_URL}}"}"""),
                    config("plain", """{"baseUrl": "https://api.example.com"}"""),
                    config("datetime", null),
                )

            val result = customizer("FACTORY_URL" to "").customize(configs, run)

            result.map { it.name } shouldBe listOf("plain", "datetime")
        }

        "a whitespace-only value counts as unset, also outside a run" {
            val configs = listOf(config("api", """{"baseUrl": "{{FACTORY_URL}}"}"""), config("datetime", null))

            customizer("FACTORY_URL" to "  ").customize(configs, preview).map { it.name } shouldBe listOf("datetime")
        }

        "a config carrying one set and one unset token is removed" {
            val configs = listOf(config("api", """{"a": "{{FACTORY_URL}}", "b": "{{OTHER_URL}}"}"""))

            customizer("FACTORY_URL" to "http://f", "OTHER_URL" to "").customize(configs, run) shouldHaveSize 0
        }

        "an undeclared token is left literal" {
            val configs = listOf(config("api", """{"a": "{{SOMETHING}}", "b": "{{FACTORY_URL}}"}"""))

            val parameters = customizer("FACTORY_URL" to "http://f").customize(configs, run).single().parameters

            parameters.text("a") shouldBe "{{SOMETHING}}"
            parameters.text("b") shouldBe "http://f"
        }

        "invalid keys are ignored" {
            val configs = listOf(config("api", """{"a": "{{1ST}}", "b": "{{BAD-NAME}}", "c": "{{ }}", "d": "{{}}"}"""))

            val result =
                customizer("1ST" to "x", "BAD-NAME" to "x", " " to "x", "" to "x", "Mixed_Case" to "x")
                    .customize(configs, run)

            (result === configs) shouldBe true
            result.single().parameters.text("a") shouldBe "{{1ST}}"
        }

        "reserved keys are ignored: the exchange tokens and the config path keep their own resolution" {
            val configs =
                listOf(
                    config(
                        "api",
                        """{"a": "{{CASE_EXCHANGE}}", "b": "{{NAMESPACE_EXCHANGE}}", "c": "{{NAMESPACE_CONFIG_PATH}}"}""",
                    ),
                )

            val result =
                customizer(
                    "CASE_EXCHANGE" to "/evil",
                    "NAMESPACE_EXCHANGE" to "/evil",
                    "NAMESPACE_CONFIG_PATH" to "/evil",
                ).customize(configs, run)

            (result === configs) shouldBe true
            result.single().parameters.text("a") shouldBe "{{CASE_EXCHANGE}}"
        }

        "an invalid or reserved key does not prevent the valid ones" {
            val configs = listOf(config("api", """{"a": "{{FACTORY_URL}}"}"""))

            val result = customizer("bad-key" to "x", "CASE_EXCHANGE" to "/evil", "FACTORY_URL" to "http://f")
                .customize(configs, run)

            result.single().parameters.text("a") shouldBe "http://f"
        }

        "a key Spring made of the environment variable form is folded back to its name" {
            // AGENTOS_INTEGRATIONS_ENVIRONMENT_TOKENS_FACTORY_URL reaches the map as factory.url
            val configs = listOf(config("api", """{"a": "{{FACTORY_URL}}"}"""))

            customizer("factory.url" to "http://f").customize(configs, run).single().parameters.text("a") shouldBe
                "http://f"
        }
    }
}
