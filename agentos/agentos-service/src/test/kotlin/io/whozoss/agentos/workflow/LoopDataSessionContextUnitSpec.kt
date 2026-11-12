package io.whozoss.agentos.workflow

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.whozoss.agentos.caseFlow.SessionContextKeys

class LoopDataSessionContextUnitSpec :
    StringSpec({
        val objectMapper = ObjectMapper().registerKotlinModule()

        val loopData =
            mapOf(
                "searchToolName" to "WHOZ__SearchTalents",
                "searchToolArgs" to mapOf("query" to "kotlin", "levels" to listOf(1, 2)),
                "scheduledPromptId" to "2a1c0a3e-61a0-4b5d-8d2e-5fb0d4d8c0a1",
            )

        "of wraps the loopData under the loopData key" {
            val context = LoopDataSessionContext.of(loopData)

            context.keys shouldBe setOf(SessionContextKeys.LOOP_DATA)
            context[SessionContextKeys.LOOP_DATA] shouldBe loopData
        }

        "read returns the loopData written by of, as a JSON object" {
            val node = LoopDataSessionContext.read(LoopDataSessionContext.of(loopData), objectMapper)!!

            node.isObject shouldBe true
            node.get("searchToolName").asText() shouldBe "WHOZ__SearchTalents"
            node.get("searchToolArgs").get("levels").map { it.asInt() } shouldBe listOf(1, 2)
            node shouldBe objectMapper.valueToTree(loopData)
        }

        "read returns null when the session context is null or has no loopData entry" {
            LoopDataSessionContext.read(null, objectMapper) shouldBe null
            LoopDataSessionContext.read(emptyMap(), objectMapper) shouldBe null
            LoopDataSessionContext.read(mapOf("preferredLanguage" to "fr"), objectMapper) shouldBe null
        }

        "read throws IllegalArgumentException when the entry is present but is not an object" {
            listOf<Any>("nope", 3, listOf(1, 2)).forEach { notAnObject ->
                shouldThrow<IllegalArgumentException> {
                    LoopDataSessionContext.read(mapOf(SessionContextKeys.LOOP_DATA to notAnObject), objectMapper)
                }.message.shouldBeInstanceOf<String>()
            }
        }
    })
