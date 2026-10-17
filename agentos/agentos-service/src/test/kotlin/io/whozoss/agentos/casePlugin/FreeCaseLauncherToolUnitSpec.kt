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
 * Unit tests for [FreeCaseLauncherTool]: input and output contract, the agent allowlist, the `userId`
 * switch, schema and the hand-off to [CaseLaunchService]. The target user rules and agent access are in
 * [CaseLaunchServiceUnitSpec].
 */
class FreeCaseLauncherToolUnitSpec :
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

        fun launched(agentName: String = "Reporter") =
            CaseLaunchOutcome.Launched(caseId = newCaseId, userId = userId, agentName = agentName)

        fun service(outcome: CaseLaunchOutcome = launched()) = mockk<CaseLaunchService> { every { launch(any()) } returns outcome }

        fun tool(
            launcher: CaseLaunchService = service(),
            configName: String? = null,
            allowedAgents: List<String> = listOf("*"),
            deniedAgents: Set<String> = emptySet(),
            allowOtherUsers: Boolean = false,
        ) = FreeCaseLauncherTool(
            configName = configName,
            caseLaunchService = launcher,
            allowedAgents = allowedAgents,
            deniedAgents = deniedAgents,
            allowOtherUsers = allowOtherUsers,
        )

        "is named FreeCaseLauncher, prefixed by the integration config name when there is one" {
            tool().name shouldBe "FreeCaseLauncher"
            tool(configName = "LAUNCHER").name shouldBe "LAUNCHER__FreeCaseLauncher"
        }

        // -------------------------------------------------------------------------
        // Input validation
        // -------------------------------------------------------------------------

        "null input is MISSING_INPUT" {
            val result = tool().execute(null, context())

            result.success shouldBe false
            result.errorType shouldBe "MISSING_INPUT"
        }

        "a blank agentName or prompt is MISSING_INPUT, and nothing is launched" {
            val launcher = service()
            val t = tool(launcher)

            t.execute(FreeCaseLauncherTool.Input(agentName = " ", prompt = "x"), context()).errorType shouldBe "MISSING_INPUT"
            t.execute(FreeCaseLauncherTool.Input(agentName = "Reporter", prompt = ""), context()).errorType shouldBe "MISSING_INPUT"
            verify(exactly = 0) { launcher.launch(any()) }
        }

        "a run without a user is refused before anything is launched" {
            val launcher = service()

            val result = tool(launcher).execute(FreeCaseLauncherTool.Input("Reporter", "prompt"), context(user = null))

            result.errorType shouldBe "MISSING_USER_CONTEXT"
            verify(exactly = 0) { launcher.launch(any()) }
        }

        "a run whose case cannot be identified, with no case events, is refused: the origin would be unknown" {
            val launcher = service()

            val result = tool(launcher).execute(FreeCaseLauncherTool.Input("Reporter", "prompt"), context(events = emptyList()))

            result.errorType shouldBe "MISSING_CASE_CONTEXT"
            verify(exactly = 0) { launcher.launch(any()) }
        }

        "the calling case is the one of the case events of the ToolContext, never an input of the LLM" {
            val request = slot<CaseLaunchRequest>()
            val launcher = mockk<CaseLaunchService> { every { launch(capture(request)) } returns launched() }
            val forged = UUID.randomUUID()

            tool(launcher).executeWithJson(
                """{"agentName": "Reporter", "prompt": "go", "callerCaseId": "$forged", "caseId": "$forged"}""",
                context(),
            )

            request.captured.callerCaseId shouldBe callerCaseId
        }

        "unknown fields in the LLM's JSON are ignored, and never read as identity" {
            val request = slot<CaseLaunchRequest>()
            val launcher = mockk<CaseLaunchService> { every { launch(capture(request)) } returns launched() }

            val result =
                tool(launcher).executeWithJson(
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
            val launcher = service()
            val t = tool(launcher, allowedAgents = listOf("Profile*"))

            t.execute(FreeCaseLauncherTool.Input("Reporter", "prompt"), context()).errorType shouldBe "UNAUTHORIZED_AGENT"
            verify(exactly = 0) { launcher.launch(any()) }
        }

        "an agent matching a pattern is launched, whatever its casing" {
            val launcher = service()

            val result =
                tool(launcher, allowedAgents = listOf("profile*"))
                    .execute(FreeCaseLauncherTool.Input("ProfileCaretaker", "prompt"), context())

            result.success shouldBe true
            verify(exactly = 1) { launcher.launch(any()) }
        }

        "a denied agent is refused even when an allowed pattern matches it" {
            val launcher = service()
            val t = tool(launcher, allowedAgents = listOf("*"), deniedAgents = setOf("reporter"))

            t.execute(FreeCaseLauncherTool.Input("Reporter", "prompt"), context()).errorType shouldBe "UNAUTHORIZED_AGENT"
            verify(exactly = 0) { launcher.launch(any()) }
        }

        // -------------------------------------------------------------------------
        // Another user
        // -------------------------------------------------------------------------

        "the integration's allowOtherUsers switch is handed to the service, which enforces it" {
            listOf(false, true).forEach { allowed ->
                val request = slot<CaseLaunchRequest>()
                val launcher = mockk<CaseLaunchService> { every { launch(capture(request)) } returns launched() }

                tool(launcher, allowOtherUsers = allowed)
                    .execute(FreeCaseLauncherTool.Input("Reporter", "prompt", userId = targetId.toString()), context())

                request.captured.allowOtherUsers shouldBe allowed
                request.captured.targetUserId shouldBe targetId
            }
        }

        "a userId the service refuses because other users are not allowed comes back as its refusal" {
            val refused =
                CaseLaunchOutcome.Refused(reason = CaseLaunchRefusal.OTHER_USERS_NOT_ALLOWED, message = "No.")

            val result =
                tool(service(refused), allowOtherUsers = false)
                    .execute(FreeCaseLauncherTool.Input("Reporter", "prompt", userId = targetId.toString()), context())

            result.success shouldBe false
            result.errorType shouldBe "OTHER_USERS_NOT_ALLOWED"
        }

        "a userId that is not a UUID is INVALID_INPUT, whether or not other users are allowed" {
            tool(allowOtherUsers = false)
                .execute(FreeCaseLauncherTool.Input("Reporter", "prompt", userId = "alice@example.com"), context())
                .errorType shouldBe "INVALID_INPUT"
        }

        "a userId that is not a UUID is INVALID_INPUT" {
            val launcher = service()

            val result =
                tool(launcher, allowOtherUsers = true)
                    .execute(FreeCaseLauncherTool.Input("Reporter", "prompt", userId = "alice@example.com"), context())

            result.errorType shouldBe "INVALID_INPUT"
            verify(exactly = 0) { launcher.launch(any()) }
        }

        "when allowed, the userId is handed to the service, which owns the target user checks" {
            val request = slot<CaseLaunchRequest>()
            val launcher = mockk<CaseLaunchService> { every { launch(capture(request)) } returns launched() }

            tool(launcher, allowOtherUsers = true)
                .execute(FreeCaseLauncherTool.Input("Reporter", "prompt", userId = targetId.toString()), context())

            request.captured.targetUserId shouldBe targetId
        }

        "a blank userId is treated as absent" {
            val request = slot<CaseLaunchRequest>()
            val launcher = mockk<CaseLaunchService> { every { launch(capture(request)) } returns launched() }

            tool(launcher).execute(FreeCaseLauncherTool.Input("Reporter", "prompt", userId = "  "), context())

            request.captured.targetUserId shouldBe null
        }

        // -------------------------------------------------------------------------
        // Hand-off: prompt, title, context
        // -------------------------------------------------------------------------

        "identity, namespace and the calling case come from the ToolContext, the prompt is the first message" {
            val request = slot<CaseLaunchRequest>()
            val launcher = mockk<CaseLaunchService> { every { launch(capture(request)) } returns launched() }

            tool(launcher).execute(FreeCaseLauncherTool.Input("Reporter", "Summarise"), context())

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
            val request = slot<CaseLaunchRequest>()
            val launcher = mockk<CaseLaunchService> { every { launch(capture(request)) } returns launched() }
            val ctx = mapOf("activeContext" to listOf(mapOf("type" to "TALENT", "data" to mapOf("id" to "t42"))))

            tool(launcher).execute(
                FreeCaseLauncherTool.Input("Reporter", "Summarise", title = "Weekly review", sessionContext = ctx),
                context(),
            )

            request.captured.title shouldBe "Weekly review"
            request.captured.sessionContext shouldBe ctx
        }

        // -------------------------------------------------------------------------
        // Output
        // -------------------------------------------------------------------------

        "the output is the case id, in the text, the metadata and the structured output" {
            val result = tool().execute(FreeCaseLauncherTool.Input("Reporter", "prompt"), context())

            result.success shouldBe true
            result.output shouldContain newCaseId.toString()
            result.metadata["caseId"] shouldBe newCaseId.toString()
            result.metadata["userId"] shouldBe userId.toString()
            mapper.treeToValue(result.structuredOutput, FreeCaseLauncherTool.Output::class.java) shouldBe
                FreeCaseLauncherTool.Output(caseId = newCaseId)
        }

        "the structured output is an Output, with exactly the fields the output schema declares, all required" {
            val t = tool()
            val schema = t.outputSchema()!!
            val result = t.execute(FreeCaseLauncherTool.Input("Reporter", "prompt"), context())
            val declared = schema["properties"].fieldNames().asSequence().toList()

            result.structuredOutput!!.fieldNames().asSequence().toList() shouldContainExactlyInAnyOrder declared
            schema["required"].map { it.asText() } shouldContainExactlyInAnyOrder declared
        }

        "a refusal becomes a tool error carrying its reason as errorType, and no structured output" {
            val t = tool(service(CaseLaunchOutcome.Refused(CaseLaunchRefusal.TARGET_NO_ACCESS, "No.")))

            val result = t.execute(FreeCaseLauncherTool.Input("Reporter", "prompt"), context())

            result.success shouldBe false
            result.errorType shouldBe "TARGET_NO_ACCESS"
            result.output shouldBe "No."
            result.structuredOutput shouldBe null
        }

        "a failure of the service is not caught by the tool: it is left to the agent layer" {
            val launcher =
                mockk<CaseLaunchService> { every { launch(any()) } throws IllegalStateException("Neo4j unavailable") }

            shouldThrow<IllegalStateException> {
                tool(launcher).execute(FreeCaseLauncherTool.Input("Reporter", "prompt"), context())
            }
        }

        // -------------------------------------------------------------------------
        // Schema and description
        // -------------------------------------------------------------------------

        "the input schema declares exactly the fields of Input, userId only when other users are allowed" {
            val everyField = FreeCaseLauncherTool.Input("agent", "prompt", "title", "user", emptyMap())
            val inputFields = mapper.valueToTree<JsonNode>(everyField).fieldNames().asSequence().toList()

            fun schema(allowOtherUsers: Boolean) = mapper.readTree(tool(allowOtherUsers = allowOtherUsers).inputSchema)

            schema(allowOtherUsers = true)["properties"].fieldNames().asSequence().toList() shouldContainExactlyInAnyOrder
                inputFields
            schema(allowOtherUsers = false)["properties"].fieldNames().asSequence().toList() shouldContainExactlyInAnyOrder
                inputFields - "userId"
            // userId is never required, even when it is offered
            schema(allowOtherUsers = true)["required"].map { it.asText() } shouldContainExactlyInAnyOrder
                listOf("agentName", "prompt")
        }

        "the description says the case lives on its own, is not stopped with the current one, and is not awaited" {
            tool().description shouldContain "lives on its own"
            tool().description shouldContain "does not stop it"
            tool().description shouldContain "without waiting"
        }

        "the description tells that the context is not inherited" {
            tool().description shouldContain "does not inherit"
        }

        "the description mentions userId only when other users are allowed" {
            tool().description shouldNotContain "userId"
            tool(allowOtherUsers = true).description shouldContain "userId"
        }

        "the description lists the allowed agents only when they are restricted" {
            tool().description shouldNotContain "Agents that can be started"
            tool(allowedAgents = listOf("Profile*")).description shouldContain "Agents that can be started: Profile*"
        }
    })
