package io.whozoss.agentos.workflow

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.util.UUID

class LoopStepsTranslatorUnitSpec :
    StringSpec({
        val mapper = ObjectMapper().registerKotlinModule()
        val scheduledPromptId = UUID.randomUUID()

        fun json(text: String): JsonNode = mapper.readTree(text)

        val loopData =
            json(
                """
                {
                  "searchToolName": "WHOZ__SearchTalents",
                  "searchToolArgs": {"query": "kotlin", "limit": 10},
                  "agentName": "weekly-agent",
                  "prompt": "Run your weekly digest.",
                  "scheduledPromptId": "$scheduledPromptId"
                }
                """.trimIndent(),
            )

        /** The reference config of the design. */
        val reference =
            $$"""
            {
              "steps": [
                {"id": "search", "type": "SEARCH", "tool": "${loopData.searchToolName}", "args": "${loopData.searchToolArgs}"},
                {"id": "start-cases", "type": "START_CASES", "tool": "FreeCaseStarterForUser",
                 "args": {"agentName": "${loopData.agentName}", "prompt": "${loopData.prompt}"},
                 "scheduledPromptId": "${loopData.scheduledPromptId}"}
              ]
            }
            """.trimIndent()

        fun steps(text: String): LoopSteps = mapper.readValue(text, LoopSteps::class.java)

        fun translate(
            text: String = reference,
            data: JsonNode = loopData,
        ): AgentLoopPayload = LoopStepsTranslator.translate(steps(text), data)

        fun failure(
            text: String,
            data: JsonNode = loopData,
        ): String = shouldThrow<IllegalArgumentException> { translate(text, data) }.message!!

        /** The reference config with the `start-cases` step body replaced. */
        fun withStart(startStep: String): String =
            """{"steps": [{"id": "search", "type": "SEARCH", "tool": "S"}, """ + startStep + "]}"

        // -------------------------------------------------------------------------
        // Valid configurations
        // -------------------------------------------------------------------------

        "the reference config translates to the expected payload" {
            translate() shouldBe
                AgentLoopPayload(
                    search = AgentLoopSearch(tool = "WHOZ__SearchTalents", params = json("""{"query": "kotlin", "limit": 10}""")),
                    act =
                        AgentLoopAct(
                            agentName = "weekly-agent",
                            prompt = "Run your weekly digest.",
                            title = null,
                            tool = "FreeCaseStarterForUser",
                            scheduledPromptId = scheduledPromptId,
                        ),
                )
        }

        "absent or null search args become an empty object" {
            val noArgs =
                """
                {"steps": [{"id": "s", "type": "SEARCH", "tool": "S"},
                           {"id": "c", "type": "START_CASES", "tool": "FreeCaseStarterForUser", "args": {"agentName": "a", "prompt": "p"}}]}
                """.trimIndent()
            translate(noArgs).search.params shouldBe json("{}")
            translate(noArgs.replace("\"tool\": \"S\"", "\"tool\": \"S\", \"args\": null")).search.params shouldBe json("{}")
        }

        "search args are passed verbatim, as an object written in place or from loopData" {
            val inline =
                $$"""
                {"steps": [{"id": "s", "type": "SEARCH", "tool": "S", "args": {"q": "${loopData.agentName}", "n": 2, "all": "${loopData.searchToolArgs}"}},
                           {"id": "c", "type": "START_CASES", "tool": "CASE__FreeCaseStarterForUser", "args": {"agentName": "a", "prompt": "p", "title": "T"}}]}
                """.trimIndent()

            val payload = translate(inline)

            payload.search.params shouldBe json("""{"q": "weekly-agent", "n": 2, "all": {"query": "kotlin", "limit": 10}}""")
            payload.act.tool shouldBe "CASE__FreeCaseStarterForUser"
            payload.act.title shouldBe "T"
            payload.act.scheduledPromptId shouldBe null
        }

        // -------------------------------------------------------------------------
        // Shape of the loop
        // -------------------------------------------------------------------------

        "the loop must have exactly a SEARCH then a START_CASES step" {
            failure("""{"steps": []}""") shouldContain "exactly two steps"
            failure("""{"steps": [{"id": "s", "type": "SEARCH", "tool": "S"}]}""") shouldContain "exactly two steps"
            failure(
                """{"steps": [{"id": "c", "type": "START_CASES", "tool": "FreeCaseStarterForUser"}, {"id": "s", "type": "SEARCH", "tool": "S"}]}""",
            ) shouldContain "exactly two steps"
            failure(
                """{"steps": [{"id": "s", "type": "SEARCH", "tool": "S"}, {"id": "s2", "type": "SEARCH", "tool": "S"}]}""",
            ) shouldContain "exactly two steps"
            failure(
                """{"steps": [{"id": "s", "type": "SEARCH", "tool": "S"}, {"id": "c", "type": "START_CASES", "tool": "FreeCaseStarterForUser"}, {"id": "x", "type": "SEARCH", "tool": "S"}]}""",
            ) shouldContain "exactly two steps"
        }

        "step ids must be non-blank and distinct" {
            failure(
                """{"steps": [{"id": " ", "type": "SEARCH", "tool": "S"}, {"id": "c", "type": "START_CASES", "tool": "FreeCaseStarterForUser"}]}""",
            ) shouldContain "non-blank id"
            failure(
                """{"steps": [{"id": "same", "type": "SEARCH", "tool": "S"}, {"id": "same", "type": "START_CASES", "tool": "FreeCaseStarterForUser"}]}""",
            ) shouldContain "distinct"
        }

        // -------------------------------------------------------------------------
        // Tools
        // -------------------------------------------------------------------------

        "a tool must resolve to a non-blank string" {
            val blank = json("""{"searchToolName": " "}""")
            val notText = json("""{"searchToolName": {"a": 1}}""")
            val config = $$"""{"steps": [{"id": "search", "type": "SEARCH", "tool": "${loopData.searchToolName}"}, {"id": "c", "type": "START_CASES", "tool": "FreeCaseStarterForUser"}]}"""

            failure(config, blank) shouldContain "step 'search': 'tool' must resolve to a non-blank string"
            failure(config, notText) shouldContain "must resolve to a non-blank string"
            failure(config, json("{}")) shouldContain "'loopData.searchToolName' is not defined"
        }

        "the START_CASES tool must be a FreeCaseStarterForUser tool" {
            failure(withStart("""{"id": "c", "type": "START_CASES", "tool": "FreeCaseStarter", "args": {"agentName": "a", "prompt": "p"}}""")) shouldContain
                "must be the 'FreeCaseStarterForUser' tool"
            failure(withStart("""{"id": "c", "type": "START_CASES", "tool": "XFreeCaseStarterForUser", "args": {"agentName": "a", "prompt": "p"}}""")) shouldContain
                "must be the 'FreeCaseStarterForUser' tool"
        }

        // -------------------------------------------------------------------------
        // START_CASES arguments
        // -------------------------------------------------------------------------

        "START_CASES requires args holding agentName and prompt" {
            failure(withStart("""{"id": "c", "type": "START_CASES", "tool": "FreeCaseStarterForUser"}""")) shouldContain "'args' is required"
            failure(withStart("""{"id": "c", "type": "START_CASES", "tool": "FreeCaseStarterForUser", "args": {"prompt": "p"}}""")) shouldContain
                "argument 'agentName' is required"
            failure(withStart("""{"id": "c", "type": "START_CASES", "tool": "FreeCaseStarterForUser", "args": {"agentName": "a", "prompt": " "}}""")) shouldContain
                "argument 'prompt' is required"
            failure(withStart("""{"id": "c", "type": "START_CASES", "tool": "FreeCaseStarterForUser", "args": {"agentName": 3, "prompt": "p"}}""")) shouldContain
                "argument 'agentName' is required and must be a non-blank string"
            failure(withStart("""{"id": "c", "type": "START_CASES", "tool": "FreeCaseStarterForUser", "args": "text"}""")) shouldContain
                "'args' must resolve to an object"
        }

        "title is optional but must be a string" {
            failure(withStart("""{"id": "c", "type": "START_CASES", "tool": "FreeCaseStarterForUser", "args": {"agentName": "a", "prompt": "p", "title": 5}}""")) shouldContain
                "argument 'title' must be a string"
        }

        "userId and sessionContext are reserved for the runner" {
            listOf("userId", "sessionContext").forEach { reserved ->
                val message =
                    failure(
                        withStart(
                            """{"id": "c", "type": "START_CASES", "tool": "FreeCaseStarterForUser", "args": {"agentName": "a", "prompt": "p", "$reserved": "x"}}""",
                        ),
                    )
                message shouldContain "argument '$reserved' is reserved"
            }
        }

        "any other START_CASES argument is refused by name" {
            failure(withStart("""{"id": "c", "type": "START_CASES", "tool": "FreeCaseStarterForUser", "args": {"agentName": "a", "prompt": "p", "extra": 1}}""")) shouldContain
                "unknown argument 'extra'"
        }

        // -------------------------------------------------------------------------
        // SEARCH arguments
        // -------------------------------------------------------------------------

        "search args that do not resolve to an object are refused" {
            val config =
                $$"""
                {"steps": [{"id": "s", "type": "SEARCH", "tool": "S", "args": "${loopData.agentName}"},
                           {"id": "c", "type": "START_CASES", "tool": "FreeCaseStarterForUser", "args": {"agentName": "a", "prompt": "p"}}]}
                """.trimIndent()

            failure(config) shouldContain "step 's': 'args' must resolve to an object"
            failure(config.replace($$""""${loopData.agentName}"""", "[1, 2]")) shouldContain "must resolve to an object"
        }

        // -------------------------------------------------------------------------
        // scheduledPromptId
        // -------------------------------------------------------------------------

        "scheduledPromptId must resolve to a UUID" {
            val config = withStart($$"""{"id": "c", "type": "START_CASES", "tool": "FreeCaseStarterForUser", "args": {"agentName": "a", "prompt": "p"}, "scheduledPromptId": "${loopData.scheduledPromptId}"}""")

            failure(config, json("""{"scheduledPromptId": "not-a-uuid"}""")) shouldContain "'scheduledPromptId' must resolve to a UUID"
            failure(config, json("""{"scheduledPromptId": 12}""")) shouldContain "'scheduledPromptId' must resolve to a UUID"
            failure(config, json("{}")) shouldContain "'loopData.scheduledPromptId' is not defined"
        }

        "scheduledPromptId belongs to the START_CASES step" {
            val config =
                """
                {"steps": [{"id": "s", "type": "SEARCH", "tool": "S", "scheduledPromptId": "$scheduledPromptId"},
                           {"id": "c", "type": "START_CASES", "tool": "FreeCaseStarterForUser", "args": {"agentName": "a", "prompt": "p"}}]}
                """.trimIndent()

            failure(config) shouldContain "only applies to the START_CASES step"
        }

        // -------------------------------------------------------------------------
        // Placeholder errors carry the step
        // -------------------------------------------------------------------------

        "a missing loopData key is reported with its step and argument" {
            failure(reference, json("""{"searchToolName": "S"}""")) shouldContain "step 'search' args"
        }

        "step type and unknown fields in the JSON" {
            // an unknown step type cannot be parsed at all
            shouldThrow<Exception> { steps("""{"steps": [{"id": "s", "type": "NOPE", "tool": "S"}]}""") }
            // unknown fields are ignored like in the other loop payload types
            steps("""{"steps": [{"id": "s", "type": "SEARCH", "tool": "S", "note": "x"}], "version": 2}""").steps.single().id shouldBe "s"
        }
    })
