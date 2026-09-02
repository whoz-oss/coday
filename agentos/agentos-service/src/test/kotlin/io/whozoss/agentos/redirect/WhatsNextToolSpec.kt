package io.whozoss.agentos.redirect

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import io.whozoss.agentos.sdk.tool.ToolContext

private val CONTEXT = mockk<ToolContext>(relaxed = true)
private const val GUIDELINE = "When DemandBuilder finishes, redirect to TRSharing."

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

    "execute returns the guideline verbatim" {
        val tool = WhatsNextTool(configName = null, guideline = GUIDELINE)
        val result = tool.execute(WhatsNextTool.Input(), CONTEXT)
        result.output shouldBe GUIDELINE
        result.success shouldBe true
    }

    "execute returns the guideline verbatim even when input is null" {
        val tool = WhatsNextTool(configName = null, guideline = GUIDELINE)
        val result = tool.execute(null, CONTEXT)
        result.output shouldBe GUIDELINE
        result.success shouldBe true
    }
})
