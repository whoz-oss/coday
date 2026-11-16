package io.whozoss.agentos.casePlugin

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.whozoss.agentos.sdk.caseEvent.CaseEvent
import io.whozoss.agentos.sdk.caseEvent.WarnEvent
import io.whozoss.agentos.sdk.tool.ToolContext
import java.util.UUID

/**
 * Unit tests for [FreeCaseStarterTool], the starter for the current user: input and output contract, the agent
 * allowlist, its fixed schema (no `userId`) and the hand-off to [CaseStarterService]. The starter for another user is
 * in [FreeCaseStarterForUserToolUnitSpec]; the target user rules (ADMIN of the namespace, access of the target)
 * and agent access are in [CaseStarterServiceUnitSpec].
 */
class FreeCaseStarterToolUnitSpec :
    StringSpec({

        val mapper = jacksonObjectMapper()
        val namespaceId: UUID = UUID.randomUUID()
        val callerCaseId: UUID = UUID.randomUUID()
        val userId: UUID = UUID.randomUUID()
        val targetId: UUID = UUID.randomUUID()
        val newCaseId: UUID = UUID.randomUUID()

        /** The events the calling case hands to the tool: its own, so the calling case is read from them. */
        fun caseEvents(caseId: UUID = callerCaseId): List<CaseEvent> = listOf(WarnEvent(namespaceId = namespaceId, caseId = caseId, message = "x"))

        fun context(
            user: UUID? = userId,
            events: List<CaseEvent> = caseEvents(),
        ) = ToolContext(namespaceId = namespaceId, userId = user, userExternalId = "ext", caseEvents = events)

        fun started(agentName: String = "Reporter") = StartedCase(caseId = newCaseId, userId = userId, agentName = agentName)

        fun service(started: StartedCase = started()) = mockk<CaseStarterService> { every { start(any()) } returns started }

        /** A service that refuses every start with [reason]. */
        fun refusingService(
            reason: CaseStarterRefusal,
            message: String = "No.",
        ) = mockk<CaseStarterService> { every { start(any()) } throws CaseStarterRefusedException(reason, message) }

        fun tool(
            starter: CaseStarterService = service(),
            configName: String? = null,
            allowedAgents: List<String> = listOf("*"),
            deniedAgents: Set<String> = emptySet(),
        ) = FreeCaseStarterTool(
            configName = configName,
            caseStarterService = starter,
            allowedAgents = allowedAgents,
            deniedAgents = deniedAgents,
        )

        "is named FreeCaseStarter, prefixed by the integration config name when there is one" {
            tool().name shouldBe "FreeCaseStarter"
            tool(configName = "STARTER").name shouldBe "STARTER__FreeCaseStarter"
        }

        // -------------------------------------------------------------------------
        // Input validation
        // -------------------------------------------------------------------------

        "null input is MISSING_INPUT" {
            val result = tool().execute(null, context())

            result.success shouldBe false
            result.errorType shouldBe "MISSING_INPUT"
        }

        "a blank agentName or prompt is MISSING_INPUT, and nothing is started" {
            val starter = service()
            val t = tool(starter)

            t.execute(FreeCaseStarterTool.Input(agentName = " ", prompt = "x"), context()).errorType shouldBe "MISSING_INPUT"
            t.execute(FreeCaseStarterTool.Input(agentName = "Reporter", prompt = ""), context()).errorType shouldBe "MISSING_INPUT"
            verify(exactly = 0) { starter.start(any()) }
        }

        "a run without a user is refused before anything is started" {
            val starter = service()

            val result = tool(starter).execute(FreeCaseStarterTool.Input("Reporter", "prompt"), context(user = null))

            result.errorType shouldBe "MISSING_USER_CONTEXT"
            verify(exactly = 0) { starter.start(any()) }
        }

        "a run whose case cannot be identified, with no case events, is refused: the origin would be unknown" {
            val starter = service()

            val result = tool(starter).execute(FreeCaseStarterTool.Input("Reporter", "prompt"), context(events = emptyList()))

            result.errorType shouldBe "MISSING_CASE_CONTEXT"
            verify(exactly = 0) { starter.start(any()) }
        }

        "the calling case is the one of the case events of the ToolContext, never an input of the LLM" {
            val request = slot<CaseStarterRequest>()
            val starter = mockk<CaseStarterService> { every { start(capture(request)) } returns started() }
            val forged = UUID.randomUUID()

            tool(starter).executeWithJson(
                """{"agentName": "Reporter", "prompt": "go", "callerCaseId": "$forged", "caseId": "$forged"}""",
                context(),
            )

            request.captured.callerCaseId shouldBe callerCaseId
        }

        "unknown fields in the LLM's JSON are ignored, and never read as identity" {
            val request = slot<CaseStarterRequest>()
            val starter = mockk<CaseStarterService> { every { start(capture(request)) } returns started() }

            val result =
                tool(starter).executeWithJson(
                    """{"agentName": "Reporter", "prompt": "go", "callerUserId": "forged", "namespaceId": "forged", "agentId": "forged"}""",
                    context(),
                )

            result.success shouldBe true
            request.captured.callerUserId shouldBe userId
            request.captured.callerNamespaceId shouldBe namespaceId
        }

        // -------------------------------------------------------------------------
        // Agent allowlist
        // -------------------------------------------------------------------------

        "an agent outside the allowed patterns is UNAUTHORIZED_AGENT" {
            val starter = service()
            val t = tool(starter, allowedAgents = listOf("Profile*"))

            t.execute(FreeCaseStarterTool.Input("Reporter", "prompt"), context()).errorType shouldBe "UNAUTHORIZED_AGENT"
            verify(exactly = 0) { starter.start(any()) }
        }

        "an agent matching a pattern is started, whatever its casing" {
            val starter = service()

            val result =
                tool(starter, allowedAgents = listOf("profile*"))
                    .execute(FreeCaseStarterTool.Input("ProfileCaretaker", "prompt"), context())

            result.success shouldBe true
            verify(exactly = 1) { starter.start(any()) }
        }

        "a denied agent is refused even when an allowed pattern matches it" {
            val starter = service()
            val t = tool(starter, allowedAgents = listOf("*"), deniedAgents = setOf("reporter"))

            t.execute(FreeCaseStarterTool.Input("Reporter", "prompt"), context()).errorType shouldBe "UNAUTHORIZED_AGENT"
            verify(exactly = 0) { starter.start(any()) }
        }

        // -------------------------------------------------------------------------
        // Always for the current user
        // -------------------------------------------------------------------------

        "a userId the LLM sends anyway is ignored: the case runs for the caller" {
            val request = slot<CaseStarterRequest>()
            val starter = mockk<CaseStarterService> { every { start(capture(request)) } returns started() }

            val result =
                tool(starter).executeWithJson(
                    """{"agentName": "Reporter", "prompt": "go", "userId": "$targetId"}""",
                    context(),
                )

            result.success shouldBe true
            request.captured.targetUserId shouldBe null
        }

        // -------------------------------------------------------------------------
        // Hand-off: prompt, title, context
        // -------------------------------------------------------------------------

        "identity, namespace and the calling case come from the ToolContext, the prompt is the first message" {
            val request = slot<CaseStarterRequest>()
            val starter = mockk<CaseStarterService> { every { start(capture(request)) } returns started() }

            tool(starter).execute(FreeCaseStarterTool.Input("Reporter", "Summarise"), context())

            request.captured.also {
                it.callerUserId shouldBe userId
                it.callerNamespaceId shouldBe namespaceId
                it.callerCaseId shouldBe callerCaseId
                it.agentName shouldBe "Reporter"
                it.prompt shouldBe "Summarise"
                it.targetUserId shouldBe null
                it.title shouldBe null
                it.sessionContext shouldBe null
            }
        }

        "the title and the session context are handed over" {
            val request = slot<CaseStarterRequest>()
            val starter = mockk<CaseStarterService> { every { start(capture(request)) } returns started() }
            val ctx = mapOf("activeContext" to listOf(mapOf("type" to "TALENT", "data" to mapOf("id" to "t42"))))

            tool(starter).execute(
                FreeCaseStarterTool.Input("Reporter", "Summarise", title = "Weekly review", sessionContext = ctx),
                context(),
            )

            request.captured.title shouldBe "Weekly review"
            request.captured.sessionContext shouldBe ctx
        }

        // -------------------------------------------------------------------------
        // Output
        // -------------------------------------------------------------------------

        "the output is the case id, in the text, the metadata and the structured output" {
            val result = tool().execute(FreeCaseStarterTool.Input("Reporter", "prompt"), context())

            result.success shouldBe true
            result.output shouldContain newCaseId.toString()
            result.metadata["caseId"] shouldBe newCaseId.toString()
            result.metadata["userId"] shouldBe userId.toString()
            mapper.treeToValue(result.structuredOutput, CaseStarterOutput::class.java) shouldBe
                CaseStarterOutput(caseId = newCaseId)
        }

        "the structured output is a CaseStarterOutput, with exactly the fields the output schema declares, all required" {
            val t = tool()
            val schema = t.outputSchema()!!
            val result = t.execute(FreeCaseStarterTool.Input("Reporter", "prompt"), context())
            val declared = schema["properties"].fieldNames().asSequence().toList()

            result.structuredOutput!!.fieldNames().asSequence().toList() shouldContainExactlyInAnyOrder declared
            schema["required"].map { it.asText() } shouldContainExactlyInAnyOrder declared
        }

        "a refusal becomes a tool error carrying its reason as errorType, and no structured output" {
            val t = tool(refusingService(CaseStarterRefusal.TARGET_NO_ACCESS))

            val result = t.execute(FreeCaseStarterTool.Input("Reporter", "prompt"), context())

            result.success shouldBe false
            result.errorType shouldBe "TARGET_NO_ACCESS"
            result.output shouldBe "No."
            result.structuredOutput shouldBe null
        }

        "every refusal reason reaches the LLM as its errorType, which AgentLoop reads: the names are a contract" {
            CaseStarterRefusal.entries.forEach { reason ->
                val result =
                    tool(refusingService(reason)).execute(FreeCaseStarterTool.Input("Reporter", "prompt"), context())

                result.errorType shouldBe reason.name
            }
            // AgentLoop counts this one as "no agent access": renaming it would silently change that.
            CaseStarterRefusal.AGENT_NOT_AVAILABLE.name shouldBe "AGENT_NOT_AVAILABLE"
        }

        "a failure of the service is not caught by the tool: it is left to the agent layer" {
            val starter =
                mockk<CaseStarterService> { every { start(any()) } throws IllegalStateException("Neo4j unavailable") }

            shouldThrow<IllegalStateException> {
                tool(starter).execute(FreeCaseStarterTool.Input("Reporter", "prompt"), context())
            }
        }

        // -------------------------------------------------------------------------
        // Schema and description
        // -------------------------------------------------------------------------

        "the input schema declares exactly the fields of Input, and no userId" {
            val everyField = FreeCaseStarterTool.Input("agent", "prompt", "title", emptyMap())
            val inputFields = mapper.valueToTree<JsonNode>(everyField).fieldNames().asSequence().toList()
            val schema = mapper.readTree(tool().inputSchema)

            schema["properties"].fieldNames().asSequence().toList() shouldContainExactlyInAnyOrder inputFields
            schema["properties"].has("userId") shouldBe false
            schema["required"].map { it.asText() } shouldContainExactlyInAnyOrder listOf("agentName", "prompt")
        }

        "the schema is the same whatever the configuration and the caller: it is fixed by the tool" {
            val schemas =
                listOf(
                    tool(),
                    tool(allowedAgents = listOf("Profile*"), deniedAgents = setOf("x")),
                    tool(configName = "STARTER"),
                ).map { it.inputSchema }

            schemas.toSet().size shouldBe 1
        }

        "the description says the case lives on its own, is not stopped with the current one, and is not awaited" {
            tool().description shouldContain "lives on its own"
            tool().description shouldContain "does not stop it"
            tool().description shouldContain "without waiting"
        }

        "the description tells that the context is not inherited" {
            tool().description shouldContain "does not inherit"
        }

        "the description says the case runs for the current user, and does not mention userId" {
            tool().description shouldContain "for the current user"
            tool().description shouldNotContain "userId"
        }

        "the description lists the allowed agents only when they are restricted" {
            tool().description shouldNotContain "Agents that can be started"
            tool(allowedAgents = listOf("Profile*")).description shouldContain "Agents that can be started: Profile*"
        }

        "the description lists the denied agents only when there are some, whether or not the allowed ones are restricted" {
            tool().description shouldNotContain "can never be started"
            tool(deniedAgents = setOf("Admin", "Billing")).description shouldContain
                "Agents that can never be started: Admin, Billing."
            tool(allowedAgents = listOf("Profile*"), deniedAgents = setOf("ProfileAdmin")).description.also {
                it shouldContain "Agents that can be started: Profile*."
                it shouldContain "Agents that can never be started: ProfileAdmin."
            }
        }
    })
