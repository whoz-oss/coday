package io.whozoss.agentos.plugin.filesystem

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

class ConfigPathSubstitutionUnitSpec :
    StringSpec({

        val configPath = "/home/alice/repos/myproject/.agentos"

        // -------------------------------------------------------------------------
        // String substitution
        // -------------------------------------------------------------------------

        "substitutes a token at the start of a value" {
            substituteConfigPath("{{NAMESPACE_CONFIG_PATH}}/../..", configPath) shouldBe "$configPath/../.."
        }

        "substitutes a token embedded mid-value" {
            substituteConfigPath("--config={{NAMESPACE_CONFIG_PATH}}/settings.json", configPath) shouldBe
                "--config=$configPath/settings.json"
        }

        "substitutes multiple occurrences of the token in the same value" {
            substituteConfigPath(
                "{{NAMESPACE_CONFIG_PATH}}/a:{{NAMESPACE_CONFIG_PATH}}/b",
                configPath,
            ) shouldBe "$configPath/a:$configPath/b"
        }

        "does not normalize the resulting path -- '..' segments are left literal" {
            substituteConfigPath("{{NAMESPACE_CONFIG_PATH}}/../scripts/build.sh", configPath) shouldBe
                "$configPath/../scripts/build.sh"
        }

        "leaves a value without the token unchanged" {
            substituteConfigPath("/absolute/unrelated/path", configPath) shouldBe "/absolute/unrelated/path"
        }

        // -------------------------------------------------------------------------
        // JsonNode substitution
        // -------------------------------------------------------------------------

        "substitutes the token inside a top-level text field" {
            val input = JsonNodeFactory.instance.objectNode().put("workingDirectory", "{{NAMESPACE_CONFIG_PATH}}/../..")

            val result = substituteConfigPath(input, configPath)

            result?.get("workingDirectory")?.textValue() shouldBe "$configPath/../.."
        }

        "substitutes the token inside an array element (MCP args case)" {
            val args = JsonNodeFactory.instance.arrayNode().add("--flag").add("{{NAMESPACE_CONFIG_PATH}}/config.json")
            val input = JsonNodeFactory.instance.objectNode().set<JsonNode>("args", args)

            val result = substituteConfigPath(input, configPath)

            result?.get("args")?.get(0)?.textValue() shouldBe "--flag"
            result?.get("args")?.get(1)?.textValue() shouldBe "$configPath/config.json"
        }

        "substitutes the token inside a nested object (BASH tools[].command case)" {
            val tool = JsonNodeFactory.instance.objectNode().also {
                it.put("name", "build")
                it.put("command", "{{NAMESPACE_CONFIG_PATH}}/../scripts/build.sh")
            }
            val tools = JsonNodeFactory.instance.arrayNode().add(tool)
            val input = JsonNodeFactory.instance.objectNode().set<JsonNode>("tools", tools)

            val result = substituteConfigPath(input, configPath)

            result?.get("tools")?.get(0)?.get("command")?.textValue() shouldBe "$configPath/../scripts/build.sh"
            result?.get("tools")?.get(0)?.get("name")?.textValue() shouldBe "build"
        }

        "preserves non-textual nodes (numbers, booleans, null) untouched" {
            val input = JsonNodeFactory.instance.objectNode().also {
                it.put("count", 42)
                it.put("enabled", true)
                it.putNull("nothing")
            }

            val result = substituteConfigPath(input, configPath)

            result?.get("count")?.intValue() shouldBe 42
            result?.get("enabled")?.booleanValue() shouldBe true
            result?.get("nothing")?.isNull shouldBe true
        }

        "leaves a JsonNode without the token unchanged in content" {
            val input = JsonNodeFactory.instance.objectNode().put("baseUrl", "https://company.atlassian.net")

            val result = substituteConfigPath(input, configPath)

            result?.get("baseUrl")?.textValue() shouldBe "https://company.atlassian.net"
        }

        "does not mutate the original JsonNode" {
            val input = JsonNodeFactory.instance.objectNode().put("workingDirectory", "{{NAMESPACE_CONFIG_PATH}}/x")

            substituteConfigPath(input, configPath)

            input.get("workingDirectory").textValue() shouldBe "{{NAMESPACE_CONFIG_PATH}}/x"
        }

        "returns null when the input node is null" {
            substituteConfigPath(null, configPath).shouldBeNull()
        }

        "returns the node unchanged when configPath is null" {
            val input = JsonNodeFactory.instance.objectNode().put("workingDirectory", "{{NAMESPACE_CONFIG_PATH}}/x")

            val result = substituteConfigPath(input, null)

            result?.get("workingDirectory")?.textValue() shouldBe "{{NAMESPACE_CONFIG_PATH}}/x"
        }
    })
