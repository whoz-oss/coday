package io.whozoss.agentos.integrationConfig

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.comparables.shouldBeLessThan
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
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTimedValue

/**
 * Unit tests for [IntegrationConfigToolPreviewService]: plugin lookup, tool mapping, failure
 * isolation (a failing `provideTools` becomes an error message, a slow or failing
 * `describeNamespace` becomes a null line), the time bounds of both plugin calls and the
 * `ToolContext` handed to the plugin.
 */
class IntegrationConfigToolPreviewServiceUnitSpec : StringSpec() {
    private val toolRegistryService: ToolRegistryService = mockk()
    private val credentialProviderFactory: CredentialProviderFactory = mockk()

    // Default timeouts: a cold worker thread must never turn a nominal preview into a timeout.
    private val service =
        IntegrationConfigToolPreviewService(
            toolRegistryService = toolRegistryService,
            credentialProviderFactory = credentialProviderFactory,
            integrationsProperties = IntegrationsProperties(),
        )

    // Short timeouts, only for the scenarios where the plugin is made to outlast them.
    private val shortTimeoutService =
        IntegrationConfigToolPreviewService(
            toolRegistryService = toolRegistryService,
            credentialProviderFactory = credentialProviderFactory,
            integrationsProperties =
                IntegrationsProperties(previewDescribeNamespaceTimeoutMs = 100, previewProvideToolsTimeoutMs = 200),
        )

    // Services built by a single test for a specific worker cap; shut down with the others.
    private val extraServices = mutableListOf<IntegrationConfigToolPreviewService>()

    private fun previewService(properties: IntegrationsProperties): IntegrationConfigToolPreviewService =
        IntegrationConfigToolPreviewService(
            toolRegistryService = toolRegistryService,
            credentialProviderFactory = credentialProviderFactory,
            integrationsProperties = properties,
        ).also { extraServices += it }

    private val serviceLogger = LoggerFactory.getLogger(IntegrationConfigToolPreviewService::class.java) as Logger

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

