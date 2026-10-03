package io.whozoss.agentos.plugins.factorybridge

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.whozoss.agentos.plugins.factorybridge.tools.FactoryAskStepQuestionTool
import io.whozoss.agentos.sdk.caseEvent.CaseStatusEvent
import io.whozoss.agentos.sdk.caseFlow.CaseStatus
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.sdk.tool.ToolContext
import okhttp3.OkHttpClient
import java.util.UUID

/** Source-only contract scenarios. Execution is deliberately left to the maintainer. */
class FactoryAskStepQuestionToolSpec : StringSpec({
    "tool is the dedicated FACTORY__ask_step_question worker capability" {
        val tool =
            FactoryAskStepQuestionTool(
                "http://127.0.0.1:8141",
                OkHttpClient(),
                jacksonObjectMapper(),
                FactoryStepResultBindingRegistry(),
            )
        tool.name shouldBe "FACTORY__ask_step_question"
        tool.version shouldBe "1.0.0"
        tool.description shouldContain "human question"
    }

    "input schema carries no identity fields — attempt identity is runtime-injected" {
        val tool =
            FactoryAskStepQuestionTool(
                "http://127.0.0.1:8141",
                OkHttpClient(),
                jacksonObjectMapper(),
                FactoryStepResultBindingRegistry(),
            )
        tool.inputSchema shouldNotContain "attemptId"
        tool.inputSchema shouldNotContain "caseId"
        tool.inputSchema shouldNotContain "namespaceId"
        tool.inputSchema shouldNotContain "workflowId"
        tool.inputSchema shouldNotContain "stepId"
        tool.inputSchema shouldContain "\"prompt\""
        tool.inputSchema shouldContain "SINGLE_CHOICE"
    }

    "tool is fail-closed outside a Factory-bound case" {
        val tool =
            FactoryAskStepQuestionTool(
                "http://127.0.0.1:8141",
                OkHttpClient(),
                jacksonObjectMapper(),
                FactoryStepResultBindingRegistry(),
            )
        val result = tool.execute(FactoryAskStepQuestionTool.Input("Proceed?"), ToolContext(UUID.randomUUID(), null, null, emptyList(), "Worker"))
        result.success shouldBe false
        result.errorType shouldBe "FACTORY_QUESTION_CONTEXT_MISSING"
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
            FactoryAskStepQuestionTool(
                "http://127.0.0.1:8141",
                OkHttpClient(),
                jacksonObjectMapper(),
                FactoryStepResultBindingRegistry(),
            )
        val result = tool.execute(FactoryAskStepQuestionTool.Input("Proceed?"), context)
        result.success shouldBe false
        result.errorType shouldBe "FACTORY_QUESTION_CONTEXT_MISSING"
    }

    "ask_step_question is grantable only as an explicit worker capability" {
        val grantService = FactoryToolGrantService { emptyList() }
        grantService.isGranted(mapOf("FACTORY" to listOf("ask_step_question"))) shouldBe true
        grantService.isGranted(mapOf("FACTORY" to listOf("FACTORY__ask_step_question"))) shouldBe true
        // Absence of an explicit grant grants nothing (explicit-only policy).
        grantService.isGranted(mapOf("FACTORY" to emptyList())) shouldBe false
        grantService.isGranted(emptyMap()) shouldBe false
        grantService.isGranted(null) shouldBe false
    }
})
