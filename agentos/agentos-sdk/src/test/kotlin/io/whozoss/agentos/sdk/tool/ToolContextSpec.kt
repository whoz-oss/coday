package io.whozoss.agentos.sdk.tool

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.whozoss.agentos.sdk.caseEvent.WarnEvent
import java.util.UUID

/** Tests for the case a [ToolContext] derives from its events. */
class ToolContextSpec :
    StringSpec({

        val namespaceId = UUID.randomUUID()
        val caseId = UUID.randomUUID()

        fun context(vararg events: WarnEvent) =
            ToolContext(
                namespaceId = namespaceId,
                userId = null,
                userExternalId = null,
                caseEvents = events.toList(),
            )

        fun event(caseId: UUID) = WarnEvent(namespaceId = namespaceId, caseId = caseId, message = "x")

        "caseId is the case of the case events" {
            context(event(caseId), event(caseId)).caseId shouldBe caseId
        }

        "caseId is null without case events" {
            context().caseId shouldBe null
        }

        "caseId follows the events across copy, and is not a constructor parameter" {
            val other = UUID.randomUUID()

            context(event(caseId)).copy(caseEvents = listOf(event(other))).caseId shouldBe other
        }
    })
