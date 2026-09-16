package io.whozoss.agentos.factory

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.whozoss.agentos.sdk.caseEvent.CaseStatusEvent
import io.whozoss.agentos.sdk.caseFlow.CaseStatus
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.sdk.tool.ToolContext
import okhttp3.OkHttpClient
import java.util.UUID

class FactoryPublishProjectionToolSpec : StringSpec({
    val mapper = jacksonObjectMapper()
    val tool = FactoryPublishProjectionTool("http://localhost:3141", OkHttpClient(), mapper)
    fun input(expectedRevision: Long? = null) = FactoryPublishProjectionTool.Input(
        "1", "wf-1", "delivery", "Workflow", "ready", expectedRevision,
        listOf(FactoryPublishProjectionTool.Step("step-1", "First", "ready")),
    )

    "validates projection graph deterministically" {
        FactoryProjectionValidation.validate(input()) shouldBe null
        FactoryProjectionValidation.validate(input().copy(steps = listOf(
            FactoryPublishProjectionTool.Step("a", "A", "ready", dependsOn = listOf("b")),
            FactoryPublishProjectionTool.Step("b", "B", "ready", dependsOn = listOf("a")),
        )))?.code shouldBe "INVALID_PROJECTION"
    }

    "rejects the smoke payload's in_progress step status with actionable feedback" {
        val smokeInput = FactoryPublishProjectionTool.Input(
            "1", "projection-smoke-demo", "demo", "Prepare a demo", "running", null,
            listOf(
                FactoryPublishProjectionTool.Step("define-goal", "Define goal", "completed"),
                FactoryPublishProjectionTool.Step("prepare-demo", "Prepare demo", "in_progress", dependsOn = listOf("define-goal")),
                FactoryPublishProjectionTool.Step("review-result", "Review result", "pending", dependsOn = listOf("prepare-demo")),
            ),
        )
        val namespace = UUID.randomUUID()
        val context = ToolContext(namespace, UUID.randomUUID(), "user", listOf(
            CaseStatusEvent(metadata = EntityMetadata(), namespaceId = namespace, caseId = UUID.randomUUID(), status = CaseStatus.PENDING),
        ), "agent")

        val result = tool.execute(smokeInput, context)

        result.errorType shouldBe "INVALID_PROJECTION"
        result.errorMessage shouldBe "steps[1].status: must be one of blocked,cancelled,completed,failed,pending,ready,running,waiting_human"
    }

    "numeric schemaVersion is coerced to string 1 before local validation" {
        val decoded = mapper.readValue(
            """{"schemaVersion":1,"workflowId":"projection-smoke-demo","workflowType":"demo","title":"Prepare a demo","status":"running","steps":[]}""",
            FactoryPublishProjectionTool.Input::class.java,
        )
        decoded.schemaVersion shouldBe "1"
        FactoryProjectionValidation.validate(decoded) shouldBe null
    }

    "normalizes absent optional fields out of the Factory projection payload" {
        val payload = tool.projectionPayload(input())
        payload.containsKey("expectedRevision") shouldBe false
        val step = (payload["steps"] as List<*>).single() as Map<*, *>
        step.containsKey("description") shouldBe false
        step["dependsOn"] shouldBe emptyList<String>()

        val serialized = mapper.readTree(mapper.writeValueAsString(mapOf("projection" to payload)))
        serialized.path("projection").has("expectedRevision") shouldBe false
        serialized.path("projection").path("steps")[0].has("description") shouldBe false
    }

    "keeps present optional fields in the Factory projection payload" {
        val payload = tool.projectionPayload(
            input(expectedRevision = 7).copy(
                steps = listOf(FactoryPublishProjectionTool.Step("step-1", "First", "ready", description = "Ready to run")),
            ),
        )
        payload.containsKey("expectedRevision") shouldBe true
        payload["expectedRevision"] shouldBe 7L
        val step = (payload["steps"] as List<*>).single() as Map<*, *>
        step.containsKey("description") shouldBe true
        step["description"] shouldBe "Ready to run"
    }

    "parses create and idempotent success envelopes" {
        tool.parseResponse(201, """{"data":{"workflowId":"wf-1","revision":1,"changed":true}}""").success shouldBe true
        tool.parseResponse(200, """{"data":{"workflowId":"wf-1","revision":1,"changed":false}}""").metadata["changed"] shouldBe false
    }

    "preserves revision conflict machine code" {
        val result = tool.parseResponse(409, """{"error":{"code":"REVISION_CONFLICT","message":"The expected revision is stale."}}""")
        result.success shouldBe false
        result.errorType shouldBe "REVISION_CONFLICT"
    }

    "rejects malformed Factory response" {
        tool.parseResponse(200, "not-json").errorType shouldBe "MALFORMED_FACTORY_RESPONSE"
    }

    "fails closed when case events are missing" {
        val context = ToolContext(UUID.randomUUID(), UUID.randomUUID(), "user", emptyList(), "agent")
        tool.execute(input(), context).errorType shouldBe "CASE_CONTEXT_UNAVAILABLE"
    }

    "fails closed when case events disagree" {
        val namespace = UUID.randomUUID()
        val context = ToolContext(namespace, UUID.randomUUID(), "user", listOf(
            CaseStatusEvent(metadata = EntityMetadata(), namespaceId = namespace, caseId = UUID.randomUUID(), status = CaseStatus.PENDING),
            CaseStatusEvent(metadata = EntityMetadata(), namespaceId = namespace, caseId = UUID.randomUUID(), status = CaseStatus.PENDING),
        ), "agent")
        tool.execute(input(), context).errorType shouldBe "CASE_CONTEXT_UNAVAILABLE"
    }

    "grant is explicit and resolves exact tool name" {
        val plugin = FactoryToolPlugin(mapper, "http://localhost:3141")
        val grant = FactoryToolGrantService(plugin)
        grant.isGranted(null) shouldBe false
        grant.isGranted(mapOf("FACTORY" to emptyList())) shouldBe false
        grant.isGranted(mapOf("FACTORY" to listOf("publish_projection"))) shouldBe true
        grant.grantTools(ToolContext(UUID.randomUUID(), null, null, emptyList())).single().name shouldBe "FACTORY__publish_projection"
    }
})
