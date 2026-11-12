package io.whozoss.agentos.workflow

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.whozoss.agentos.caseFlow.SessionContextKeys

class AgentLoopSessionContextUnitSpec :
    StringSpec({
        val objectMapper = ObjectMapper().registerKotlinModule()

        val payload =
            AgentLoopPayload(
                search =
                    AgentLoopSearch(
                        tool = "WHOZ__SearchTalents",
                        params = objectMapper.readTree("""{"query":"kotlin","filters":{"levels":[1,2]},"next":null}"""),
                    ),
                act = AgentLoopAct(agentName = "Caretaker", prompt = "Review this profile"),
            )

        "of wraps the payload under the agentLoop key, as plain maps and lists" {
            val context = AgentLoopSessionContext.of(payload, objectMapper)

            context.keys shouldBe setOf(SessionContextKeys.AGENT_LOOP)
            val entry = context[SessionContextKeys.AGENT_LOOP].shouldBeInstanceOf<Map<*, *>>()
            val search = entry["search"].shouldBeInstanceOf<Map<*, *>>()
            search["tool"] shouldBe "WHOZ__SearchTalents"
            val params = search["params"].shouldBeInstanceOf<Map<*, *>>()
            params["query"] shouldBe "kotlin"
            params["filters"].shouldBeInstanceOf<Map<*, *>>()["levels"] shouldBe listOf(1, 2)
            (entry["act"] as Map<*, *>)["prompt"] shouldBe "Review this profile"
        }

        "read returns the payload written by of" {
            val context = AgentLoopSessionContext.of(payload, objectMapper)

            AgentLoopSessionContext.read(context, objectMapper) shouldBe payload
        }

        "read returns null when the session context is null or has no agentLoop entry" {
            AgentLoopSessionContext.read(null, objectMapper) shouldBe null
            AgentLoopSessionContext.read(emptyMap(), objectMapper) shouldBe null
            AgentLoopSessionContext.read(mapOf("preferredLanguage" to "fr"), objectMapper) shouldBe null
        }

        "read throws IllegalArgumentException when the entry is present but unparseable" {
            shouldThrow<IllegalArgumentException> {
                AgentLoopSessionContext.read(mapOf(SessionContextKeys.AGENT_LOOP to "nope"), objectMapper)
            }
            shouldThrow<IllegalArgumentException> {
                AgentLoopSessionContext.read(mapOf(SessionContextKeys.AGENT_LOOP to mapOf("search" to 3)), objectMapper)
            }
        }
    })
