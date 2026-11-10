package io.whozoss.agentos.workflow

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

class WorkflowRunBindingsUnitSpec :
    StringSpec({
        val mapper = jacksonObjectMapper()
        val bindings =
            WorkflowRunBindings(
                agentConfigId = UUID.randomUUID(),
                agentName = "ProfileCaretaker",
                promptId = UUID.randomUUID(),
                title = "Weekly digest",
                scheduledPromptId = UUID.randomUUID(),
                runId = UUID.randomUUID(),
            )

        fun configWith(params: String) =
            LoopConfig(steps = listOf(LoopStep(id = "s", tool = "BatchCaseStarter", input = LoopStepInput(params = mapper.readTree(params)))))

        "whole-value bindings are substituted, nested ones included" {
            val resolved =
                bindings.applyTo(
                    configWith("""{"agentName": "{{run.agentName}}", "nested": {"ids": ["{{run.runId}}"]}, "n": 3}"""),
                ).steps.single().input.params!!

            resolved["agentName"].asText() shouldBe "ProfileCaretaker"
            resolved["nested"]["ids"][0].asText() shouldBe bindings.runId.toString()
            resolved["n"].asInt() shouldBe 3
        }

        "text that is not exactly a binding is left untouched" {
            val resolved = bindings.applyTo(configWith("""{"prompt": "Hello {{run.agentName}}", "plain": "x"}""")).steps.single().input.params!!

            resolved["prompt"].asText() shouldBe "Hello {{run.agentName}}"
            resolved["plain"].asText() shouldBe "x"
        }

        "the definition itself is not mutated" {
            val config = configWith("""{"agentName": "{{run.agentName}}"}""")
            bindings.applyTo(config)
            config.steps.single().input.params!!["agentName"].asText() shouldBe "{{run.agentName}}"
        }

        "the default workflow resolves to the config the scheduler used to synthesize" {
            val agentConfigId = UUID.randomUUID()
            val promptId = UUID.randomUUID()
            val scheduledPromptId = UUID.randomUUID()
            val runId = UUID.randomUUID()
            val resolved =
                WorkflowRunBindings(agentConfigId, "ProfileCaretaker", promptId, "Weekly digest", scheduledPromptId, runId)
                    .applyTo(DefaultWorkflowSeeder.defaultWorkflow().toLoopConfig())

            val expected =
                mapper.readTree(
                    """
                    {"version": 1, "steps": [
                      {"id": "list-audience", "tool": "ListAgentUsers",
                       "input": {"params": {"agentConfigId": "$agentConfigId"}, "from": null}},
                      {"id": "fan-out", "tool": "BatchCaseStarter",
                       "input": {"from": "list-audience", "params": {
                         "agentName": "ProfileCaretaker", "promptId": "$promptId", "title": "Weekly digest",
                         "scheduledPromptId": "$scheduledPromptId", "runId": "$runId"}}}
                    ]}
                    """.trimIndent(),
                )
            mapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(resolved) shouldBe expected
        }
    })
