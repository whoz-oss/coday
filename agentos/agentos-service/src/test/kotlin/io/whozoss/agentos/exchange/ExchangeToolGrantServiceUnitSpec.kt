package io.whozoss.agentos.exchange

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolPlugin
import io.whozoss.agentos.tool.ToolRegistryService
import io.whozoss.agentos.tool.ToolResolverService
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * Unit tests for [ExchangeToolGrantService]: the enablement rules of the two built-in exchange
 * integrations, and the configuration node handed to the file plugin.
 *
 * The node assertions mirror how `FileToolProvider.provideTools` actually reads each key — notably
 * `asDouble().toFloat()` for the JPEG quality and `isArray` for the deny patterns — so a change in
 * the JSON representation shows up here rather than at runtime inside a tool call.
 */
class ExchangeToolGrantServiceUnitSpec : StringSpec() {
    private val toolRegistryService: ToolRegistryService = mockk()
    private val toolResolverService: ToolResolverService = mockk()

    private fun service(
        properties: ExchangeToolsConfigProperties = ExchangeToolsConfigProperties(),
        storageProperties: ExchangeStorageConfigProperties = ExchangeStorageConfigProperties(),
    ) = ExchangeToolGrantService(
        properties = properties,
        storageProperties = storageProperties,
        toolRegistryService = toolRegistryService,
        toolResolverService = toolResolverService,
        objectMapper = ObjectMapper(),
    )

    private val toolContext =
        ToolContext(
            namespaceId = UUID.randomUUID(),
            userId = null,
            userExternalId = null,
            caseEvents = emptyList(),
            agentName = "my-agent",
        )

    private fun tool(name: String): StandardTool<*> {
        val standardTool = mockk<StandardTool<*>>()
        every { standardTool.name } returns name
        return standardTool
    }

    /** A path that does not exist yet, so a test can assert whether the grant materialised it. */
    private fun unmaterialisedRoot(prefix: String): Path = Files.createTempDirectory(prefix).resolve("scope")

    private fun captureConfig(
        grantService: ExchangeToolGrantService,
        root: Path = unmaterialisedRoot("grant-config"),
        readOnly: Boolean = false,
    ): JsonNode {
        val filePlugin = mockk<ToolPlugin>(relaxed = true)
        every { toolRegistryService.findPlugin("FILE_ACCESS") } returns filePlugin
        grantService.grantTools(
            root = root,
            readOnly = readOnly,
            configName = ExchangeIntegrationTypes.CASE_CONFIG_NAME,
            allowedTools = null,
            toolContext = toolContext,
        )
        val cfg = slot<JsonNode>()
        verify { filePlugin.provideTools(capture(cfg), ExchangeIntegrationTypes.CASE_CONFIG_NAME, any()) }
        return cfg.captured
    }

