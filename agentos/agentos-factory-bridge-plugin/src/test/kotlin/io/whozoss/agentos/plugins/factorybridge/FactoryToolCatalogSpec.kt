package io.whozoss.agentos.plugins.factorybridge

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import okhttp3.OkHttpClient

class FactoryToolCatalogSpec : StringSpec({
    "workstream catalogue exposes only the active tools" {
        val config = FactoryBridgeConfig("http://localhost", "secret", "bridge", listOf("workflow:write"))
        val services = FactoryBridgeServices(
            config,
            jacksonObjectMapper(),
            OkHttpClient(),
            FactoryTrustedHeaderSigner("secret", "bridge", listOf("workflow:write")),
        )

        val tools = buildFactoryWorkstreamTools(services)
        tools.map { it.name } shouldContainExactly listOf(
            "FACTORY_WORKSTREAM__get_workflow",
            "FACTORY_WORKSTREAM__get_workstream",
            "FACTORY_WORKSTREAM__list_workflows",
            "FACTORY_WORKSTREAM__get_step_attempts",
            "FACTORY_WORKSTREAM__get_blockers",
            "FACTORY_WORKSTREAM__get_required_human_actions",
            "FACTORY_WORKSTREAM__start_workflow",
            "FACTORY_WORKSTREAM__request_agent_retry",
        )
        tools.associate { it.name to it.inputSchema } shouldBe mapOf(
            "FACTORY_WORKSTREAM__get_workflow" to """{"type":"object","additionalProperties":false,"properties":{"workflowId":{"type":"string","pattern":"^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$"}},"required":["workflowId"]}""",
            "FACTORY_WORKSTREAM__get_workstream" to """{"type":"object","additionalProperties":false,"properties":{"workstreamId":{"type":"string","maxLength":128}},"required":["workstreamId"]}""",
            "FACTORY_WORKSTREAM__list_workflows" to """{"type":"object","additionalProperties":false,"properties":{"state":{"type":"string","enum":["active","removed","purged","all"]},"workflowType":{"type":"string","maxLength":128},"limit":{"type":"integer","minimum":1,"maximum":200},"cursor":{"type":"string","maxLength":256}},"required":[]}""",
            "FACTORY_WORKSTREAM__get_step_attempts" to """{"type":"object","additionalProperties":false,"properties":{"workflowId":{"type":"string","maxLength":128},"stepId":{"type":"string","maxLength":128}},"required":["workflowId","stepId"]}""",
            "FACTORY_WORKSTREAM__get_blockers" to """{"type":"object","additionalProperties":false,"properties":{"workflowId":{"type":"string","maxLength":128}},"required":["workflowId"]}""",
            "FACTORY_WORKSTREAM__get_required_human_actions" to """{"type":"object","additionalProperties":false,"properties":{"workflowId":{"type":"string","maxLength":128}},"required":["workflowId"]}""",
            "FACTORY_WORKSTREAM__start_workflow" to """{"type":"object","additionalProperties":false,"properties":{"workflowType":{"type":"string","maxLength":128},"title":{"type":"string","maxLength":200},"ticket":{"type":"string","maxLength":64}},"required":["workflowType"]}""",
            "FACTORY_WORKSTREAM__request_agent_retry" to """{"type":"object","additionalProperties":false,"properties":{"workflowId":{"type":"string","maxLength":128},"stepId":{"type":"string","maxLength":128},"expectedRevision":{"type":"integer","minimum":1},"reasonCode":{"type":"string","maxLength":64},"idempotencyKey":{"type":"string","maxLength":128}},"required":["workflowId","stepId","expectedRevision","reasonCode"]}""",
        )
    }

    "worker catalogue exposes only submit step result" {
        val config = FactoryBridgeConfig("http://localhost", null, "bridge", listOf("workflow:write"))
        val services = FactoryBridgeServices(config, jacksonObjectMapper(), OkHttpClient(), FactoryTrustedHeaderSigner(null, "bridge", emptyList()))
        FactoryWorkerToolPlugin { services }.provideTools(null).map { it.name } shouldContainExactly
            listOf("FACTORY_WORKER__submit_step_result")
    }
})
