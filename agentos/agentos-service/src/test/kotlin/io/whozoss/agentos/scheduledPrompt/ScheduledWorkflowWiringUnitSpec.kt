package io.whozoss.agentos.scheduledPrompt

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.whozoss.agentos.workflow.DefaultWorkflowSeeder
import io.whozoss.agentos.workflow.Workflow
import io.whozoss.agentos.workflow.WorkflowStep
import io.whozoss.agentos.workflow.WorkflowTargets
import java.util.UUID

class ScheduledWorkflowWiringUnitSpec :
    StringSpec({
        val mapper = jacksonObjectMapper()
        val values =
            ScheduledWorkflowWiring.RunValues(
                agentConfigId = UUID.randomUUID(),
                agentName = "ProfileCaretaker",
                promptId = UUID.randomUUID(),
                title = "Weekly digest",
                scheduledPromptId = UUID.randomUUID(),
                runId = UUID.randomUUID(),
                workflowInput = null,
            )

        "the user-group workflow lists the agent's audience then starts cases with the scheduled prompt" {
            val (search, start) = ScheduledWorkflowWiring.toLoopConfig(DefaultWorkflowSeeder.defaultWorkflow(), values).steps

            search.tool shouldBe "listAgentUsers"
            search.input shouldBe mapper.readTree("""{"agentConfigId":"${values.agentConfigId}"}""")
            search.feedsNext shouldBe WorkflowTargets.FIELD

            start.tool shouldBe Workflow.BATCH_CASE_STARTER_TOOL
            start.input shouldBe
                mapper.readTree(
                    """
                    {"agentName":"ProfileCaretaker","promptId":"${values.promptId}","title":"Weekly digest",
                     "scheduledPromptId":"${values.scheduledPromptId}","runId":"${values.runId}"}
                    """.trimIndent(),
                )
            start.feedsNext shouldBe null
        }

        "the scheduled prompt's workflowInput is the search step input" {
            val workflow = Workflow(name = "talents", steps = listOf(WorkflowStep("s", "WHOZ__SearchTalents"), WorkflowStep("c", "BatchCaseStarter")))
            val criteria = mapper.readTree("""{"completionRate":{"max":19}}""")

            val search = ScheduledWorkflowWiring.toLoopConfig(workflow, values.copy(workflowInput = criteria)).steps.first()

            search.tool shouldBe "WHOZ__SearchTalents"
            search.input["completionRate"]["max"].asInt() shouldBe 19
            search.input["agentConfigId"].asText() shouldBe values.agentConfigId.toString()
        }

        "a workflow without its two steps is refused" {
            shouldThrow<IllegalArgumentException> {
                ScheduledWorkflowWiring.toLoopConfig(Workflow(name = "x", steps = listOf(WorkflowStep("s", "T"))), values)
            }
        }
    })
