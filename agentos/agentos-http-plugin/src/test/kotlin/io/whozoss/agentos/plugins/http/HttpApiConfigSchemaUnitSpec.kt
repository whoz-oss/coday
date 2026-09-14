package io.whozoss.agentos.plugins.http

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.BooleanNode
import io.kotest.assertions.withClue
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.whozoss.agentos.plugins.http.config.AuthConfig
import io.whozoss.agentos.plugins.http.config.HttpApiConfig
import io.whozoss.agentos.plugins.http.config.HttpApiConfigParser
import io.whozoss.agentos.plugins.http.config.OperationOverride
import io.whozoss.agentos.plugins.http.config.SpecConfig
import io.whozoss.agentos.plugins.http.openapi.json
import java.lang.reflect.Modifier

/**
 * The UI form only re-emits the keys the schema declares and shows the schema defaults, so the schema must
 * declare exactly the config keys and agree with the Kotlin defaults.
 */
class HttpApiConfigSchemaUnitSpec : StringSpec({
    isolationMode = IsolationMode.InstancePerLeaf

    val schema = HttpApiConfigSchema.schema

    fun fieldsOf(type: Class<*>): List<String> =
        type.declaredFields.filter { !it.isSynthetic && !Modifier.isStatic(it.modifiers) }.map { it.name }

    fun propertiesOf(node: JsonNode): List<String> = node.path("properties").properties().map { it.key }

    "declares exactly the HttpApiConfig keys at every level" {
        propertiesOf(schema) shouldContainExactlyInAnyOrder fieldsOf(HttpApiConfig::class.java)
        propertiesOf(schema.path("properties").path("spec")) shouldContainExactlyInAnyOrder
            fieldsOf(SpecConfig::class.java)
        propertiesOf(schema.path("properties").path("auth")) shouldContainExactlyInAnyOrder
            fieldsOf(AuthConfig::class.java)
        propertiesOf(schema.path("properties").path("operations").path("items")) shouldContainExactlyInAnyOrder
            fieldsOf(OperationOverride::class.java)
    }

    "tells the administrator that an untouched auth block follows the document security scheme" {
        val auth = schema.path("properties").path("auth")
        auth.path("description").asText() shouldContain "securitySchemes"
        auth.path("description").asText() shouldContain "left at its defaults"
    }

    "requires only spec and refuses undeclared keys" {
        schema.path("required").map { it.asText() } shouldBe listOf("spec")
        schema.path("additionalProperties") shouldBe BooleanNode.FALSE
        schema.path("properties").path("spec").path("additionalProperties") shouldBe BooleanNode.FALSE
        schema.path("properties").path("auth").path("additionalProperties") shouldBe BooleanNode.FALSE
        val operation = schema.path("properties").path("operations").path("items")
        operation.path("additionalProperties") shouldBe BooleanNode.FALSE
        operation.path("required").map { it.asText() } shouldBe listOf("operationId")
    }

    "schema defaults equal the Kotlin defaults" {
        val defaults = HttpApiConfig(spec = SpecConfig(inline = "openapi: 3.0.0"), baseUrl = "https://api.example.com")
        val properties = schema.path("properties")
        val spec = properties.path("spec").path("properties")
        spec.path("refreshMinutes").path("default").asInt() shouldBe defaults.spec.refreshMinutes
        spec.path("maxBytes").path("default").asLong() shouldBe defaults.spec.maxBytes
        spec.path("maxBytes").path("minimum").asLong() shouldBe SpecConfig.MIN_MAX_BYTES
        properties.path("maxTools").path("default").asInt() shouldBe defaults.maxTools
        properties.path("maxTools").path("maximum").asInt() shouldBe HttpApiConfig.MAX_TOOLS_UPPER_BOUND
        val auth = properties.path("auth").path("properties")
        auth.path("apiKeyIn").path("default").asText() shouldBe defaults.auth.apiKeyIn.name.lowercase()
        auth.path("apiKeyName").path("default").asText() shouldBe defaults.auth.apiKeyName
        properties.path("responseFormat").path("default").asText() shouldBe defaults.responseFormat.name.lowercase()
        properties.path("maxResponseChars").path("default").asInt() shouldBe defaults.maxResponseChars
        properties.path("maxResponseChars").path("minimum").asInt() shouldBe HttpApiConfig.MIN_RESPONSE_CHARS
        properties.path("timeoutSeconds").path("default").asInt() shouldBe defaults.timeoutSeconds
        properties.path("maxConcurrentCalls").path("default").asInt() shouldBe defaults.maxConcurrentCalls
        properties.path("allowMutations").path("default").asBoolean() shouldBe defaults.allowMutations
    }

    "defaultHeaders names every header the parser refuses" {
        val description = schema.path("properties").path("defaultHeaders").path("description").asText()
        listOf("Authorization", "Proxy-Authorization", "Cookie", "Host").forEach { description shouldContain it }
    }

    "the spec file property is titled for the admin form" {
        schema.path("properties").path("spec").path("properties").path("file").path("title").asText() shouldBe
            "Spec file"
    }

    "the parser accepts the lowercase enum values the schema advertises" {
        val config = HttpApiConfigParser.parse(
            json(
                """
                { "spec": { "inline": "openapi: 3.0.0" }, "baseUrl": "https://api.example.com",
                  "responseFormat": "yaml", "auth": { "apiKeyIn": "query" },
                  "operations": [ { "operationId": "x", "responseFormat": "json" } ] }
                """,
            ),
        )
        config.responseFormat.name shouldBe "YAML"
        config.auth.apiKeyIn.name shouldBe "QUERY"
        config.operations.single().responseFormat?.name shouldBe "JSON"
    }

    "every property has an admin-friendly title and description" {
        fun check(node: JsonNode, path: String) {
            node.path("properties").properties().forEach { (name, property) ->
                withClue("$path$name") {
                    property.path("title").isTextual shouldBe true
                    property.path("description").isTextual shouldBe true
                }
                check(property, "$path$name.")
                check(property.path("items"), "$path$name[].")
            }
        }
        check(schema, "")
    }
})
