package io.whozoss.agentos.workflow

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly

class JsonSchemaSubsetUnitSpec :
    StringSpec({
        val mapper = jacksonObjectMapper()
        fun json(text: String) = mapper.readTree(text)

        val schema =
            json(
                """
                {"type":"object","required":["scope","agentConfigId"],"additionalProperties":false,
                 "properties":{
                   "scope":{"type":"string","enum":["TEAM","WORKSPACE"]},
                   "agentConfigId":{"type":"string","format":"uuid"},
                   "rate":{"type":"object","properties":{"max":{"type":"integer"}}},
                   "ids":{"type":"array","items":{"type":"string"}}}}
                """.trimIndent(),
            )

        "a conforming value has no problem; ignored required keys are not required" {
            JsonSchemaSubset.problems(json("""{"scope":"TEAM","rate":{"max":19},"ids":["a"]}"""), schema, setOf("agentConfigId")).shouldBeEmpty()
        }

        "missing required, unknown property, wrong type, enum and nested problems are all reported with their path" {
            JsonSchemaSubset.problems(json("""{"scope":"ALL","rate":{"max":"x"},"ids":[1],"extra":true}"""), schema) shouldContainExactly
                listOf(
                    "$: missing required 'agentConfigId'",
                    "$.scope: must be one of [\"TEAM\",\"WORKSPACE\"]",
                    "$.rate.max: expected integer, got string",
                    "$.ids[0]: expected string, got integer",
                    "$: unknown property 'extra'",
                )
        }

        "unsupported keywords are ignored and an open schema accepts extra keys" {
            JsonSchemaSubset.problems(json("""{"agentConfigId":"not-a-uuid","other":1}"""), json("""{"type":"object","properties":{"agentConfigId":{"type":"string","format":"uuid"}}}"""))
                .shouldBeEmpty()
        }
    })
