package io.whozoss.agentos.plugins.factorybridge

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.whozoss.agentos.plugins.factorybridge.tools.FactorySubmitStepResultTool
import io.whozoss.agentos.sdk.caseEvent.CaseStatusEvent
import io.whozoss.agentos.sdk.caseFlow.CaseStatus
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.sdk.tool.ToolContext
import okhttp3.OkHttpClient
import java.util.UUID

/** Source-only contract scenarios. Execution is deliberately left to the maintainer. */
class FactorySubmitStepResultToolSpec : StringSpec({
    "tool is fail-closed outside a Factory-bound case" {
        val tool =
            FactorySubmitStepResultTool(
                "http://127.0.0.1:3141",
                OkHttpClient(),
                jacksonObjectMapper(),
                FactoryStepResultBindingRegistry(),
            )
        val input = FactorySubmitStepResultTool.Input("PASS", "ok", claims = FactorySubmitStepResultTool.Claims(emptyList()))
        val result = tool.execute(input, ToolContext(UUID.randomUUID(), null, null, emptyList(), "Worker"))
        result.success shouldBe false
        result.errorType shouldBe "FACTORY_RESULT_CONTEXT_MISSING"
    }

    "tool is fail-closed when the case exists but has no active binding" {
        val namespaceId = UUID.randomUUID()
        val caseId = UUID.randomUUID()
        val context =
            ToolContext(
                namespaceId,
                UUID.randomUUID(),
                "actor",
                listOf(CaseStatusEvent(metadata = EntityMetadata(), namespaceId = namespaceId, caseId = caseId, status = CaseStatus.RUNNING)),
                "Worker",
            )
        val tool =
            FactorySubmitStepResultTool(
                "http://127.0.0.1:3141",
                OkHttpClient(),
                jacksonObjectMapper(),
                FactoryStepResultBindingRegistry(),
            )
        val result = tool.execute(FactorySubmitStepResultTool.Input("PASS", "ok", claims = FactorySubmitStepResultTool.Claims(emptyList())), context)
        result.success shouldBe false
        result.errorType shouldBe "FACTORY_RESULT_CONTEXT_MISSING"
    }
})