    /**
     * Blocks until [release] opens (10 s at most) and ignores interrupts, like a plugin stuck in a
     * socket read that an interrupt cannot abort. Returns whether an interrupt was delivered meanwhile.
     */
    private fun blockIgnoringInterrupts(release: CountDownLatch): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        var interrupted = false
        while (true) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) return interrupted
            try {
                if (release.await(remaining, TimeUnit.NANOSECONDS)) return interrupted
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
    }

    init {
        afterSpec {
            service.shutdown()
            shortTimeoutService.shutdown()
            extraServices.forEach { it.shutdown() }
        }

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

        "the describeNamespace timeout defaults to 5 seconds" {
            IntegrationsProperties().previewDescribeNamespaceTimeoutMs shouldBe 5_000
        }

        "preview yields a null namespace description when describeNamespace exceeds the configured timeout" {
            registered(RecordingPlugin(tools = { emptyList() }, describe = { delay(10_000); "too late" }))

            val preview = shortTimeoutService.preview(config(), namespaceId, user)

            preview.namespaceDescription.shouldBeNull()
            preview.error.shouldBeNull()
        }

        "the provideTools timeout defaults to 30 seconds" {
            IntegrationsProperties().previewProvideToolsTimeoutMs shouldBe 30_000
        }

        "the preview worker cap defaults to 4" {
            IntegrationsProperties().previewMaxConcurrentPluginCalls shouldBe 4
        }

        "preview returns a timeout error without waiting for a provideTools that blocks past the configured timeout" {
            val release = CountDownLatch(1)
            registered(
                RecordingPlugin(tools = {
                    blockIgnoringInterrupts(release)
                    listOf(tool("MCP_PROD__TooLate"))
                }),
            )
            try {
                val (preview, elapsed) = measureTimedValue { shortTimeoutService.preview(config(), namespaceId, user) }

                elapsed shouldBeLessThan 3.seconds
                preview.tools.shouldBeEmpty()
                preview.error shouldBe "TimeoutException: tools not built within 200 ms"
            } finally {
                release.countDown()
            }
        }

        "preview abandons a timed-out provideTools without interrupting it, and the call still runs to completion" {
            val release = CountDownLatch(1)
            val completed = CountDownLatch(1)
            val interrupted = AtomicBoolean(false)
            registered(
                RecordingPlugin(tools = {
                    interrupted.set(blockIgnoringInterrupts(release))
                    completed.countDown()
                    emptyList()
                }),
            )

            try {
                val preview = shortTimeoutService.preview(config(), namespaceId, user)

                preview.error shouldBe "TimeoutException: tools not built within 200 ms"
            } finally {
                release.countDown()
            }
            completed.await(5, TimeUnit.SECONDS) shouldBe true
            interrupted.get() shouldBe false
        }

        "preview still logs a provideTools failure that happens after the preview gave up" {
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            serviceLogger.addAppender(appender)
            val release = CountDownLatch(1)
            registered(
                RecordingPlugin(tools = {
                    blockIgnoringInterrupts(release)
                    throw IllegalStateException("MCP session closed late")
                }),
            )
            try {
                shortTimeoutService.preview(config(), namespaceId, user).error shouldBe
                    "TimeoutException: tools not built within 200 ms"
                release.countDown()

                eventually(5.seconds) {
                    appender.list.any {
                        it.level == Level.WARN && it.throwableProxy?.message == "MCP session closed late"
                    } shouldBe true
                }
            } finally {
                release.countDown()
                serviceLogger.detachAppender(appender)
            }
        }

        "preview answers at once, without calling the plugin, when every preview worker is busy" {
            val singleWorker =
                previewService(
                    IntegrationsProperties(
                        previewProvideToolsTimeoutMs = 10_000,
                        previewDescribeNamespaceTimeoutMs = 100,
                        previewMaxConcurrentPluginCalls = 1,
                    ),
                )
            val release = CountDownLatch(1)
            val entered = CountDownLatch(1)
            registered(
                RecordingPlugin(tools = {
                    entered.countDown()
                    blockIgnoringInterrupts(release)
                    emptyList()
                }),
            )
            val holder = thread { singleWorker.preview(config(), namespaceId, user) }
            try {
                entered.await(5, TimeUnit.SECONDS) shouldBe true
                val refusedPlugin = registered(RecordingPlugin(tools = { emptyList() }))

                val (preview, elapsed) = measureTimedValue { singleWorker.preview(config(), namespaceId, user) }

                // Far below the 10 s bound: a busy pool is answered without waiting for a worker.
                elapsed shouldBeLessThan 3.seconds
                preview.tools.shouldBeEmpty()
                preview.error shouldBe
                    "RejectedExecutionException: all preview workers are busy with earlier previews, retry later"
                preview.namespaceDescription.shouldBeNull()
                refusedPlugin.provideContext.shouldBeNull()
                refusedPlugin.describeContext.shouldBeNull()
            } finally {
                release.countDown()
                holder.join(5_000)
            }
        }

        "a worker held by an abandoned call is available again once the plugin returns" {
            val singleWorker =
                previewService(
                    IntegrationsProperties(
                        previewProvideToolsTimeoutMs = 200,
                        previewDescribeNamespaceTimeoutMs = 100,
                        previewMaxConcurrentPluginCalls = 1,
                    ),
                )
            val release = CountDownLatch(1)
            val completed = CountDownLatch(1)
            registered(
                RecordingPlugin(tools = {
                    blockIgnoringInterrupts(release)
                    completed.countDown()
                    emptyList()
                }),
            )
            try {
                singleWorker.preview(config(), namespaceId, user).error shouldBe
                    "TimeoutException: tools not built within 200 ms"
            } finally {
                release.countDown()
            }
            completed.await(5, TimeUnit.SECONDS) shouldBe true
            registered(RecordingPlugin(tools = { listOf(tool("MCP_PROD__Again")) }))

            eventually(5.seconds) {
                val preview = singleWorker.preview(config(), namespaceId, user)

                preview.tools.map { it.name } shouldBe listOf("MCP_PROD__Again")
            }
        }

        "preview returns a null namespace description without waiting for a describeNamespace that blocks" {
            val release = CountDownLatch(1)
            registered(
                RecordingPlugin(tools = { emptyList() }, describe = {
                    blockIgnoringInterrupts(release)
                    "too late"
                }),
            )
            try {
                val (preview, elapsed) = measureTimedValue { shortTimeoutService.preview(config(), namespaceId, user) }

                elapsed shouldBeLessThan 3.seconds
                preview.namespaceDescription.shouldBeNull()
                preview.error.shouldBeNull()
            } finally {
                release.countDown()
            }
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
            verify(exactly = 0) { credentialProviderFactory.forPreview(any(), any()) }
        }

        "preview hands the plugin a credential provider built for the current user when an auth setting is bound" {
            val plugin = registered(RecordingPlugin(tools = { emptyList() }))
            val provider: CredentialProvider = { null }
            every {
                credentialProviderFactory.forPreview(namespaceId = namespaceId, userId = user.id)
            } returns { name -> if (name == "my-auth") provider else error("unexpected auth setting '$name'") }

            service.preview(config(authSettingName = "my-auth"), namespaceId, user)

            plugin.provideContext.shouldNotBeNull().credentialProvider shouldBe provider
            // describeNamespace never receives the provider: it must not trigger any credential lookup.
            plugin.describeContext.shouldNotBeNull().credentialProvider.shouldBeNull()
            plugin.describeContext.shouldNotBeNull().namespaceId shouldBe namespaceId
        }
    }
}
