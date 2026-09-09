package io.whozoss.agentos.integrationConfig

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.whozoss.agentos.auth.CredentialProviderFactory
import io.whozoss.agentos.exception.UnprocessableEntityException
import io.whozoss.agentos.sdk.auth.CredentialProvider
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.sdk.tool.ConfirmationMode
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolExecutionResult
import io.whozoss.agentos.sdk.tool.ToolPlugin
import io.whozoss.agentos.tool.ToolRegistryService
import io.whozoss.agentos.user.User
import kotlinx.coroutines.delay
import java.util.UUID

/**
 * Unit tests for [IntegrationConfigToolPreviewService]: plugin lookup, tool mapping, failure
 * isolation (a failing `provideTools` becomes an error message, a slow or failing
 * `describeNamespace` becomes a null line) and the `ToolContext` handed to the plugin.
 */
class IntegrationConfigToolPreviewServiceUnitSpec : StringSpec() {
    private val toolRegistryService: ToolRegistryService = mockk()
    private val credentialProviderFactory: CredentialProviderFactory = mockk()
    private val service =
        IntegrationConfigToolPreviewService(
            toolRegistryService = toolRegistryService,
            credentialProviderFactory = credentialProviderFactory,
            integrationsProperties = IntegrationsProperties(previewDescribeNamespaceTimeoutMs = 100),
        )

    private val namespaceId: UUID = UUID.randomUUID()
    private val user =
        User(
            metadata = EntityMetadata(id = UUID.randomUUID()),
            externalId = "alice@example.com",
            email = "alice@example.com",
        )
    private val parameters: JsonNode = JsonNodeFactory.instance.objectNode().put("url", "https://mcp.example.com")

    private fun config(
        authSettingName: String? = null,
        integrationType: String = "MCP_HTTP",
    ) = IntegrationConfig(
        metadata = EntityMetadata(id = UUID.randomUUID()),
        namespaceId = namespaceId,
        userId = null,
        name = "MCP_PROD",
        integrationType = integrationType,
        parameters = parameters,
        authSettingName = authSettingName,
    )

    private fun tool(
        name: String,
        confirmationMode: ConfirmationMode = ConfirmationMode.NONE,
    ): StandardTool<Nothing> =
        object : StandardTool<Nothing> {
            override val name = name
            override val description = "Description of $name"
            override val inputSchema = """{"type":"object","properties":{"id":{"type":"string"}}}"""
            override val version = "1.0.0"
            override val paramType: Class<Nothing>? = null

            override suspend fun execute(
                input: Nothing?,
                context: ToolContext,
            ): ToolExecutionResult = ToolExecutionResult.success(name)

            override suspend fun getConfirmationMode(
                argsJson: String?,
                context: ToolContext?,
            ): ConfirmationMode = confirmationMode
        }

    /** Fake plugin recording the contexts it receives; [describe] may suspend or throw. */
    private class RecordingPlugin(
        private val tools: () -> List<StandardTool<*>>,
        private val describe: suspend (ToolContext?) -> String? = { "namespace line" },
    ) : ToolPlugin {
        var provideContext: ToolContext? = null
        var describeContext: ToolContext? = null
        var providedConfig: JsonNode? = null
        var providedConfigName: String? = null
        override val integrationType = "MCP_HTTP"
        override val configSchema: JsonNode? = null

        override fun provideTools(
            config: JsonNode?,
            configName: String?,
            context: ToolContext?,
        ): List<StandardTool<*>> {
            providedConfig = config
            providedConfigName = configName
            provideContext = context
            return tools()
        }

        override suspend fun describeNamespace(
            config: JsonNode?,
            configName: String?,
            context: ToolContext?,
        ): String? {
            describeContext = context
            return describe(context)
        }
    }

    private fun <T : ToolPlugin> registered(plugin: T): T {
        every { toolRegistryService.findPlugin("MCP_HTTP") } returns plugin
        return plugin
    }

