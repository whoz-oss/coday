package io.whozoss.agentos.scheduledPrompt

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.whozoss.agentos.exception.BadRequestException
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.workflow.DefaultWorkflowSeeder
import io.whozoss.agentos.workflow.Workflow
import io.whozoss.agentos.workflow.WorkflowStep
import java.util.UUID

class ToolSchemaWorkflowInputValidatorUnitSpec :
    StringSpec({
        val mapper = jacksonObjectMapper()
        val namespaceId = UUID.randomUUID()
        val talents = Workflow(name = "talents", steps = listOf(WorkflowStep("s", "WHOZ__SearchTalents"), WorkflowStep("c", "BatchCaseStarter")))

        fun toolWithSchema(schema: String): StandardTool<*> = mockk<StandardTool<*>>().also { every { it.inputSchema } returns schema }

        fun validator(tool: StandardTool<*>?) = ToolSchemaWorkflowInputValidator { _, _ -> tool }

        val searchSchema = """{"type":"object","required":["scope"],"additionalProperties":false,"properties":{"scope":{"type":"string"}}}"""

        "a workflowInput matching the step-1 tool schema is accepted" {
            validator(toolWithSchema(searchSchema)).requireValid(talents, mapper.readTree("""{"scope":"TEAM"}"""), namespaceId)
        }

        "a workflowInput not matching the schema is refused with the problems" {
            shouldThrow<BadRequestException> {
                validator(toolWithSchema(searchSchema)).requireValid(talents, mapper.readTree("""{"scope":1,"x":true}"""), namespaceId)
            }.message shouldContain "unknown property 'x'"
        }

        "a step-1 tool not granted to the loop agent is refused" {
            shouldThrow<BadRequestException> { validator(null).requireValid(talents, null, namespaceId) }.message shouldContain
                "not available to the scheduler loop agent"
        }

        "a non-object workflowInput is refused" {
            shouldThrow<BadRequestException> {
                validator(toolWithSchema(searchSchema)).requireValid(talents, mapper.readTree("[1]"), namespaceId)
            }.message shouldContain "JSON object"
        }

        "the user-group workflow needs no workflowInput: agentConfigId is supplied by the wiring" {
            val listAgentUsersSchema = """{"type":"object","required":["agentConfigId"],"properties":{"agentConfigId":{"type":"string"}}}"""
            validator(toolWithSchema(listAgentUsersSchema)).requireValid(DefaultWorkflowSeeder.defaultWorkflow(), null, namespaceId)
        }
    })
