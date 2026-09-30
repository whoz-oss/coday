package io.whozoss.agentos.plugins.http.openapi

import com.fasterxml.jackson.databind.JsonNode
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.whozoss.agentos.plugins.http.config.ApiKeyPlacement
import io.whozoss.agentos.plugins.http.config.AuthConfig
import io.whozoss.agentos.plugins.http.config.HttpApiConfig
import io.whozoss.agentos.plugins.http.config.SpecConfig

class OperationCuratorUnitSpec : StringSpec({
    isolationMode = IsolationMode.InstancePerLeaf

    val maxBytes = 5L * 1024 * 1024
    val baseConfig = HttpApiConfig(spec = SpecConfig(inline = "openapi: 3.0.0"), baseUrl = "https://api.example.com")

    fun document(fixture: String): OpenApiDocument = OpenApiReader.read(Fixtures.load(fixture), maxBytes)

    fun curate(fixture: String, config: HttpApiConfig = baseConfig): CurationResult =
        OperationCurator.curate(document(fixture), config, configName = "cfg")

    fun selected(fixture: String, config: HttpApiConfig = baseConfig): CurationResult.Selected =
        curate(fixture, config).shouldBeInstanceOf<CurationResult.Selected>()

    fun CurationResult.Selected.keys(): List<String> = operations.map { "${it.method} ${it.pathTemplate}" }

    fun jsonBody(schema: JsonNode): OpenApiRequestBody =
        OpenApiRequestBody(mediaType = "application/json", schema = schema, required = true)

    fun operation(
        method: HttpMethod,
        path: String,
        parameters: List<OpenApiParameter> = emptyList(),
        requestBody: OpenApiRequestBody? = null,
    ): OpenApiOperation =
        OpenApiOperation(
            operationId = null,
            method = method,
            path = path,
            summary = null,
            description = null,
            tags = emptyList(),
            deprecated = false,
            parameters = parameters,
            requestBody = requestBody,
            unsupportedBody = false,
            successDescription = null,
        )

    "keeps only GET operations by default and drops deprecated ones" {
        selected("petstore-3.0.yaml").keys() shouldBe listOf("GET /pets", "GET /pets/{petId}", "GET /stores")
    }

    "keeps mutations when allowMutations is true" {
        selected("petstore-3.0.yaml", baseConfig.copy(allowMutations = true)).keys() shouldBe listOf(
            "GET /pets",
            "POST /pets",
            "DELETE /pets/{petId}",
            "GET /pets/{petId}",
            "PUT /pets/{petId}",
            "GET /stores",
        )
    }

    "drops an operation with an unsupported body and reports it" {
        val result = selected("multipart.yaml", baseConfig.copy(allowMutations = true))
        result.keys() shouldBe listOf("GET /ping")
        result.warnings.single().operationKey shouldBe "POST /upload"
        result.warnings.single().reason shouldContain "media type"
    }

    "drops operations with a required cookie parameter, not those with a required header, and reports them" {
        val result = selected("required-header-param.yaml")
        result.keys() shouldBe listOf("GET /others", "GET /things")
        result.warnings.map { it.operationKey } shouldBe listOf("GET /session")
        result.warnings.single().reason shouldContain "cookie"
    }

    "exposes header parameters as tool arguments described as HTTP headers" {
        val tenants = selected("header-params.yaml").operations.single { it.operationId == "listTenants" }
        tenants.parameters.map { it.name to it.location } shouldBe listOf(
            "X-Tenant" to ParameterLocation.HEADER,
            "X-Trace" to ParameterLocation.HEADER,
            "q" to ParameterLocation.QUERY,
        )
        tenants.parameters.map { it.required } shouldBe listOf(true, false, false)
        val schema = json(tenants.inputSchema)
        schema.path("required").map { it.asText() } shouldBe listOf("X-Tenant")
        schema.path("properties").path("X-Tenant").path("description").asText() shouldBe "HTTP header X-Tenant"
        schema.path("properties").path("X-Trace").path("description").asText() shouldBe "HTTP header X-Trace. Trace id"
        schema.path("properties").path("X-Trace").path("type").asText() shouldBe "string"
    }

    "ignores an optional reserved header with a warning and keeps the operation" {
        val result = selected("header-params.yaml")
        val optional = result.operations.single { it.operationId == "authOptional" }
        optional.parameters.map { it.name } shouldBe listOf("X-Custom-Key")
        val warning = result.warnings.single { it.operationKey == "GET /auth-optional" }
        warning.reason shouldContain "'Authorization'"
        warning.reason shouldContain "reserved"
    }

    "reserves the effective API key header as well" {
        val auth = AuthConfig(apiKeyIn = ApiKeyPlacement.HEADER, apiKeyName = "x-custom-key")
        val result = OperationCurator
            .curate(document("header-params.yaml"), baseConfig, configName = "cfg", auth = auth)
            .shouldBeInstanceOf<CurationResult.Selected>()
        result.operations.single { it.operationId == "authOptional" }.parameters shouldBe emptyList()
        result.warnings.filter { it.operationKey == "GET /auth-optional" }.map { it.reason }
            .single { "X-Custom-Key" in it } shouldContain "reserved"
    }

    "skips an operation with a required reserved header and reports it" {
        val result = selected("header-params.yaml")
        result.operations.none { it.operationId == "authRequired" } shouldBe true
        val warning = result.warnings.single { it.operationKey == "GET /auth-required" }
        warning.reason shouldContain "required header parameter 'Authorization'"
        warning.reason shouldContain "reserved"
    }

    "reserves the defaultHeaders names: an optional one is ignored, a required one skips the operation" {
        val optional = selected("header-params.yaml", baseConfig.copy(defaultHeaders = mapOf("x-trace" to "t1")))
        optional.operations.single { it.operationId == "listTenants" }.parameters.map { it.name } shouldBe
            listOf("X-Tenant", "q")
        optional.warnings.single { it.operationKey == "GET /tenants" }.reason shouldBe
            "header parameter 'X-Trace' ignored: is set by 'defaultHeaders' and cannot be changed by the agent"
        val required = selected("header-params.yaml", baseConfig.copy(defaultHeaders = mapOf("X-Tenant" to "acme")))
        required.operations.none { it.operationId == "listTenants" } shouldBe true
        required.warnings.single { it.operationKey == "GET /tenants" }.reason shouldBe
            "required header parameter 'X-Tenant' is set by 'defaultHeaders' and cannot be changed by the agent"
    }

    "ignores a header parameter whose name a path or query parameter already uses" {
        val result = selected("header-params.yaml")
        val duplicate = result.operations.single { it.operationId == "duplicateName" }
        duplicate.parameters.map { it.name to it.location } shouldBe listOf("id" to ParameterLocation.PATH)
        result.warnings.single { it.operationKey == "GET /dup/{id}" }.reason shouldContain "already used"
    }

    "ignores a header parameter whose name is not an HTTP token, skipping the operation when it is required" {
        val result = selected("header-params.yaml")
        result.operations.single { it.operationId == "badNames" }.parameters.map { it.name } shouldBe listOf("X-Ok")
        result.warnings.single { it.operationKey == "GET /bad-names" }.reason shouldBe
            "header parameter 'X Tenant' ignored: is not a valid HTTP header name"
        result.operations.none { it.operationId == "badRequired" } shouldBe true
        result.warnings.single { it.operationKey == "GET /bad-required" }.reason shouldBe
            "required header parameter 'Café' is not a valid HTTP header name"
    }

    "reports operations skipped by the reader" {
        val result = selected("remote-ref.yaml")
        result.keys() shouldBe listOf("GET /local")
        result.warnings.map { it.operationKey } shouldContainExactlyInAnyOrder listOf("GET /remote", "GET /missing")
    }

    "filters by tag" {
        val config = baseConfig.copy(allowMutations = true, includeTags = listOf("admin"))
        selected("petstore-3.0.yaml", config).keys() shouldBe listOf("DELETE /pets/{petId}", "PUT /pets/{petId}")
    }

    "filters by path prefix" {
        val config = baseConfig.copy(includePathPrefixes = listOf("/api/v2/tickets"))
        selected("zendesk-excerpt.yaml", config).keys() shouldBe listOf(
            "GET /api/v2/tickets",
            "GET /api/v2/tickets/{ticket_id}",
            "GET /api/v2/tickets/{ticket_id}/comments",
        )
    }

    "filters by operationId glob" {
        val included = selected("zendesk-excerpt.yaml", baseConfig.copy(includeOperations = listOf("List*")))
        included.operations.map { it.operationId } shouldBe
            listOf("ListSearchResults", "ListTickets", "ListTicketComments")
        val excluded = selected("zendesk-excerpt.yaml", baseConfig.copy(excludeOperations = listOf("List???????")))
        excluded.operations.map { it.operationId } shouldBe
            listOf("ListSearchResults", "ShowTicket", "ListTicketComments")
    }

    "includeOperations drops operations without an operationId" {
        val included = selected("minimal-handwritten.yaml", baseConfig.copy(includeOperations = listOf("*")))
        included.operations shouldBe emptyList()
        val excluded = selected("minimal-handwritten.yaml", baseConfig.copy(excludeOperations = listOf("*")))
        excluded.operations.size shouldBe 2
    }

    "sorts by path then method" {
        selected("petstore-3.0.yaml", baseConfig.copy(allowMutations = true)).keys().take(3) shouldBe
            listOf("GET /pets", "POST /pets", "DELETE /pets/{petId}")
    }

    "returns TooManyOperations with the post-filter count when maxTools is exceeded" {
        // zendesk-excerpt declares 5 operations; the PUT is filtered out first (allowMutations = false)
        val result = curate("zendesk-excerpt.yaml", baseConfig.copy(maxTools = 2))
        result.shouldBeInstanceOf<CurationResult.TooManyOperations>() shouldBe
            CurationResult.TooManyOperations(count = 4, max = 2, warnings = emptyList())
    }

    "assigns unique tool suffixes" {
        val result = selected("minimal-handwritten.yaml")
        result.operations.map { it.toolSuffix } shouldBe listOf("get_tickets", "get_tickets_by_ticket_id")
    }

    "emits no warning when no schema is reduced" {
        val result = selected("petstore-3.0.yaml")
        result.warnings shouldBe emptyList()
    }

    "reports a reduced input schema" {
        val document = OpenApiDocument(
            title = null,
            version = null,
            serverUrl = null,
            apiKeyScheme = null,
            operations = listOf(
                operation(HttpMethod.POST, "/x", requestBody = jsonBody(DeepSchemas.deepProperties(400))),
            ),
            skipped = emptyList(),
        )
        val result = OperationCurator.curate(document, baseConfig.copy(allowMutations = true), configName = "cfg")
            .shouldBeInstanceOf<CurationResult.Selected>()
        result.operations.map { it.pathTemplate } shouldBe listOf("/x")
        result.warnings.single().operationKey shouldBe "POST /x"
        result.warnings.single().reason shouldContain "input schema reduced"
    }

    "drops an operation whose parameter collides with the body property and reports it" {
        val colliding = OpenApiParameter(
            name = JsonSchemaBuilder.BODY_PROPERTY,
            location = ParameterLocation.QUERY,
            required = false,
            description = null,
            schema = json("""{"type":"string"}"""),
        )
        val document = OpenApiDocument(
            title = null,
            version = null,
            serverUrl = null,
            apiKeyScheme = null,
            operations = listOf(
                operation(
                    HttpMethod.POST,
                    "/x",
                    parameters = listOf(colliding),
                    requestBody = jsonBody(json("""{"type":"object"}""")),
                ),
                operation(HttpMethod.GET, "/y", parameters = listOf(colliding)),
            ),
            skipped = emptyList(),
        )
        val result = OperationCurator.curate(document, baseConfig.copy(allowMutations = true), configName = "cfg")
            .shouldBeInstanceOf<CurationResult.Selected>()
        result.keys() shouldBe listOf("GET /y")
        result.warnings.single().operationKey shouldBe "POST /x"
        result.warnings.single().reason shouldContain "'body'"
    }
})
