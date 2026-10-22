package io.whozoss.agentos.casePlugin

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.whozoss.agentos.config.LimitsConfigProperties
import io.whozoss.agentos.sdk.caseEvent.CaseEvent
import io.whozoss.agentos.sdk.tool.ToolContext
import java.util.UUID

/**
 * Unit tests for the wiring of [CasePluginConfiguration]: it holds no business logic, so what is checked here is
 * that the plugin it produces reads cases through [CaseToolService] and starts them through [CaseStarterService].
 * The guards of the read side (existence, namespace, Case READ) are in [CaseToolServiceUnitSpec].
 */
class CasePluginConfigurationUnitSpec :
    StringSpec({

        val namespaceId: UUID = UUID.randomUUID()
        val userId: UUID = UUID.randomUUID()

        fun context(user: UUID?) =
            ToolContext(namespaceId = namespaceId, userId = user, userExternalId = null, caseEvents = emptyList())

        "ReadCase reads through the CaseToolService, with the namespace and the user of the ToolContext" {
            val caseId = UUID.randomUUID()
            val caseToolService = mockk<CaseToolService>()
            every { caseToolService.findCaseEvents(caseId, namespaceId, userId) } returns emptyList<CaseEvent>()
            val plugin = CasePluginConfiguration(caseToolService, mockk<CaseStarterService>(), LimitsConfigProperties()).caseToolPlugin() as CaseToolPlugin
            val ctx = context(userId)
            val readCase = plugin.provideTools(config = null, context = ctx).filterIsInstance<ReadCaseTool>().single()

            val result = readCase.execute(ReadCaseTool.Input(caseId = caseId.toString()), ctx)

            result.success shouldBe true
            verify(exactly = 1) { caseToolService.findCaseEvents(caseId, namespaceId, userId) }
        }

        "a null from the CaseToolService is the NOT_FOUND of ReadCase, whatever the reason" {
            val caseId = UUID.randomUUID()
            val caseToolService = mockk<CaseToolService>()
            every { caseToolService.findCaseEvents(any(), any(), any()) } returns null
            val plugin = CasePluginConfiguration(caseToolService, mockk<CaseStarterService>(), LimitsConfigProperties()).caseToolPlugin() as CaseToolPlugin
            val ctx = context(userId)
            val readCase = plugin.provideTools(config = null, context = ctx).filterIsInstance<ReadCaseTool>().single()

            val result = readCase.execute(ReadCaseTool.Input(caseId = caseId.toString()), ctx)

            result.success shouldBe false
            result.errorType shouldBe "NOT_FOUND"
        }

        "an anonymous run hands a null user to the CaseToolService, which decides" {
            val caseId = UUID.randomUUID()
            val caseToolService = mockk<CaseToolService>()
            every { caseToolService.findCaseEvents(caseId, namespaceId, null) } returns null
            val plugin = CasePluginConfiguration(caseToolService, mockk<CaseStarterService>(), LimitsConfigProperties()).caseToolPlugin() as CaseToolPlugin
            val ctx = context(null)
            val readCase = plugin.provideTools(config = null, context = ctx).filterIsInstance<ReadCaseTool>().single()

            readCase.execute(ReadCaseTool.Input(caseId = caseId.toString()), ctx).errorType shouldBe "NOT_FOUND"

            verify(exactly = 1) { caseToolService.findCaseEvents(caseId, namespaceId, null) }
        }

        "the plugin it produces is the CASE integration, with its three tools" {
            val caseToolService = mockk<CaseToolService>()
            val plugin = CasePluginConfiguration(caseToolService, mockk<CaseStarterService>(), LimitsConfigProperties()).caseToolPlugin()

            plugin.integrationType shouldBe CaseToolPlugin.INTEGRATION_TYPE
            plugin.provideTools(config = null, context = context(userId)).map { it.name } shouldBe
                listOf("ReadCase", "FreeCaseStarter", "FreeCaseStarterForUser", "BatchCaseStarter")
        }
    })
