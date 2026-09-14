package io.whozoss.agentos.plugins.http.testing

import io.whozoss.agentos.plugins.http.HttpApiRuntime
import io.whozoss.agentos.plugins.http.HttpApiTool
import io.whozoss.agentos.plugins.http.auth.AuthHeaderSpec
import io.whozoss.agentos.plugins.http.config.HttpApiConfig
import io.whozoss.agentos.plugins.http.config.OperationOverride
import io.whozoss.agentos.plugins.http.config.ResponseFormat
import io.whozoss.agentos.plugins.http.config.SpecConfig
import io.whozoss.agentos.plugins.http.net.OutboundUrlPolicy
import io.whozoss.agentos.plugins.http.openapi.CurationResult
import io.whozoss.agentos.plugins.http.openapi.OpenApiReader
import io.whozoss.agentos.plugins.http.openapi.OperationCurator
import io.whozoss.agentos.plugins.http.openapi.OperationDescriptor
import io.whozoss.agentos.sdk.tool.ToolContext
import kotlinx.coroutines.sync.Semaphore
import okhttp3.OkHttpClient
import java.util.UUID

/** Test OpenAPI document exercising path, query, JSON body and form body operations. */
object ExecutionFixture {

    // language=yaml
    val document: String = """
        openapi: 3.0.3
        info: { title: Items API, version: 2.1.0 }
        paths:
          /items:
            get:
              operationId: listItems
              summary: List items
              parameters:
                - { name: q, in: query, schema: { type: string } }
                - { name: tags, in: query, schema: { type: array, items: { type: string } } }
                - { name: limit, in: query, schema: { type: integer } }
                - { name: X-Request-Id, in: header, schema: { type: string } }
              responses: { '200': { description: Items } }
            post:
              operationId: createItem
              summary: Create an item
              requestBody:
                required: true
                content:
                  application/json:
                    schema: { type: object, properties: { name: { type: string } } }
              responses: { '201': { description: Created item } }
          /items/{id}:
            parameters:
              - { name: id, in: path, required: true, schema: { type: string } }
            get:
              operationId: showItem
              summary: Show an item
              responses: { '200': { description: The item } }
            put:
              operationId: updateItem
              summary: Update an item
              requestBody:
                content:
                  application/json:
                    schema: { type: object }
              responses: { '200': { description: Updated item } }
            delete:
              operationId: deleteItem
              summary: Delete an item
              responses: { '204': { description: Deleted } }
          /tenant:
            get:
              operationId: tenantInfo
              summary: Tenant info
              parameters:
                - { name: X-Tenant, in: header, required: true, schema: { type: string } }
              responses: { '200': { description: Tenant } }
          /token:
            post:
              operationId: createToken
              summary: Create a token
              requestBody:
                required: true
                content:
                  application/x-www-form-urlencoded:
                    schema: { type: object, properties: { grant_type: { type: string } } }
              responses: { '200': { description: Token } }
          /nested/{a}/{b}.json:
            get:
              operationId: nestedOp
              summary: Nested path
              parameters:
                - { name: a, in: path, required: true, schema: { type: string } }
                - { name: b, in: path, required: true, schema: { type: string } }
              responses: { '200': { description: Nested } }
    """.trimIndent()

    val policy = OutboundUrlPolicy(allowLoopbackForTests = true)

    fun context(): ToolContext =
        ToolContext(namespaceId = UUID.randomUUID(), userId = null, userExternalId = null, caseEvents = emptyList())

    fun config(
        baseUrl: String,
        maxResponseChars: Int = 500,
        timeoutSeconds: Int = 5,
        maxConcurrentCalls: Int = 4,
    ): HttpApiConfig =
        HttpApiConfig(
            spec = SpecConfig(inline = document),
            baseUrl = baseUrl,
            allowMutations = true,
            maxResponseChars = maxResponseChars,
            timeoutSeconds = timeoutSeconds,
            maxConcurrentCalls = maxConcurrentCalls,
            operations = listOf(
                OperationOverride(operationId = "showItem", keepPaths = listOf("item.id", "item.name")),
                OperationOverride(
                    operationId = "listItems",
                    responseFormat = ResponseFormat.YAML,
                    ignorePaths = listOf("items.secret"),
                ),
            ),
        )

    fun descriptors(config: HttpApiConfig, configName: String = "ITEMS"): List<OperationDescriptor> {
        val document = OpenApiReader.read(document, maxBytes = config.spec.maxBytes)
        return (OperationCurator.curate(document, config, configName) as CurationResult.Selected).operations
    }

    /** A runtime with its own semaphore sized to `maxConcurrentCalls`, or [semaphore] when given. */
    fun runtime(
        config: HttpApiConfig,
        client: OkHttpClient,
        authSpec: AuthHeaderSpec = AuthHeaderSpec.None,
        configName: String = "ITEMS",
        semaphore: Semaphore = Semaphore(config.maxConcurrentCalls),
    ): HttpApiRuntime =
        HttpApiRuntime(
            configName = configName,
            baseUrl = requireNotNull(config.baseUrl) { "fixture configs always set baseUrl" },
            authSpec = authSpec,
            defaultHeaders = config.defaultHeaders,
            timeoutSeconds = config.timeoutSeconds,
            semaphore = semaphore,
            urlPolicy = policy,
            client = client,
        )

    /** Tools of [config] keyed by operationId. */
    fun tools(config: HttpApiConfig, runtime: HttpApiRuntime): Map<String, HttpApiTool> =
        descriptors(config, runtime.configName).associate { checkNotNull(it.operationId) to HttpApiTool(it, runtime) }
}