    init {
        // -------------------------------------------------------------------------
        // Enablement — the agent's declaration wins, the platform default is the fallback
        // -------------------------------------------------------------------------

        "no case grant when the key is absent and the platform default is off" {
            service().resolveCaseGrant(emptyMap()) shouldBe null
        }

        "no case grant when the agent declares no integrations at all" {
            service().resolveCaseGrant(null) shouldBe null
        }

        "the platform default grants every tool when the key is absent" {
            val grant = service(ExchangeToolsConfigProperties(caseEnabledByDefault = true)).resolveCaseGrant(emptyMap())

            grant.shouldNotBeNull()
            grant.allowedTools shouldBe null
        }

        "the platform default also applies to an agent that declares no integrations at all" {
            val grant = service(ExchangeToolsConfigProperties(caseEnabledByDefault = true)).resolveCaseGrant(null)

            grant.shouldNotBeNull()
            grant.allowedTools shouldBe null
        }

        "a key declared with no list grants every tool" {
            val grant = service().resolveCaseGrant(mapOf(ExchangeIntegrationTypes.CASE to null))

            grant.shouldNotBeNull()
            grant.allowedTools shouldBe null
        }

        "a key declared with a list restricts to those tool names" {
            val grant = service().resolveCaseGrant(mapOf(ExchangeIntegrationTypes.CASE to listOf("readFile", "ls")))

            grant.shouldNotBeNull()
            grant.allowedTools shouldBe listOf("readFile", "ls")
        }

        "an empty list opts the agent out even when the platform default is on" {
            // The central opt-out case: isToolAllowed would already reject every tool, but grantTools
            // creates the scope directory before that filter runs — so the decision short-circuits here.
            val grantService = service(ExchangeToolsConfigProperties(caseEnabledByDefault = true))

            grantService.resolveCaseGrant(mapOf(ExchangeIntegrationTypes.CASE to emptyList())) shouldBe null
        }

        "an empty list opts the agent out of the namespace exchange too" {
            val grantService = service(ExchangeToolsConfigProperties(namespaceEnabledByDefault = true))

            grantService.resolveNamespaceGrant(mapOf(ExchangeIntegrationTypes.NAMESPACE to emptyList())) shouldBe null
        }

        "each scope reads its own platform default" {
            val grantService =
                service(
                    ExchangeToolsConfigProperties(caseEnabledByDefault = false, namespaceEnabledByDefault = true),
                )

            grantService.resolveCaseGrant(null) shouldBe null
            grantService.resolveNamespaceGrant(null).shouldNotBeNull()
        }

        // -------------------------------------------------------------------------
        // Plugin configuration node
        // -------------------------------------------------------------------------

        "every configured file-tool setting is carried into the plugin config" {
            val properties =
                ExchangeToolsConfigProperties(
                    imageMaxDimension = 1568,
                    imageJpegQuality = 0.55f,
                    imageMaxSourcePixels = 12_000_000L,
                    imagePassThroughMaxBytes = 2048L,
                    documentMaxOutputChars = 1234,
                    documentMaxAttachedImages = 3,
                    documentMaxTableColumns = 8,
                    documentMaxCellChars = 99,
                )

            val cfg = captureConfig(service(properties))

            cfg.get("imageMaxDimension").asInt() shouldBe 1568
            // The plugin reads this as asDouble()?.toFloat(); asserting the raw double would fail.
            cfg.get("imageJpegQuality").asDouble().toFloat() shouldBe 0.55f
            cfg.get("imageMaxSourcePixels").asLong() shouldBe 12_000_000L
            cfg.get("imagePassThroughMaxBytes").asLong() shouldBe 2048L
            cfg.get("documentMaxOutputChars").asInt() shouldBe 1234
            cfg.get("documentMaxAttachedImages").asInt() shouldBe 3
            cfg.get("documentMaxTableColumns").asInt() shouldBe 8
            cfg.get("documentMaxCellChars").asInt() shouldBe 99
        }

        "extraDenyPatterns is emitted as a JSON array" {
            val properties = ExchangeToolsConfigProperties(extraDenyPatterns = listOf("*.bak", "internal-*"))

            val patterns = captureConfig(service(properties)).get("extraDenyPatterns")

            patterns.isArray shouldBe true
            patterns.map { it.asText() } shouldBe listOf("*.bak", "internal-*")
        }

        "extraDenyPatterns is an empty array when nothing is configured" {
            // The plugin reads the key with `takeIf { it.isArray }`: a missing key or a scalar would
            // silently mean "no extra pattern", so the array itself is the contract.
            val patterns = captureConfig(service()).get("extraDenyPatterns")

            patterns.isArray shouldBe true
            patterns.size() shouldBe 0
        }

        "an out-of-range jpeg quality is clamped rather than handed to the JPEG writer" {
            captureConfig(service(ExchangeToolsConfigProperties(imageJpegQuality = 1.5f)))
                .get("imageJpegQuality")
                .asDouble()
                .toFloat() shouldBe 1.0f

            captureConfig(service(ExchangeToolsConfigProperties(imageJpegQuality = -0.2f)))
                .get("imageJpegQuality")
                .asDouble()
                .toFloat() shouldBe 0.0f
        }

        "readMaxSizeMb derives from the exchange read cap" {
            val storage = ExchangeStorageConfigProperties(readMaxSizeBytes = 50L * 1024 * 1024)

            captureConfig(service(storageProperties = storage)).get("readMaxSizeMb").asLong() shouldBe 50L
        }

        "readMaxSizeMb is floored at 1 MB" {
            val storage = ExchangeStorageConfigProperties(readMaxSizeBytes = 512L * 1024)

            captureConfig(service(storageProperties = storage)).get("readMaxSizeMb").asLong() shouldBe 1L
        }

        "the runtime rootPath and readOnly reach the plugin unchanged" {
            val root = unmaterialisedRoot("grant-runtime")

            val cfg = captureConfig(service(), root = root, readOnly = true)

            cfg.get("rootPath").asText() shouldBe root.toAbsolutePath().toString()
            cfg.get("readOnly").asBoolean() shouldBe true
        }

        // -------------------------------------------------------------------------
        // Materialisation
        // -------------------------------------------------------------------------

        "the scope root is created before the plugin tools are built" {
            // The file-plugin's BoundaryPathResolver canonicalises rootPath at construction and throws
            // when the directory is missing, so the grant has to materialise it first.
            val root = unmaterialisedRoot("grant-materialise")
            every { toolRegistryService.findPlugin("FILE_ACCESS") } returns mockk<ToolPlugin>(relaxed = true)

            service().grantTools(
                root = root,
                readOnly = false,
                configName = ExchangeIntegrationTypes.CASE_CONFIG_NAME,
                allowedTools = null,
                toolContext = toolContext,
            )

            Files.exists(root) shouldBe true
        }

        "no tools and no directory when the FILE_ACCESS plugin is not loaded" {
            val root = unmaterialisedRoot("grant-no-plugin")
            every { toolRegistryService.findPlugin("FILE_ACCESS") } returns null

            val tools =
                service().grantTools(
                    root = root,
                    readOnly = false,
                    configName = ExchangeIntegrationTypes.CASE_CONFIG_NAME,
                    allowedTools = null,
                    toolContext = toolContext,
                )

            tools.shouldBeEmpty()
            Files.exists(root) shouldBe false
        }

        "the plugin tools are filtered through the shared allowlist matcher" {
            val root = unmaterialisedRoot("grant-filter")
            val filePlugin = mockk<ToolPlugin>()
            every { toolRegistryService.findPlugin("FILE_ACCESS") } returns filePlugin
            every { filePlugin.provideTools(any(), any(), any()) } returns listOf(tool("readFile"), tool("editFiles"))
            every {
                toolResolverService.isToolAllowed("readFile", ExchangeIntegrationTypes.CASE_CONFIG_NAME, any())
            } returns true
            every {
                toolResolverService.isToolAllowed("editFiles", ExchangeIntegrationTypes.CASE_CONFIG_NAME, any())
            } returns false

            val tools =
                service().grantTools(
                    root = root,
                    readOnly = false,
                    configName = ExchangeIntegrationTypes.CASE_CONFIG_NAME,
                    allowedTools = listOf("readFile"),
                    toolContext = toolContext,
                )

            tools.map { it.name } shouldBe listOf("readFile")
        }
    }
}
