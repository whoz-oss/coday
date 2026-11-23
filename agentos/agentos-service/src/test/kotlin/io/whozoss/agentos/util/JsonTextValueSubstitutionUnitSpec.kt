package io.whozoss.agentos.util

import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class JsonTextValueSubstitutionUnitSpec :
    StringSpec({
        "substitutes textual values recursively without changing keys or non-text values" {
            val input =
                ObjectMapper().readTree(
                    """
                    {
                      "${'$'}{TOKEN}": "${'$'}{TOKEN}",
                      "nested": [
                        "prefix-${'$'}{TOKEN}",
                        { "count": 3, "enabled": true, "nothing": null }
                      ]
                    }
                    """.trimIndent(),
                )
            val originalJson = input.toString()

            val result =
                substituteJsonTextValues(input) { value ->
                    value.replace("${'$'}{TOKEN}", "resolved")
                }

            result.toString() shouldBe
                """{"${'$'}{TOKEN}":"resolved","nested":["prefix-resolved",{"count":3,"enabled":true,"nothing":null}]}"""
            input.toString() shouldBe originalJson
        }
    })
