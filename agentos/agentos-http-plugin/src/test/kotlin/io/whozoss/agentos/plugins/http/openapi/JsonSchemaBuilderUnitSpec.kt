package io.whozoss.agentos.plugins.http.openapi

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

class JsonSchemaBuilderUnitSpec : StringSpec({
    isolationMode = IsolationMode.InstancePerLeaf

    val mapper = jacksonObjectMapper()
    val maxBytes = 5L * 1024 * 1024

    fun operation(fixture: String, method: HttpMethod, path: String): OpenApiOperation =
        OpenApiReader.read(Fixtures.load(fixture), maxBytes).operations
            .single { it.method == method && it.path == path }

    fun schemaOf(operation: OpenApiOperation): JsonNode = mapper.readTree(JsonSchemaBuilder.build(operation).json)

    fun JsonNode.keys(): List<String> = fieldNames().asSequence().toList()

    fun bodyOperation(schema: String, required: Boolean = true): OpenApiOperation =
        OpenApiOperation(
            operationId = "op",
            method = HttpMethod.POST,
            path = "/x",
            summary = null,
            description = null,
            tags = emptyList(),
            deprecated = false,
            parameters = emptyList(),
            requestBody = OpenApiRequestBody(
                mediaType = "application/json",
                schema = mapper.readTree(schema),
                required = required,
            ),
            unsupportedBody = false,
            successDescription = null,
        )

    "emits an object root without a schema declaration" {
        val schema = schemaOf(operation("petstore-3.0.yaml", HttpMethod.GET, "/stores"))
        schema.get("type").asText() shouldBe "object"
        schema.get("properties").isObject shouldBe true
        schema.get("required").isArray shouldBe true
        schema.get("additionalProperties").asBoolean() shouldBe false
        schema.has("\$schema") shouldBe false
    }

    "path parameters are required, optional query parameters are not" {
        val schema = schemaOf(operation("petstore-3.0.yaml", HttpMethod.GET, "/pets/{petId}"))
        schema.get("required").map { it.asText() } shouldBe listOf("petId")
        schema.path("properties").path("petId").path("description").asText() shouldBe "operation-level description"
        val list = schemaOf(operation("petstore-3.0.yaml", HttpMethod.GET, "/pets"))
        list.get("required").size() shouldBe 0
        list.path("properties").keys() shouldContainExactlyInAnyOrder listOf("limit", "status", "tags")
    }

    "explicitly required query parameters are required" {
        val schema = schemaOf(operation("zendesk-excerpt.yaml", HttpMethod.GET, "/api/v2/search"))
        schema.get("required").map { it.asText() } shouldBe listOf("query")
    }

    "carries description, type, format, enum, default, minimum, maximum and items" {
        val props = schemaOf(operation("petstore-3.0.yaml", HttpMethod.GET, "/pets")).path("properties")
        val limit = props.path("limit")
        limit.path("type").asText() shouldBe "integer"
        limit.path("format").asText() shouldBe "int32"
        limit.path("minimum").asInt() shouldBe 1
        limit.path("maximum").asInt() shouldBe 100
        limit.path("default").asInt() shouldBe 20
        limit.path("description").asText() shouldBe "How many items to return at one time (max 100)"
        props.path("status").path("enum").map { it.asText() } shouldBe listOf("available", "pending", "sold")
        props.path("tags").path("items").path("type").asText() shouldBe "string"
    }

    "header parameters are exposed as HTTP header properties, cookie parameters are not" {
        val schema = schemaOf(operation("required-header-param.yaml", HttpMethod.GET, "/others"))
        schema.path("properties").keys() shouldBe listOf("X-Trace")
        schema.path("properties").path("X-Trace").path("description").asText() shouldBe "HTTP header X-Trace"
        schema.path("required").size() shouldBe 0
        val cookie = schemaOf(operation("required-header-param.yaml", HttpMethod.GET, "/session"))
        cookie.path("properties").keys() shouldBe emptyList()
    }

    "the request body is one required 'body' property, never flattened" {
        val schema = schemaOf(operation("petstore-3.0.yaml", HttpMethod.POST, "/pets"))
        schema.path("properties").keys() shouldBe listOf("body")
        schema.get("required").map { it.asText() } shouldBe listOf("body")
        val body = schema.path("properties").path("body")
        body.path("type").asText() shouldBe "object"
        body.path("properties").keys() shouldContainExactlyInAnyOrder listOf("name", "tag")
        body.path("required").map { it.asText() } shouldBe listOf("name")
    }

    "an optional request body is not required" {
        val schema = schemaOf(operation("petstore-3.0.yaml", HttpMethod.PUT, "/pets/{petId}"))
        schema.get("required").map { it.asText() } shouldBe listOf("petId")
        schema.path("properties").keys() shouldContainExactlyInAnyOrder listOf("petId", "body")
    }

    fun bodySchemaOf(fixture: String, method: HttpMethod, path: String): JsonNode =
        schemaOf(operation(fixture, method, path)).path("properties").path("body")

    "merges allOf of object schemas and removes readOnly properties" {
        val body = bodySchemaOf("petstore-3.0.yaml", HttpMethod.PUT, "/pets/{petId}")
        body.has("allOf") shouldBe false
        body.path("type").asText() shouldBe "object"
        body.path("properties").keys() shouldContainExactlyInAnyOrder listOf("name", "tag", "owner")
        body.path("required").map { it.asText() } shouldBe listOf("name")
    }

    "converts nullable to a type array" {
        val body = bodySchemaOf("petstore-3.0.yaml", HttpMethod.PUT, "/pets/{petId}")
        body.path("properties").path("tag").path("type").map { it.asText() } shouldBe listOf("string", "null")
        body.path("properties").path("tag").has("nullable") shouldBe false
    }

    "keeps 3.1 type arrays and oneOf as-is" {
        val schema = schemaOf(operation("sample-3.1.json", HttpMethod.GET, "/items"))
        schema.path("properties").path("cursor").path("type").map { it.asText() } shouldBe listOf("string", "null")
        val op = bodyOperation("""{ "oneOf": [ { "type": "string" }, { "type": "integer" } ] }""")
        schemaOf(op).path("properties").path("body").path("oneOf").size() shouldBe 2
    }

    "removes OAS-only keywords recursively" {
        val json = JsonSchemaBuilder.build(operation("petstore-3.0.yaml", HttpMethod.PUT, "/pets/{petId}")).json
        OpenApiSchemaConverter.OAS_ONLY_KEYWORDS.forEach { keyword -> json shouldNotContain "\"$keyword\"" }
        val json31 = JsonSchemaBuilder.build(operation("sample-3.1.json", HttpMethod.POST, "/items")).json
        json31 shouldNotContain "\"examples\""
    }

    "merges nested allOf compositions" {
        val op = bodyOperation(
            """
            { "allOf": [
                { "allOf": [ { "type": "object", "required": ["a"], "properties": { "a": { "type": "string" } } } ] },
                { "type": "object", "properties": { "b": { "type": "integer" } } }
            ] }
            """,
        )
        val body = schemaOf(op).path("properties").path("body")
        body.has("allOf") shouldBe false
        body.path("type").asText() shouldBe "object"
        body.path("properties").keys() shouldContainExactlyInAnyOrder listOf("a", "b")
        body.path("required").map { it.asText() } shouldBe listOf("a")
    }

    "reduces deep object schemas when the serialised schema exceeds the size limit" {
        val op = bodyOperation(mapper.writeValueAsString(DeepSchemas.deepProperties(250)))
        val result = JsonSchemaBuilder.build(op)
        result.reduced shouldBe true
        result.json.toByteArray(Charsets.UTF_8).size shouldBeLessThanOrEqual JsonSchemaBuilder.MAX_SCHEMA_BYTES
        val body = mapper.readTree(result.json).path("properties").path("body")
        val l4 = body.path("properties").path("p0").path("properties").path("l3").path("properties").path("l4")
        l4.path("type").asText() shouldBe "object"
        l4.path("description").asText() shouldBe "Leaf0"
        l4.has("properties") shouldBe false
    }

    "lowers the nesting threshold when collapsing level 3 is not enough" {
        val op = bodyOperation(mapper.writeValueAsString(DeepSchemas.deepProperties(350)))
        val result = JsonSchemaBuilder.build(op)
        result.reduced shouldBe true
        result.json.toByteArray(Charsets.UTF_8).size shouldBeLessThanOrEqual JsonSchemaBuilder.MAX_SCHEMA_BYTES
        val body = mapper.readTree(result.json).path("properties").path("body")
        val l3 = body.path("properties").path("p0").path("properties").path("l3")
        l3.path("description").asText() shouldBe "l3"
        l3.has("properties") shouldBe false
    }

    "reduces deep object schemas nested inside oneOf members and stays under the size limit" {
        val op = bodyOperation(mapper.writeValueAsString(DeepSchemas.deepOneOf(300)))
        val result = JsonSchemaBuilder.build(op)
        result.reduced shouldBe true
        result.json.toByteArray(Charsets.UTF_8).size shouldBeLessThanOrEqual JsonSchemaBuilder.MAX_SCHEMA_BYTES
        val members = mapper.readTree(result.json).path("properties").path("body").path("oneOf")
        members.size() shouldBe 300
        val l4 = members.path(0).path("properties").path("l3").path("properties").path("l4")
        l4.has("properties") shouldBe false
    }

    "rejects a parameter named 'body' when a request body is declared" {
        val op = bodyOperation("""{ "type": "object" }""").copy(
            parameters = listOf(
                OpenApiParameter(
                    name = JsonSchemaBuilder.BODY_PROPERTY,
                    location = ParameterLocation.QUERY,
                    required = false,
                    description = null,
                    schema = json("""{"type":"string"}"""),
                ),
            ),
        )
        shouldThrow<IllegalArgumentException> { JsonSchemaBuilder.build(op) }.message shouldContain "body"
    }

    "does not reduce a small schema" {
        JsonSchemaBuilder.build(operation("petstore-3.0.yaml", HttpMethod.PUT, "/pets/{petId}")).reduced shouldBe false
    }

    "output is valid JSON for every petstore operation" {
        OpenApiReader.read(Fixtures.load("petstore-3.0.yaml"), maxBytes).operations.forEach { op ->
            mapper.readTree(JsonSchemaBuilder.build(op).json).isObject shouldBe true
        }
    }
})
