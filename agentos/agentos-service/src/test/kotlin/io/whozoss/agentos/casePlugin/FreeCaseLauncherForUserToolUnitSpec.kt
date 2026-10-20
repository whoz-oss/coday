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
 * Unit tests for [FreeCaseLauncherForUserTool], the launcher for a user the caller names: its required `userId`, its
 * fixed schema and the hand-off to [CaseLaunchService]. What it shares with [FreeCaseLauncherTool] (allowlist,
 * origin case, output) lives in `CaseLauncher`, whose behaviour each tool exposes and is therefore checked in both
 * specs. The ADMIN right of the caller and the access of the target are owned by [CaseLaunchServiceUnitSpec].
 */
class FreeCaseLauncherForUserToolUnitSpec :
    StringSpec({

        val mapper = jacksonObjectMapper()
        val namespaceId: UUID = UUID.randomUUID()
        val callerCaseId: UUID = UUID.randomUUID()
        val userId: UUID = UUID.randomUUID()
        val targetId: UUID = UUID.randomUUID()
        val newCaseId: UUID = UUID.randomUUID()

        fun caseEvents(): List<CaseEvent> = listOf(WarnEvent(namespaceId = namespaceId, caseId = callerCaseId, message = "x"))

        fun context(events: List<CaseEvent> = caseEvents()) =
            ToolContext(namespaceId = namespaceId, userId = userId, userExternalId = "ext", caseEvents = events)

        fun launched() = LaunchedCase(caseId = newCaseId, userId = targetId, agentName = "Reporter")

        fun service() = mockk<CaseLaunchService> { every { start(any()) } returns launched() }

        fun refusingService(reason: CaseLaunchRefusal) =
            mockk<CaseLaunchService> { every { start(any()) } throws CaseLaunchRefusedException(reason, "No.") }

        fun tool(
            launcher: CaseLaunchService = service(),
            configName: String? = null,
            allowedAgents: List<String> = listOf("*"),
            deniedAgents: Set<String> = emptySet(),
        ) = FreeCaseLauncherForUserTool(
            configName = configName,
            caseLaunchService = launcher,
            allowedAgents = allowedAgents,
            deniedAgents = deniedAgents,
        )

        fun input(
            user: String = targetId.toString(),
            agent: String = "Reporter",
        ) = FreeCaseLauncherForUserTool.Input(agentName = agent, prompt = "prompt", userId = user)

        "is named FreeCaseLauncherForUser, prefixed by the integration config name when there is one" {
            tool().name shouldBe "FreeCaseLauncherForUser"
            tool(configName = "CASE").name shouldBe "CASE__FreeCaseLauncherForUser"
        }

        "its name is told apart from the launcher for the current user, whichever the prefix" {
            FreeCaseLauncherForUserTool.isFreeCaseLauncherForUserTool("CASE__FreeCaseLauncherForUser") shouldBe true
            FreeCaseLauncherForUserTool.isFreeCaseLauncherForUserTool("CASE__FreeCaseLauncher") shouldBe false
            FreeCaseLauncherTool.isFreeCaseLauncherTool("CASE__FreeCaseLauncherForUser") shouldBe false
        }

        // -------------------------------------------------------------------------
        // userId is required, and handed to the service
        // -------------------------------------------------------------------------

        "the userId is handed to the service, which owns the ADMIN and target user checks" {
            val request = slot<CaseLaunchRequest>()
            val launcher = mockk<CaseLaunchService> { every { start(capture(request)) } returns launched() }

            tool(launcher).execute(input(), context())

            request.captured.also {
                it.targetUserId shouldBe targetId
                it.callerUserId shouldBe userId
                it.callerNamespaceId shouldBe namespaceId
                it.callerCaseId shouldBe callerCaseId
            }
        }

        "a blank userId is MISSING_INPUT, and nothing is launched" {
            val launcher = service()

            tool(launcher).execute(input(user = "  "), context()).errorType shouldBe "MISSING_INPUT"
            verify(exactly = 0) { launcher.start(any()) }
        }

        "a userId absent from the JSON fails the deserialization, like a missing agentName or prompt, and nothing is launched" {
            val launcher = service()

            shouldThrow<Exception> {
                tool(launcher).executeWithJson("""{"agentName": "Reporter", "prompt": "go"}""", context())
            }
            verify(exactly = 0) { launcher.start(any()) }
        }

        "a userId that is not a UUID is INVALID_INPUT, and nothing is launched" {
            val launcher = service()

            val result = tool(launcher).execute(input(user = "alice@example.com"), context())

            result.errorType shouldBe "INVALID_INPUT"
            verify(exactly = 0) { launcher.start(any()) }
        }

        "null input is MISSING_INPUT" {
            tool().execute(null, context()).errorType shouldBe "MISSING_INPUT"
        }

        "a caller who is not an ADMIN of the namespace gets the refusal of the service" {
            val result = tool(refusingService(CaseLaunchRefusal.FORBIDDEN)).execute(input(), context())

            result.success shouldBe false
            result.errorType shouldBe "FORBIDDEN"
        }

        "every refusal reason reaches the caller as its errorType" {
            CaseLaunchRefusal.entries.forEach { reason ->
                tool(refusingService(reason)).execute(input(), context()).errorType shouldBe reason.name
            }
        }

        "a failure of the service is not caught by the tool: it is left to the agent layer" {
            val launcher = mockk<CaseLaunchService> { every { start(any()) } throws IllegalStateException("Neo4j unavailable") }

            shouldThrow<IllegalStateException> { tool(launcher).execute(input(), context()) }
        }

        // -------------------------------------------------------------------------
        // What the tool still checks itself
        // -------------------------------------------------------------------------

        "a run without a case to record as the origin is refused, before anything is launched" {
            val launcher = service()

            tool(launcher).execute(input(), context(events = emptyList())).errorType shouldBe "MISSING_CASE_CONTEXT"
            verify(exactly = 0) { launcher.start(any()) }
        }

        "an agent outside the allowed patterns, or denied, is UNAUTHORIZED_AGENT, before anything is launched" {
            val launcher = service()

            tool(launcher, allowedAgents = listOf("Profile*")).execute(input(), context()).errorType shouldBe "UNAUTHORIZED_AGENT"
            tool(launcher, deniedAgents = setOf("reporter")).execute(input(), context()).errorType shouldBe "UNAUTHORIZED_AGENT"
            verify(exactly = 0) { launcher.start(any()) }
        }

        "the output is the case id, as for the other launcher, so that a caller reads either the same way" {
            val result = tool().execute(input(), context())

            result.success shouldBe true
            result.metadata["caseId"] shouldBe newCaseId.toString()
            mapper.treeToValue(result.structuredOutput, CaseLauncherOutput::class.java) shouldBe
                CaseLauncherOutput(caseId = newCaseId)
        }

        // -------------------------------------------------------------------------
        // Schema and description
        // -------------------------------------------------------------------------

        "the input schema declares exactly the fields of Input, and requires the userId" {
            val everyField = FreeCaseLauncherForUserTool.Input("agent", "prompt", "user", "title", emptyMap())
            val inputFields = mapper.valueToTree<JsonNode>(everyField).fieldNames().asSequence().toList()
            val schema = mapper.readTree(tool().inputSchema)

            schema["properties"].fieldNames().asSequence().toList() shouldContainExactlyInAnyOrder inputFields
            schema["required"].map { it.asText() } shouldContainExactlyInAnyOrder listOf("agentName", "prompt", "userId")
        }

        "the schema is the same whatever the configuration: it is fixed by the tool" {
            listOf(tool(), tool(allowedAgents = listOf("Profile*"), deniedAgents = setOf("x")), tool(configName = "CASE"))
                .map { it.inputSchema }
                .toSet()
                .size shouldBe 1
        }

        "the description says the case runs for the given user, and that only an administrator can name another one" {
            tool().description shouldContain "given by userId"
            tool().description shouldContain "administrator of the namespace"
        }

        "the description lists the allowed and the denied agents, each only when they restrict something" {
            tool().description shouldNotContain "Agents that can be started"
            tool().description shouldNotContain "can never be started"
            tool(allowedAgents = listOf("Profile*"), deniedAgents = setOf("ProfileAdmin")).description.also {
                it shouldContain "Agents that can be started: Profile*."
                it shouldContain "Agents that can never be started: ProfileAdmin."
            }
        }
    })
