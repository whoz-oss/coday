package io.whozoss.agentos.casePlugin

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.mockk
import io.whozoss.agentos.sdk.tool.ToolContext
import java.util.UUID

class CaseLauncherToolPluginUnitSpec :
    StringSpec({

        val mapper = jacksonObjectMapper()
        val launcher = mockk<CaseLaunchService>()
        val namespaceId = UUID.randomUUID()
        val context =
            ToolContext(
                namespaceId = namespaceId,
                userId = UUID.randomUUID(),
                userExternalId = null,
                caseEvents = emptyList(),
                caseId = UUID.randomUUID(),
            )

        fun plugin() = CaseLauncherToolPlugin(launcher)

        "integrationType is CASE_LAUNCHER, distinct from the read-only CASE" {
            plugin().integrationType shouldBe "CASE_LAUNCHER"
        }

        "provides a single CreateCaseTool" {
            val tools = plugin().provideTools(config = null, configName = null, context = context)

            tools shouldHaveSize 1
            tools.single().shouldBeInstanceOf<CreateCaseTool>()
        }

        "prefixes the tool name with the config name" {
            plugin().provideTools(config = null, configName = "LAUNCHER", context = context).single().name shouldBe "LAUNCHER__CreateCase"
        }

        "the configSchema declares agents, deniedAgents and allowOtherUsers, and refuses anything else" {
            val schema = plugin().configSchema

            schema["properties"].fieldNames().asSequence().toList() shouldBe listOf("agents", "deniedAgents", "allowOtherUsers")
            schema["properties"]["allowOtherUsers"]["default"].asBoolean() shouldBe false
            schema["additionalProperties"].asBoolean() shouldBe false
        }

        "agents and deniedAgents from the config bound the tool" {
            val config = mapper.readTree("""{"agents": ["Profile*"], "deniedAgents": ["ProfileAdmin"]}""")
            val tool = plugin().provideTools(config, "LAUNCHER", context).single() as CreateCaseTool

            tool.execute(CreateCaseTool.Input("ProfileAdmin", "t"), context).errorType shouldBe "UNAUTHORIZED_AGENT"
            tool.execute(CreateCaseTool.Input("Reporter", "t"), context).errorType shouldBe "UNAUTHORIZED_AGENT"
        }

        "other users are off unless the config turns them on" {
            val off = plugin().provideTools(null, null, context).single()
            val explicitOff = plugin().provideTools(mapper.readTree("""{"allowOtherUsers": false}"""), null, context).single()
            val on = plugin().provideTools(mapper.readTree("""{"allowOtherUsers": true}"""), null, context).single()

            mapper.readTree(off.inputSchema)["properties"].has("userId") shouldBe false
            mapper.readTree(explicitOff.inputSchema)["properties"].has("userId") shouldBe false
            mapper.readTree(on.inputSchema)["properties"].has("userId") shouldBe true
        }
    })
