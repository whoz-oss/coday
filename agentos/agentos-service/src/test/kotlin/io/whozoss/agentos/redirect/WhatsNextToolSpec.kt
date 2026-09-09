package io.whozoss.agentos.redirect

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import io.whozoss.agentos.sdk.tool.ToolContext

private val CONTEXT = mockk<ToolContext>(relaxed = true)
private const val GUIDELINE = "When DemandBuilder finishes, redirect to TRSharing."
private val objectMapper = jacksonObjectMapper()

class WhatsNextToolSpec : StringSpec({

    // -------------------------------------------------------------------------
    // name
    // -------------------------------------------------------------------------

    "name uses configName prefix when provided" {
        val tool = WhatsNextTool(configName = "REDIRECT_all", guideline = GUIDELINE)
        tool.name shouldBe "REDIRECT_all__whatsNext"
    }

    "name is bare whatsNext when configName is null" {
        val tool = WhatsNextTool(configName = null, guideline = GUIDELINE)
        tool.name shouldBe "whatsNext"
    }

    // -------------------------------------------------------------------------
    // execute
    // -------------------------------------------------------------------------

    "execute returns the guideline wrapped in a JSON object" {
        val tool = WhatsNextTool(configName = null, guideline = GUIDELINE)
        val result = tool.execute(null, CONTEXT)
        result.success shouldBe true
        val parsed = objectMapper.readTree(result.output)
        parsed.get("guideline").asText() shouldBe GUIDELINE
    }

    "execute output is valid JSON" {
        val tool = WhatsNextTool(configName = null, guideline = GUIDELINE)
        val result = tool.execute(null, CONTEXT)
        // Should not throw
        objectMapper.readTree(result.output)
    }
})
