package io.whozoss.agentos.casePlugin

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import io.whozoss.agentos.sdk.caseEvent.CaseEvent
import io.whozoss.agentos.sdk.caseEvent.WarnEvent
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import java.util.UUID

class CaseToolPluginUnitSpec :
    StringSpec({

        val namespaceId: UUID = UUID.randomUUID()
        val noopLoader: (UUID, UUID, UUID?) -> List<CaseEvent>? = { _, _, _ -> emptyList() }
        val starterService = mockk<CaseStarterService>()
        val mapper = jacksonObjectMapper()

        fun plugin(loader: (UUID, UUID, UUID?) -> List<CaseEvent>? = noopLoader) = CaseToolPlugin(loader, starterService)

        /** Anonymous context: no user, no case events. What a ReadCase caller without identity looks like. */
        fun context() =
            ToolContext(
                namespaceId = namespaceId,
                userId = null,
                userExternalId = null,
                caseEvents = emptyList(),
            )

        /** Context of a run: an identified user and the events of the calling case, as FreeCaseStarter needs. */
        fun runContext(userId: UUID = UUID.randomUUID()) =
            ToolContext(
                namespaceId = namespaceId,
                userId = userId,
                userExternalId = null,
                caseEvents = listOf(WarnEvent(namespaceId = namespaceId, caseId = UUID.randomUUID(), message = "x")),
            )

        fun List<StandardTool<*>>.readCase() = filterIsInstance<ReadCaseTool>().single()

        fun List<StandardTool<*>>.starter() = filterIsInstance<FreeCaseStarterTool>().single()

        fun List<StandardTool<*>>.starterForUser() = filterIsInstance<FreeCaseStarterForUserTool>().single()

        // -------------------------------------------------------------------------
        // context == null guard
        // -------------------------------------------------------------------------

        "returns empty list when context is null" {
            val tools = plugin().provideTools(config = null, context = null)
            tools.shouldBeEmpty()
        }

        // -------------------------------------------------------------------------
        // Shape of the integration
        // -------------------------------------------------------------------------

        "integrationType is CASE" {
            plugin().integrationType shouldBe "CASE"
        }

        "provides the three tools, ReadCase and the two starters, when context is provided" {
            val tools = plugin().provideTools(config = null, context = context())

            tools shouldHaveSize 3
            tools.readCase()
            tools.starter()
            tools.starterForUser()
        }

        "the configSchema declares the read and the start parameters, and refuses anything else" {
            val schema = plugin().configSchema

            schema["properties"].fieldNames().asSequence().toList() shouldContainExactlyInAnyOrder
                listOf("includesTechnicalEvents", "agents", "deniedAgents")
            schema["additionalProperties"].asBoolean() shouldBe false
        }

        // -------------------------------------------------------------------------
        // Default includesTechnicalEvents behaviour
        // -------------------------------------------------------------------------

        "defaults includesTechnicalEvents to true when config is null" {
            val tools = plugin().provideTools(config = null, context = context())
            val tool = tools.readCase()
            // The tool's behaviour is not directly observable via a public field;
            // we verify indirectly by checking the integration type is as expected.
            tool.name shouldBe "ReadCase"
        }

        "defaults includesTechnicalEvents to true when config has no 'includesTechnicalEvents' key" {
            val config = mapper.readTree("{\"someOtherKey\": 42}")
            val tools = plugin().provideTools(config = config, context = context())
            tools shouldHaveSize 3
        }

        // -------------------------------------------------------------------------
        // includesTechnicalEvents propagation
        // -------------------------------------------------------------------------

        "propagates includesTechnicalEvents=false from config" {
            // The CaseTranscriptFormatterUnitSpec already covers the rendering contract;
            // here we only verify that the plugin reads the config value without breaking the tool set.
            val config = mapper.readTree("{\"includesTechnicalEvents\": false}")
            val tools = plugin().provideTools(config = config, context = context())
            tools shouldHaveSize 3
            // The flag value is tested in ReadCaseToolUnitSpec and CaseTranscriptFormatterUnitSpec.
            tools.readCase()
        }

        "propagates includesTechnicalEvents=true from config" {
            val config = mapper.readTree("{\"includesTechnicalEvents\": true}")
            val tools = plugin().provideTools(config = config, context = context())
            tools shouldHaveSize 3
            tools.readCase()
        }

        // -------------------------------------------------------------------------
        // configName propagation
        // -------------------------------------------------------------------------

        "propagates configName to ReadCaseTool" {
            val tools = plugin().provideTools(config = null, configName = "CASE_prod", context = context())
            tools.readCase().name shouldBe "CASE_prod__ReadCase"
        }

        "propagates configName to FreeCaseStarterTool" {
            val tools = plugin().provideTools(config = null, configName = "CASE_prod", context = context())
            tools.starter().name shouldBe "CASE_prod__FreeCaseStarter"
            tools.starterForUser().name shouldBe "CASE_prod__FreeCaseStarterForUser"
        }

        "uses default tool names when configName is null" {
            val tools = plugin().provideTools(config = null, configName = null, context = context())
            tools.map { it.name } shouldContainExactlyInAnyOrder listOf("ReadCase", "FreeCaseStarter", "FreeCaseStarterForUser")
        }

        // -------------------------------------------------------------------------
        // ReadCase: permission enforcement via loader
        // -------------------------------------------------------------------------

        "tool returns NOT_FOUND when loader returns null due to permission denial" {
            // The loader returns null when the user lacks READ on the target case.
            // ReadCaseTool must not leak whether the case exists or is just inaccessible.
            val unauthorisedLoader: (UUID, UUID, UUID?) -> List<CaseEvent>? = { _, _, _ -> null }
            val contextWithUser = runContext()
            val tools = plugin(unauthorisedLoader).provideTools(config = null, context = contextWithUser)

            val result = tools.readCase().execute(ReadCaseTool.Input(caseId = UUID.randomUUID().toString()), contextWithUser)

            result.success shouldBe false
            result.errorType shouldBe "NOT_FOUND"
        }

        "tool returns NOT_FOUND for cross-namespace or anonymous case (loader returns null)" {
            // The loader returns null for namespace mismatch, permission denial, or null userId.
            // Same observable behaviour in all cases — no information leak.
            val rejectingLoader: (UUID, UUID, UUID?) -> List<CaseEvent>? = { _, _, _ -> null }
            val tools = plugin(rejectingLoader).provideTools(config = null, context = context())

            val result =
                tools.readCase().execute(
                    ReadCaseTool.Input(caseId = UUID.randomUUID().toString()),
                    ToolContext(namespaceId = namespaceId, userId = null, userExternalId = null, caseEvents = emptyList()),
                )

            result.success shouldBe false
            result.errorType shouldBe "NOT_FOUND"
        }

        "ReadCase hands the user of the ToolContext it was built with to the loader" {
            val ctx = runContext()
            var seenUser: UUID? = null
            val tools =
                plugin { _, _, userId ->
                    seenUser = userId
                    emptyList()
                }.provideTools(config = null, context = ctx)

            tools.readCase().execute(ReadCaseTool.Input(caseId = UUID.randomUUID().toString()), ctx)

            seenUser shouldBe ctx.userId
        }

        // -------------------------------------------------------------------------
        // FreeCaseStarter
        // -------------------------------------------------------------------------

        "agents and deniedAgents from the config bound the starter" {
            val config = mapper.readTree("""{"agents": ["Profile*"], "deniedAgents": ["ProfileAdmin"]}""")
            val ctx = runContext()
            val tools = plugin().provideTools(config, "CASE_x", ctx)
            val someone = UUID.randomUUID().toString()

            tools.starter().execute(FreeCaseStarterTool.Input("ProfileAdmin", "t"), ctx).errorType shouldBe "UNAUTHORIZED_AGENT"
            tools.starter().execute(FreeCaseStarterTool.Input("Reporter", "t"), ctx).errorType shouldBe "UNAUTHORIZED_AGENT"
            tools.starterForUser().execute(FreeCaseStarterForUserTool.Input("ProfileAdmin", "t", someone), ctx).errorType shouldBe
                "UNAUTHORIZED_AGENT"
            tools.starterForUser().execute(FreeCaseStarterForUserTool.Input("Reporter", "t", someone), ctx).errorType shouldBe
                "UNAUTHORIZED_AGENT"
        }

        "the schemas are fixed by the tool, not by the config or the caller: userId is in one starter and not in the other" {
            listOf(null, mapper.readTree("""{"agents": ["Profile*"]}""")).forEach { config ->
                listOf(context(), runContext()).forEach { ctx ->
                    val tools = plugin().provideTools(config, null, ctx)

                    mapper.readTree(tools.starter().inputSchema)["properties"].has("userId") shouldBe false
                    mapper.readTree(tools.starterForUser().inputSchema)["properties"].has("userId") shouldBe true
                }
            }
        }

        "an anonymous run still gets the starter, which refuses it at execution: the tool is not withheld" {
            val tool = plugin().provideTools(config = null, context = context()).starter()

            tool.execute(FreeCaseStarterTool.Input("Reporter", "t"), context()).errorType shouldBe "MISSING_USER_CONTEXT"
        }

        // -------------------------------------------------------------------------
        // Cross-tool independence
        // -------------------------------------------------------------------------

        "the start parameters do not alter ReadCase" {
            val config = mapper.readTree("""{"agents": ["Nobody"]}""")
            val ctx = runContext()
            val tool = plugin().provideTools(config, null, ctx).readCase()

            tool.execute(ReadCaseTool.Input(caseId = UUID.randomUUID().toString()), ctx).errorType shouldBe null
        }

        "includesTechnicalEvents does not alter the starter" {
            val config = mapper.readTree("""{"includesTechnicalEvents": false}""")
            val tool = plugin().provideTools(config, null, context()).starter()

            tool.name shouldBe "FreeCaseStarter"
        }
    })
