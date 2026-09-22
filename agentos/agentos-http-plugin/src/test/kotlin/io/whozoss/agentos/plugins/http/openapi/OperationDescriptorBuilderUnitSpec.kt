package io.whozoss.agentos.plugins.http.openapi

import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import io.whozoss.agentos.plugins.http.config.HttpApiConfig
import io.whozoss.agentos.plugins.http.config.OperationOverride
import io.whozoss.agentos.plugins.http.config.ResponseFormat
import io.whozoss.agentos.plugins.http.config.SpecConfig

class OperationDescriptorBuilderUnitSpec : StringSpec({
    isolationMode = IsolationMode.InstancePerLeaf

    val config = HttpApiConfig(spec = SpecConfig(inline = "openapi: 3.0.0"), baseUrl = "https://api.example.com")

    fun operation(
        method: HttpMethod = HttpMethod.GET,
        path: String = "/tickets/{id}",
        operationId: String? = "ShowTicket",
        summary: String? = "Show a ticket",
        description: String? = null,
        successDescription: String? = null,
        parameters: List<OpenApiParameter> = emptyList(),
        requestBody: OpenApiRequestBody? = null,
    ): OpenApiOperation =
        OpenApiOperation(
            operationId = operationId,
            method = method,
            path = path,
            summary = summary,
            description = description,
            tags = emptyList(),
            deprecated = false,
            parameters = parameters,
            requestBody = requestBody,
            unsupportedBody = false,
            successDescription = successDescription,
        )

    fun build(op: OpenApiOperation, cfg: HttpApiConfig = config): OperationDescriptor =
        OperationDescriptorBuilder.build(op, toolSuffix = "ShowTicket", config = cfg).descriptor

    "describes a GET operation with method, path and summary" {
        build(operation()).description shouldBe "GET /tickets/{id} — Show a ticket."
    }

    "prefixes non-GET operations with [WRITE]" {
        build(operation(method = HttpMethod.PUT)).description shouldStartWith "[WRITE] PUT /tickets/{id} — "
    }

    "appends the description after the summary and collapses markdown whitespace" {
        val op = operation(
            summary = "List all pets",
            description = "Returns a **paginated** list.\n\nUse `limit`\n  to bound it.",
        )
        build(op).description shouldBe
            "GET /tickets/{id} — List all pets. Returns a paginated list. Use limit to bound it."
    }

    "uses the description alone when there is no summary" {
        build(operation(summary = null, description = "Only description")).description shouldBe
            "GET /tickets/{id} — Only description"
    }

    "omits the dash when neither summary nor description exists" {
        build(operation(summary = null)).description shouldBe "GET /tickets/{id}"
    }

    "caps the summary and description part at 800 characters" {
        val description = build(operation(description = "x".repeat(2000), successDescription = "OK")).description
        val part = description.removePrefix("GET /tickets/{id} — ").substringBefore(" Returns: OK")
        part.length shouldBe 800
        description shouldEndWith " Returns: OK"
    }

    "appends the 2xx description" {
        build(operation(successDescription = "The ticket")).description shouldBe
            "GET /tickets/{id} — Show a ticket. Returns: The ticket"
    }

    "appends 'Returns only' when keepPaths is configured" {
        val override = OperationOverride(operationId = "ShowTicket", keepPaths = listOf("ticket.id", "ticket.subject"))
        val cfg = config.copy(operations = listOf(override))
        build(operation(), cfg).description shouldEndWith " Returns only: ticket.id, ticket.subject"
    }

    "an override description replaces the summary and description part" {
        val override = OperationOverride(operationId = "ShowTicket", description = "Custom text")
        val cfg = config.copy(operations = listOf(override))
        val description = build(operation(description = "spec text", successDescription = "OK"), cfg).description
        description shouldBe "GET /tickets/{id} — Custom text Returns: OK"
        description shouldNotContain "Show a ticket"
    }

    "resolves shaping from config defaults" {
        val shaping = build(operation()).shaping
        shaping shouldBe ResponseShaping(
            keepPaths = emptyList(),
            ignorePaths = emptyList(),
            responseFormat = ResponseFormat.JSON,
            maxResponseChars = HttpApiConfig.DEFAULT_MAX_RESPONSE_CHARS,
        )
    }

    "resolves shaping from the matching override" {
        val cfg = config.copy(
            responseFormat = ResponseFormat.YAML,
            operations = listOf(
                OperationOverride(operationId = "Other", maxResponseChars = 999),
                OperationOverride(
                    operationId = "ShowTicket",
                    ignorePaths = listOf("ticket.url"),
                    maxResponseChars = 1000,
                ),
            ),
        )
        val shaping = build(operation(), cfg).shaping
        shaping.ignorePaths shouldBe listOf("ticket.url")
        shaping.responseFormat shouldBe ResponseFormat.YAML
        shaping.maxResponseChars shouldBe 1000
    }

    fun parameter(name: String, location: ParameterLocation, required: Boolean, schema: String): OpenApiParameter =
        OpenApiParameter(
            name = name,
            location = location,
            required = required,
            description = null,
            schema = json(schema),
        )

    "carries method, path, suffix, parameters and body" {
        val op = operation(
            method = HttpMethod.PUT,
            parameters = listOf(
                parameter("id", ParameterLocation.PATH, required = true, schema = """{"type":"string"}"""),
                parameter("tags", ParameterLocation.QUERY, required = false, schema = """{"type":"array"}"""),
                parameter("X-Trace", ParameterLocation.HEADER, required = false, schema = "{}"),
            ),
            requestBody = OpenApiRequestBody("application/json", json("""{"type":"object"}"""), required = true),
        )
        val descriptor = build(op)
        descriptor.operationId shouldBe "ShowTicket"
        descriptor.method shouldBe HttpMethod.PUT
        descriptor.pathTemplate shouldBe "/tickets/{id}"
        descriptor.toolSuffix shouldBe "ShowTicket"
        descriptor.parameters shouldBe listOf(
            ParameterDescriptor(name = "id", location = ParameterLocation.PATH, required = true, isArray = false),
            ParameterDescriptor(name = "tags", location = ParameterLocation.QUERY, required = false, isArray = true),
            ParameterDescriptor(
                name = "X-Trace",
                location = ParameterLocation.HEADER,
                required = false,
                isArray = false,
            ),
        )
        descriptor.body.shouldNotBeNull() shouldBe BodyDescriptor(mediaType = "application/json", required = true)
        descriptor.inputSchema shouldContain "\"body\""
    }

    "detects an array parameter declared with a 3.1 type array" {
        val op = operation(
            parameters = listOf(
                parameter("ids", ParameterLocation.QUERY, required = false, schema = """{"type":["array","null"]}"""),
                parameter("name", ParameterLocation.QUERY, required = false, schema = """{"type":["string","null"]}"""),
            ),
        )
        build(op).parameters.map { it.name to it.isArray } shouldBe listOf("ids" to true, "name" to false)
    }
})
