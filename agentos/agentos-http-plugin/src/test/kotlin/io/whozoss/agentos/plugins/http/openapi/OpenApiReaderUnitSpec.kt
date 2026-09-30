package io.whozoss.agentos.plugins.http.openapi

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.whozoss.agentos.plugins.http.config.ApiKeyPlacement

class OpenApiReaderUnitSpec : StringSpec({
    isolationMode = IsolationMode.InstancePerLeaf

    val maxBytes = 5L * 1024 * 1024

    fun read(fixture: String): OpenApiDocument = OpenApiReader.read(Fixtures.load(fixture), maxBytes)

    fun OpenApiDocument.operation(method: HttpMethod, path: String): OpenApiOperation =
        operations.single { it.method == method && it.path == path }

    "reads info and servers from a YAML document" {
        val doc = read("petstore-3.0.yaml")
        doc.title shouldBe "Petstore"
        doc.version shouldBe "1.0.0"
        doc.serverUrl shouldBe "https://petstore.example.com/v1"
    }

    "reads a JSON document and leaves servers null when absent" {
        val doc = read("sample-3.1.json")
        doc.title shouldBe "Sample 3.1"
        doc.serverUrl shouldBe null
        doc.operations.map { it.operationId } shouldContainExactly listOf("listItems", "createItem")
    }

    "substitutes the defaults of server variables into the server URL" {
        val text = """
            openapi: 3.0.0
            info: { title: t, version: "1" }
            servers:
              - url: https://{region}.example.com/{basePath}
                variables:
                  region: { default: eu, enum: [eu, us] }
                  basePath: { default: v2 }
            paths: {}
        """.trimIndent()
        OpenApiReader.read(text, maxBytes).serverUrl shouldBe "https://eu.example.com/v2"
    }

    "leaves a server variable without default in place" {
        val text = """
            openapi: 3.0.0
            info: { title: t, version: "1" }
            servers:
              - url: https://{region}.example.com
                variables:
                  region: { enum: [eu, us] }
            paths: {}
        """.trimIndent()
        OpenApiReader.read(text, maxBytes).serverUrl shouldBe "https://{region}.example.com"
    }

    "reads the apiKey scheme named by the document security requirement, skipping cookie and other types" {
        read("api-key-scheme.yaml").apiKeyScheme shouldBe
            ApiKeyScheme(placement = ApiKeyPlacement.QUERY, name = "api_key")
    }

    "falls back to the first usable apiKey scheme in document order when the security requirement names none" {
        val bearerRequired = """
            openapi: 3.0.0
            info: { title: t, version: "1" }
            components:
              securitySchemes:
                Bearer: { type: http, scheme: bearer }
                Session: { type: apiKey, in: cookie, name: session }
                HeaderKey: { type: apiKey, in: header, name: X-Api-Key }
                QueryKey: { type: apiKey, in: query, name: api_key }
            security:
              - Bearer: []
              - Session: []
            paths: {}
        """.trimIndent()
        OpenApiReader.read(bearerRequired, maxBytes).apiKeyScheme shouldBe
            ApiKeyScheme(placement = ApiKeyPlacement.HEADER, name = "X-Api-Key")
        val noRequirement = bearerRequired.substringBefore("security:") + "paths: {}\n"
        OpenApiReader.read(noRequirement, maxBytes).apiKeyScheme shouldBe
            ApiKeyScheme(placement = ApiKeyPlacement.HEADER, name = "X-Api-Key")
    }

    "has no apiKey scheme when the document declares none usable" {
        read("petstore-3.0.yaml").apiKeyScheme shouldBe null
        val cookieOnly = """
            openapi: 3.0.0
            info: { title: t, version: "1" }
            components:
              securitySchemes:
                Session: { type: apiKey, in: cookie, name: session }
                Unnamed: { type: apiKey, in: header }
            paths: {}
        """.trimIndent()
        OpenApiReader.read(cookieOnly, maxBytes).apiKeyScheme shouldBe null
    }

    "rejects a Swagger 2.0 document with an explicit message" {
        val ex = shouldThrow<IllegalArgumentException> { read("swagger-2.0.yaml") }
        ex.message shouldContain "only OpenAPI 3.x is supported"
    }

    "rejects a document without an openapi field" {
        shouldThrow<IllegalArgumentException> { OpenApiReader.read("info:\n  title: x\n", maxBytes) }
    }

    "rejects a document larger than maxBytes" {
        val text = Fixtures.load("minimal-handwritten.yaml")
        val ex = shouldThrow<IllegalArgumentException> { OpenApiReader.read(text, maxBytes = 10) }
        ex.message shouldContain "larger than the allowed 10 bytes"
    }

    "rejects malformed YAML and JSON" {
        shouldThrow<IllegalArgumentException> { OpenApiReader.read("openapi: 3.0.0\npaths: [", maxBytes) }
        shouldThrow<IllegalArgumentException> { OpenApiReader.read("{ \"openapi\": ", maxBytes) }
    }

    "a parse failure names the problem on one line and never echoes the offending source" {
        val sentinel = "SUPER-SECRET-do-not-leak"
        val malformed = "openapi: 3.0.0\ninfo: { title: t, version: \"1\" }\npassword: [$sentinel\n"
        val ex = shouldThrow<IllegalArgumentException> { OpenApiReader.read(malformed, maxBytes) }
        ex.message shouldContain "OpenAPI document cannot be parsed"
        ex.message shouldNotContain sentinel
        ex.message shouldNotContain "\n"
    }

    "walks only get/post/put/patch/delete and ignores vendor extension paths" {
        val doc = read("petstore-3.0.yaml")
        doc.operations.map { "${it.method} ${it.path}" } shouldContainExactlyInAnyOrder listOf(
            "GET /pets",
            "POST /pets",
            "GET /pets/{petId}",
            "PUT /pets/{petId}",
            "DELETE /pets/{petId}",
            "GET /pets/{petId}/legacy",
            "GET /stores",
        )
    }

    "collects summary, description, tags and deprecated" {
        val doc = read("petstore-3.0.yaml")
        val list = doc.operation(HttpMethod.GET, "/pets")
        list.operationId shouldBe "listPets"
        list.summary shouldBe "List all pets"
        list.description.shouldNotBeNull() shouldContain "paginated"
        list.tags shouldBe listOf("pets")
        list.deprecated shouldBe false
        list.successDescription shouldBe "A paged array of pets"
        doc.operation(HttpMethod.GET, "/pets/{petId}/legacy").deprecated shouldBe true
        doc.operation(HttpMethod.PUT, "/pets/{petId}").tags shouldBe listOf("pets", "admin")
    }

    "merges path-level and operation-level parameters with operation precedence" {
        val doc = read("petstore-3.0.yaml")
        val show = doc.operation(HttpMethod.GET, "/pets/{petId}")
        show.parameters.map { it.name } shouldBe listOf("petId")
        show.parameters.single().description shouldBe "operation-level description"
        show.parameters.single().location shouldBe ParameterLocation.PATH
        show.parameters.single().required shouldBe true
        val delete = doc.operation(HttpMethod.DELETE, "/pets/{petId}")
        delete.parameters.single().description shouldBe "path-level description"
    }

    "captures parameter schemas with query location and required flag" {
        val doc = read("petstore-3.0.yaml")
        val limit = doc.operation(HttpMethod.GET, "/pets").parameters.single { it.name == "limit" }
        limit.location shouldBe ParameterLocation.QUERY
        limit.required shouldBe false
        limit.schema.get("type").asText() shouldBe "integer"
        limit.schema.get("maximum").asInt() shouldBe 100
    }

    "inlines local refs in parameters and bodies" {
        val doc = read("zendesk-excerpt.yaml")
        val show = doc.operation(HttpMethod.GET, "/api/v2/tickets/{ticket_id}")
        show.parameters.single().name shouldBe "ticket_id"
        show.parameters.single().schema.get("type").asText() shouldBe "integer"
        val update = doc.operation(HttpMethod.PUT, "/api/v2/tickets/{ticket_id}")
        val bodySchema = update.requestBody.shouldNotBeNull().schema
        bodySchema.path("properties").path("ticket").path("type").asText() shouldBe "object"
    }

    "inlines nested refs and allOf members" {
        val doc = read("petstore-3.0.yaml")
        val body = doc.operation(HttpMethod.PUT, "/pets/{petId}").requestBody.shouldNotBeNull()
        val allOf = body.schema.get("allOf")
        allOf.get(0).path("properties").path("name").path("type").asText() shouldBe "string"
        val owner = allOf.get(1).path("properties").path("owner")
        owner.path("properties").path("email").path("type").asText() shouldBe "string"
    }

    "replaces a cyclic ref by a recursive placeholder" {
        val doc = read("cyclic-refs.yaml")
        val body = doc.operation(HttpMethod.POST, "/tree").requestBody.shouldNotBeNull()
        val children = body.schema.path("properties").path("children").path("items")
        children.path("type").asText() shouldBe "object"
        children.path("description").asText() shouldBe "Node (recursive)"
        body.schema.path("properties").path("parent").path("description").asText() shouldBe "Node (recursive)"
        doc.skipped shouldBe emptyList()
    }

    "skips an operation with a remote ref without fetching it" {
        val doc = read("remote-ref.yaml")
        doc.operations.map { it.operationId } shouldBe listOf("getLocal")
        doc.skipped.map { it.key } shouldContainExactlyInAnyOrder listOf("GET /remote", "GET /missing")
        doc.skipped.single { it.key == "GET /remote" }.reason shouldContain
            "https://example.com/schemas/common.yaml#/Filter"
        doc.skipped.single { it.key == "GET /missing" }.reason shouldContain "#/components/schemas/DoesNotExist"
    }

    "does not skip an operation whose only external ref is in a response" {
        val text = """
            openapi: 3.0.0
            info: { title: t, version: "1" }
            paths:
              /things:
                get:
                  operationId: listThings
                  responses:
                    '200':
                      description: Things
                      content:
                        application/json:
                          schema:
                            ${'$'}ref: 'https://example.com/common.yaml#/Thing'
        """.trimIndent()
        val doc = OpenApiReader.read(text, maxBytes)
        doc.skipped shouldBe emptyList()
        doc.operations.single().operationId shouldBe "listThings"
        doc.operations.single().successDescription shouldBe "Things"
    }

    "reads the description of a response declared by a local ref, and leaves it null when external" {
        val text = """
            openapi: 3.0.0
            info: { title: t, version: "1" }
            paths:
              /local:
                get:
                  responses:
                    '200':
                      ${'$'}ref: '#/components/responses/Ok'
              /remote:
                get:
                  responses:
                    '200':
                      ${'$'}ref: 'https://example.com/common.yaml#/Ok'
            components:
              responses:
                Ok:
                  description: Everything is fine
        """.trimIndent()
        val doc = OpenApiReader.read(text, maxBytes)
        doc.skipped shouldBe emptyList()
        doc.operation(HttpMethod.GET, "/local").successDescription shouldBe "Everything is fine"
        doc.operation(HttpMethod.GET, "/remote").successDescription shouldBe null
    }

    "inlines a request body declared by a local ref" {
        val text = """
            openapi: 3.0.0
            info: { title: t, version: "1" }
            paths:
              /things:
                post:
                  requestBody:
                    ${'$'}ref: '#/components/requestBodies/Thing'
                  responses:
                    '201':
                      description: Created
            components:
              requestBodies:
                Thing:
                  required: true
                  content:
                    application/json:
                      schema:
                        type: object
        """.trimIndent()
        val doc = OpenApiReader.read(text, maxBytes)
        val body = doc.operation(HttpMethod.POST, "/things").requestBody.shouldNotBeNull()
        body.required shouldBe true
        body.schema.path("type").asText() shouldBe "object"
    }

    "preserves 3.1 type arrays" {
        val doc = read("sample-3.1.json")
        val cursor = doc.operation(HttpMethod.GET, "/items").parameters.single()
        cursor.schema.get("type").isArray shouldBe true
        cursor.schema.get("type").map { it.asText() } shouldBe listOf("string", "null")
    }

    "captures a JSON request body with its required flag" {
        val doc = read("petstore-3.0.yaml")
        val create = doc.operation(HttpMethod.POST, "/pets").requestBody.shouldNotBeNull()
        create.mediaType shouldBe "application/json"
        create.required shouldBe true
        create.schema.path("required").map { it.asText() } shouldBe listOf("name")
        doc.operation(HttpMethod.PUT, "/pets/{petId}").requestBody.shouldNotBeNull().required shouldBe false
        doc.operation(HttpMethod.GET, "/pets").requestBody shouldBe null
    }

    "captures a form-urlencoded request body" {
        val doc = read("form-urlencoded.yaml")
        val op = doc.operation(HttpMethod.POST, "/token")
        op.requestBody.shouldNotBeNull().mediaType shouldBe "application/x-www-form-urlencoded"
        op.unsupportedBody shouldBe false
    }

    "marks a multipart body as unsupported" {
        val doc = read("multipart.yaml")
        val op = doc.operation(HttpMethod.POST, "/upload")
        op.requestBody shouldBe null
        op.unsupportedBody shouldBe true
        doc.operation(HttpMethod.GET, "/ping").unsupportedBody shouldBe false
    }

    "reads header and cookie parameters" {
        val doc = read("required-header-param.yaml")
        doc.operation(HttpMethod.GET, "/things").parameters.single().location shouldBe ParameterLocation.HEADER
        doc.operation(HttpMethod.GET, "/session").parameters.single().location shouldBe ParameterLocation.COOKIE
    }

    "reads operations without operationId" {
        val doc = read("minimal-handwritten.yaml")
        doc.operations.map { it.operationId } shouldBe listOf(null, null)
        doc.operations.map { it.path } shouldBe listOf("/tickets", "/tickets/{ticket_id}")
    }
})