    init {
        "preview throws 422 when no plugin is loaded for the integration type" {
            every { toolRegistryService.findPlugin("UNKNOWN") } returns null

            val exception =
                shouldThrow<UnprocessableEntityException> {
                    service.preview(config(integrationType = "UNKNOWN"), namespaceId, user)
                }

            exception.message.shouldNotBeNull() shouldContain "UNKNOWN"
        }

        "preview maps every tool to name, description, input schema and confirmation mode" {
            val plugin =
                registered(
                    RecordingPlugin(
                        tools = {
                            listOf(
                                tool("MCP_PROD__ListTickets"),
                                tool("MCP_PROD__UpdateTicket", ConfirmationMode.EVERY_TIME),
                            )
                        },
                    ),
                )

            val preview = service.preview(config(), namespaceId, user)

            preview.integrationType shouldBe "MCP_HTTP"
            preview.configName shouldBe "MCP_PROD"
            preview.error.shouldBeNull()
            preview.namespaceDescription shouldBe "namespace line"
            preview.tools.map { it.name } shouldBe listOf("MCP_PROD__ListTickets", "MCP_PROD__UpdateTicket")
            preview.tools[0].description shouldBe "Description of MCP_PROD__ListTickets"
            preview.tools[0].inputSchema shouldContain "\"properties\""
            preview.tools[0].confirmationMode shouldBe ConfirmationMode.NONE
            preview.tools[1].confirmationMode shouldBe ConfirmationMode.EVERY_TIME
            plugin.providedConfig shouldBe parameters
            plugin.providedConfigName shouldBe "MCP_PROD"
        }

        "preview reports a provideTools failure as an error message with no tools and no stack trace" {
            registered(RecordingPlugin(tools = { throw IllegalStateException("MCP server unreachable") }))

            val preview = service.preview(config(), namespaceId, user)

            preview.tools.shouldBeEmpty()
            preview.error shouldBe "IllegalStateException: MCP server unreachable"
            // The namespace line is still attempted: a broken tool set must not hide the plugin's own diagnosis.
            preview.namespaceDescription shouldBe "namespace line"
        }

        "preview renders a message-less provideTools failure without a null literal" {
            registered(RecordingPlugin(tools = { throw IllegalStateException() }))

            val preview = service.preview(config(), namespaceId, user)

            preview.tools.shouldBeEmpty()
            preview.error shouldBe "IllegalStateException: no message"
        }

        "preview names an anonymous provideTools failure by its binary class name" {
            val anonymous = object : RuntimeException("MCP handshake rejected") {}
            registered(RecordingPlugin(tools = { throw anonymous }))

            val preview = service.preview(config(), namespaceId, user)

            preview.error shouldBe "${anonymous::class.java.name}: MCP handshake rejected"
            preview.error.shouldNotBeNull() shouldNotContain "null"
        }

        "the describeNamespace timeout defaults to 5 seconds and is bound from agentos.integrations" {
            IntegrationsProperties().previewDescribeNamespaceTimeoutMs shouldBe 5_000
        }

        "preview yields a null namespace description when describeNamespace exceeds the configured timeout" {
            registered(RecordingPlugin(tools = { emptyList() }, describe = { delay(10_000); "too late" }))

            val preview = service.preview(config(), namespaceId, user)

            preview.namespaceDescription.shouldBeNull()
            preview.error.shouldBeNull()
        }

        "preview yields a null namespace description when describeNamespace throws" {
            registered(RecordingPlugin(tools = { emptyList() }, describe = { throw RuntimeException("boom") }))

            val preview = service.preview(config(), namespaceId, user)

            preview.namespaceDescription.shouldBeNull()
            preview.error.shouldBeNull()
        }

        "preview builds the tool context for the current user without an agent or case events" {
            val plugin = registered(RecordingPlugin(tools = { emptyList() }))

            service.preview(config(), namespaceId, user)

            val context = plugin.provideContext.shouldNotBeNull()
            context.namespaceId shouldBe namespaceId
            context.userId shouldBe user.id
            context.userExternalId shouldBe "alice@example.com"
            context.caseEvents.shouldBeEmpty()
            context.agentName.shouldBeNull()
            context.credentialProvider.shouldBeNull()
            verify(exactly = 0) { credentialProviderFactory.forRun(any(), any(), any(), any(), any()) }
        }

        "preview hands the plugin a credential provider built for the current user when an auth setting is bound" {
            val plugin = registered(RecordingPlugin(tools = { emptyList() }))
            val provider: CredentialProvider = { null }
            every {
                credentialProviderFactory.forRun(
                    namespaceId = namespaceId,
                    userId = user.id,
                    caseId = null,
                    agentName = null,
                    emitEvent = null,
                )
            } returns { name -> if (name == "my-auth") provider else null }

            service.preview(config(authSettingName = "my-auth"), namespaceId, user)

            plugin.provideContext.shouldNotBeNull().credentialProvider shouldBe provider
            // describeNamespace never receives the provider: it must not trigger any credential lookup.
            plugin.describeContext.shouldNotBeNull().credentialProvider.shouldBeNull()
            plugin.describeContext.shouldNotBeNull().namespaceId shouldBe namespaceId
        }
    }
}
