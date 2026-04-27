package io.whozoss.agentos.plugins.bash

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class BashToolProviderUnitSpec : StringSpec({

    val provider = BashToolProvider()
    val mapper = jacksonObjectMapper()

    fun json(block: String) = mapper.readTree(block)

    // --- integrationType ---

    "integrationType should be BASH" {
        provider.integrationType shouldBe "BASH"
    }

    // --- configSchema ---

    "configSchema should be a valid JSON object with required workingDirectory" {
        val schema = provider.configSchema
        schema.get("type").asText() shouldBe "object"
        schema.get("required").toString() shouldContain "workingDirectory"
    }

    // --- null / missing config ---

    "null config should return empty tool list" {
        provider.provideTools(null, "TEST").shouldBeEmpty()
    }

    "JSON null config should return empty tool list" {
        provider.provideTools(json("null"), "TEST").shouldBeEmpty()
    }

    // --- invalid config: provider must not throw, returns empty ---

    "missing workingDirectory should return empty tool list" {
        val config = json("""{"tools": [{"name": "t", "description": "d", "command": "ls"}]}""")
        provider.provideTools(config, "TEST").shouldBeEmpty()
    }

    "tool with PARAMETERS but no parametersDescription should return empty tool list" {
        val config = json("""
            {
                "workingDirectory": "/tmp",
                "tools": [{
                    "name": "search",
                    "description": "Searches",
                    "command": "grep -r PARAMETERS ."
                }]
            }
        """)
        provider.provideTools(config, "TEST").shouldBeEmpty()
    }

    "duplicate tool names should return empty tool list" {
        val config = json("""
            {
                "workingDirectory": "/tmp",
                "tools": [
                    {"name": "dup", "description": "d", "command": "echo a"},
                    {"name": "dup", "description": "d", "command": "echo b"}
                ]
            }
        """)
        provider.provideTools(config, "TEST").shouldBeEmpty()
    }

    // --- valid configs ---

    "valid config with one fixed tool should return one tool" {
        val config = json("""
            {
                "workingDirectory": "/tmp",
                "tools": [{
                    "name": "list",
                    "description": "Lists files",
                    "command": "ls -la"
                }]
            }
        """)
        val tools = provider.provideTools(config, "MY_PROJECT")
        tools shouldHaveSize 1
        tools[0].name shouldBe "MY_PROJECT__list"
    }

    "valid config with multiple tools should return all tools" {
        val config = json("""
            {
                "workingDirectory": "/repo",
                "tools": [
                    {"name": "test",   "description": "Run tests",  "command": "./gradlew test"},
                    {"name": "search", "description": "Search code", "command": "grep -r PARAMETERS src", "parametersDescription": "Pattern"},
                    {"name": "bash",   "description": "Raw bash",   "command": "PARAMETERS", "parametersDescription": "Full command"}
                ]
            }
        """)
        val tools = provider.provideTools(config, "PROJ")
        tools shouldHaveSize 3
        tools.map { it.name } shouldBe listOf("PROJ__test", "PROJ__search", "PROJ__bash")
    }

    "null configName should produce tools without prefix" {
        val config = json("""
            {
                "workingDirectory": "/tmp",
                "tools": [{"name": "list", "description": "Lists", "command": "ls"}]
            }
        """)
        val tools = provider.provideTools(config, null)
        tools[0].name shouldBe "list"
    }

    "empty tools array should return empty list" {
        val config = json("""{"workingDirectory": "/tmp", "tools": []}""")
        provider.provideTools(config, "TEST").shouldBeEmpty()
    }

    "valid config with no tools key should return empty list" {
        val config = json("""{"workingDirectory": "/tmp"}""")
        provider.provideTools(config, "TEST").shouldBeEmpty()
    }

    // --- tool input schema wiring ---

    "fixed tool should have empty properties schema" {
        val config = json("""
            {
                "workingDirectory": "/tmp",
                "tools": [{"name": "t", "description": "d", "command": "echo hi"}]
            }
        """)
        val tool = provider.provideTools(config, null)[0]
        tool.inputSchema shouldContain "\"properties\": {}"
    }

    "parameterised tool should expose parameters field in schema" {
        val config = json("""
            {
                "workingDirectory": "/tmp",
                "tools": [{
                    "name": "search",
                    "description": "Searches",
                    "command": "grep -r PARAMETERS .",
                    "parametersDescription": "The pattern to find"
                }]
            }
        """)
        val tool = provider.provideTools(config, null)[0]
        tool.inputSchema shouldContain "\"parameters\""
        tool.inputSchema shouldContain "The pattern to find"
    }
})
