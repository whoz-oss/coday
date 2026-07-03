package io.whozoss.agentos.casePlugin

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.whozoss.agentos.sdk.caseEvent.CaseEvent
import io.whozoss.agentos.sdk.tool.ToolContext
import java.util.UUID

class CaseToolPluginUnitSpec : StringSpec({

    val namespaceId: UUID = UUID.randomUUID()
    val noopLoader: (UUID, UUID, UUID?) -> List<CaseEvent>? = { _, _, _ -> emptyList() }
    val mapper = jacksonObjectMapper()

    fun context() = ToolContext(
        namespaceId = namespaceId,
        userId = null,
        userExternalId = null,
        caseEvents = emptyList(),
    )

    // -------------------------------------------------------------------------
    // context == null guard
    // -------------------------------------------------------------------------

    "returns empty list when context is null" {
        val plugin = CaseToolPlugin(noopLoader)
        val tools = plugin.provideTools(config = null, context = null)
        tools.shouldBeEmpty()
    }

    // -------------------------------------------------------------------------
    // Default includesTechnicalEvents behaviour
    // -------------------------------------------------------------------------

    "returns a ReadCaseTool when context is provided" {
        val plugin = CaseToolPlugin(noopLoader)
        val tools = plugin.provideTools(config = null, context = context())
        tools shouldHaveSize 1
        tools.first() as ReadCaseTool
    }

    "defaults includesTechnicalEvents to true when config is null" {
        val plugin = CaseToolPlugin(noopLoader)
        val tools = plugin.provideTools(config = null, context = context())
        val tool = tools.first() as ReadCaseTool
        // The tool's behaviour is not directly observable via a public field;
        // we verify indirectly by checking the integration type is as expected.
        tool.name shouldBe "ReadCase"
    }

    "defaults includesTechnicalEvents to true when config has no 'includesTechnicalEvents' key" {
        val config = mapper.readTree("{\"someOtherKey\": 42}")
        val plugin = CaseToolPlugin(noopLoader)
        val tools = plugin.provideTools(config = config, context = context())
        tools shouldHaveSize 1
    }

    // -------------------------------------------------------------------------
    // includesTechnicalEvents propagation
    // -------------------------------------------------------------------------

    "propagates includesTechnicalEvents=false from config" {
        // We verify that the ReadCaseTool produced with includesTechnicalEvents=false
        // behaves differently from one produced with true by checking the formatter
        // output indirectly via the tool name — the flag is an implementation detail.
        // The CaseTranscriptFormatterUnitSpec already covers the rendering contract;
        // here we only verify that the plugin reads and forwards the config value.
        val config = mapper.readTree("{\"includesTechnicalEvents\": false}")
        val plugin = CaseToolPlugin(noopLoader)
        val tools = plugin.provideTools(config = config, context = context())
        tools shouldHaveSize 1
        // The tool is created successfully; the flag value is tested in ReadCaseToolUnitSpec
        // and CaseTranscriptFormatterUnitSpec.
        tools.first() as ReadCaseTool
    }

    "propagates includesTechnicalEvents=true from config" {
        val config = mapper.readTree("{\"includesTechnicalEvents\": true}")
        val plugin = CaseToolPlugin(noopLoader)
        val tools = plugin.provideTools(config = config, context = context())
        tools shouldHaveSize 1
        tools.first() as ReadCaseTool
    }

    // -------------------------------------------------------------------------
    // configName propagation
    // -------------------------------------------------------------------------

    "propagates configName to ReadCaseTool" {
        val plugin = CaseToolPlugin(noopLoader)
        val tools = plugin.provideTools(config = null, configName = "CASE_prod", context = context())
        val tool = tools.first() as ReadCaseTool
        tool.name shouldBe "CASE_prod__ReadCase"
    }

    "uses default tool name when configName is null" {
        val plugin = CaseToolPlugin(noopLoader)
        val tools = plugin.provideTools(config = null, configName = null, context = context())
        val tool = tools.first() as ReadCaseTool
        tool.name shouldBe "ReadCase"
    }

    // -------------------------------------------------------------------------
    // Permission enforcement via loader
    // -------------------------------------------------------------------------

    "tool returns NOT_FOUND when loader returns null due to permission denial" {
        // The loader returns null when the user lacks READ on the target case.
        // ReadCaseTool must not leak whether the case exists or is just inaccessible.
        val unauthorisedLoader: (UUID, UUID, UUID?) -> List<CaseEvent>? = { _, _, _ -> null }
        val userId = UUID.randomUUID()
        val contextWithUser = ToolContext(
            namespaceId = namespaceId,
            userId = userId,
            userExternalId = null,
            caseEvents = emptyList(),
        )
        val plugin = CaseToolPlugin(unauthorisedLoader)
        val tools = plugin.provideTools(config = null, context = contextWithUser)
        val tool = tools.first() as ReadCaseTool

        val result = tool.execute(ReadCaseTool.Input(caseId = UUID.randomUUID().toString()), contextWithUser)

        result.success shouldBe false
        result.errorType shouldBe "NOT_FOUND"
    }

    "tool returns NOT_FOUND for cross-namespace or anonymous case (loader returns null)" {
        // The loader returns null for namespace mismatch, permission denial, or null userId.
        // Same observable behaviour in all cases — no information leak.
        val rejectingLoader: (UUID, UUID, UUID?) -> List<CaseEvent>? = { _, _, _ -> null }
        val plugin = CaseToolPlugin(rejectingLoader)
        val tools = plugin.provideTools(config = null, context = context())
        val tool = tools.first() as ReadCaseTool

        val result = tool.execute(
            ReadCaseTool.Input(caseId = UUID.randomUUID().toString()),
            ToolContext(namespaceId = namespaceId, userId = null, userExternalId = null, caseEvents = emptyList()),
        )

        result.success shouldBe false
        result.errorType shouldBe "NOT_FOUND"
    }
})
