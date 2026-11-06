package io.whozoss.agentos.caseEvent

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.whozoss.agentos.sdk.caseEvent.AgentFinishedEvent
import io.whozoss.agentos.sdk.caseEvent.CaseEvent
import java.util.UUID

/** JSON contract of the `contextWindowExceeded` field of [AgentFinishedEvent]: a round trip, and omitted when null. */
class AgentFinishedEventJsonUnitSpec :
    StringSpec({
        timeout = 5000

        // Like the application's mapper: computed properties (`id`, `type`) are written but not read back.
        val mapper =
            jacksonObjectMapper()
                .findAndRegisterModules()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

        fun event(contextWindowExceeded: Boolean?) =
            AgentFinishedEvent(
                namespaceId = UUID.randomUUID(),
                caseId = UUID.randomUUID(),
                agentId = UUID.randomUUID(),
                agentName = "WorkerAgent",
                contextWindowExceeded = contextWindowExceeded,
            )

        "contextWindowExceeded round-trips through JSON" {
            val json = mapper.writeValueAsString(event(true))

            mapper.readTree(json).get("contextWindowExceeded").asBoolean() shouldBe true
            (mapper.readValue(json, CaseEvent::class.java) as AgentFinishedEvent).contextWindowExceeded shouldBe true
        }

        "contextWindowExceeded is omitted from the JSON when null, and reads back as null" {
            val json = mapper.writeValueAsString(event(null))

            mapper.readTree(json).has("contextWindowExceeded") shouldBe false
            (mapper.readValue(json, CaseEvent::class.java) as AgentFinishedEvent).contextWindowExceeded shouldBe null
        }

        "an event stored before the field existed still reads, with the flag null" {
            val withoutField =
                (mapper.readTree(mapper.writeValueAsString(event(true))) as ObjectNode)
                    .also { it.remove("contextWindowExceeded") }
                    .toString()

            (mapper.readValue(withoutField, CaseEvent::class.java) as AgentFinishedEvent).contextWindowExceeded shouldBe null
        }
    })
