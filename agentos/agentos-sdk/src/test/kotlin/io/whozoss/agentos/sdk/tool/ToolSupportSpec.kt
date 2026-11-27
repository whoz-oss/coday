package io.whozoss.agentos.sdk.tool

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/** Tests for the helpers shared by the tools: [ToolTimeout], [ToolSchema] and [prefixedToolName]. */
class ToolSupportSpec :
    StringSpec({
        val mapper = jacksonObjectMapper()

        // --- ToolTimeout ---

        "the timeout is bounded by 270 seconds, under the 5 minutes of the context cache" {
            ToolTimeout.MAX_SECONDS shouldBe 270
        }

        "validate accepts the whole range, bounds included" {
            listOf(1, 60, ToolTimeout.MAX_SECONDS).forEach { ToolTimeout.validate(seconds = it) shouldBe null }
        }

        "validate refuses a value outside the range and names the parameter, the range and the value" {
            listOf(0, -5, ToolTimeout.MAX_SECONDS + 1).forEach { seconds ->
                val message = ToolTimeout.validate(seconds = seconds).orEmpty()

                message shouldContain "timeoutSeconds"
                message shouldContain "between 1 and ${ToolTimeout.MAX_SECONDS}"
                message shouldContain "got $seconds"
            }
        }

        "the param description and the missing message tell what happens on expiry" {
            ToolTimeout.paramDescription(onExpiry = "the run stops") shouldBe
                "Mandatory timeout in seconds, from 1 to 270: the run stops when it is exceeded. "
            ToolTimeout.missingMessage(onExpiry = "the run stops") shouldBe
                "'timeoutSeconds' is mandatory (1 to 270): the run stops when it is exceeded."
        }

        // --- ToolSchema ---

        "schema is a closed object schema listing its properties and its required ones" {
            val schema =
                mapper.readTree(
                    ToolSchema.schema(
                        required = listOf("files"),
                        properties =
                            mapOf(
                                "files" to ToolSchema.stringArray(description = "The files"),
                                "timeoutSeconds" to ToolSchema.integer(description = "The timeout"),
                                "name" to ToolSchema.string(description = "A name"),
                            ),
                    ),
                )

            schema["\$schema"].asText() shouldBe "https://json-schema.org/draft/2020-12/schema"
            schema["type"].asText() shouldBe "object"
            schema["additionalProperties"].asBoolean() shouldBe false
            schema["required"].map { it.asText() } shouldBe listOf("files")
            schema["properties"]["files"]["type"].asText() shouldBe "array"
            schema["properties"]["files"]["items"]["type"].asText() shouldBe "string"
            schema["properties"]["timeoutSeconds"]["type"].asText() shouldBe "integer"
            schema["properties"]["name"]["description"].asText() shouldBe "A name"
        }

        "schema has no required array when nothing is required" {
            val schema = ToolSchema.schema(required = emptyList(), properties = mapOf("name" to ToolSchema.string(description = "A name")))

            schema shouldNotContain "\"required\""
        }

        "a description with quotes and newlines stays valid JSON" {
            val description = "say \"hi\"\nthen leave"

            val schema =
                mapper.readTree(
                    ToolSchema.schema(
                        required = emptyList(),
                        properties =
                            mapOf("x" to ToolSchema.string(description = description)),
                    ),
                )

            schema["properties"]["x"]["description"].asText() shouldBe description
        }

        "objectArray describes its items as closed objects with their own required properties" {
            val property =
                ToolSchema.objectArray(
                    description = "The targets",
                    itemRequired = listOf("name"),
                    itemProperties = mapOf("name" to ToolSchema.string(description = "A name")),
                )

            property["type"].asText() shouldBe "array"
            property["items"]["type"].asText() shouldBe "object"
            property["items"]["required"].map { it.asText() } shouldBe listOf("name")
            property["items"]["additionalProperties"].asBoolean() shouldBe false
            property["items"]["properties"]["name"]["type"].asText() shouldBe "string"
        }

        // --- prefixedToolName ---

        "a tool name is prefixed with the config name when there is one" {
            prefixedToolName(configName = "MY_KTLINT", toolName = "ktlint_check") shouldBe "MY_KTLINT__ktlint_check"
        }

        "a tool name is left as is without a config name" {
            prefixedToolName(configName = null, toolName = "ktlint_check") shouldBe "ktlint_check"
        }
    })
