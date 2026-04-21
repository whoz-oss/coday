package io.whozoss.agentos.plugins.bash

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class BashConfigParserUnitSpec : StringSpec({

    val mapper = jacksonObjectMapper()

    fun json(block: String) = mapper.readTree(block)

    // --- workingDirectory ---

    "minimal valid config with one fixed tool should parse correctly" {
        val config = json("""
            {
                "workingDirectory": "/tmp",
                "tools": [
                    {
                        "name": "list",
                        "description": "Lists files",
                        "command": "ls -la"
                    }
                ]
            }
        """)

        val result = BashConfigParser.parse(config)

        result.workingDirectory shouldBe "/tmp"
        result.defaultTimeoutSeconds shouldBe 30L
        result.tools shouldHaveSize 1
        result.tools[0].name shouldBe "list"
        result.tools[0].command shouldBe "ls -la"
        result.tools[0].parametersDescription shouldBe null
        result.tools[0].path shouldBe null
        result.tools[0].timeoutSeconds shouldBe null
    }

    "missing workingDirectory should throw with descriptive message" {
        val config = json("""{"tools": []}""")

        val ex = shouldThrow<IllegalArgumentException> { BashConfigParser.parse(config) }
        ex.message shouldContain "workingDirectory"
    }

    "blank workingDirectory should throw" {
        val config = json("""{"workingDirectory": "   ", "tools": []}""")

        val ex = shouldThrow<IllegalArgumentException> { BashConfigParser.parse(config) }
        ex.message shouldContain "workingDirectory"
    }

    // --- defaultTimeoutSeconds ---

    "custom defaultTimeoutSeconds should be parsed" {
        val config = json("""
            {"workingDirectory": "/tmp", "defaultTimeoutSeconds": 120, "tools": []}
        """)

        BashConfigParser.parse(config).defaultTimeoutSeconds shouldBe 120L
    }

    "zero defaultTimeoutSeconds should throw" {
        val config = json("""
            {"workingDirectory": "/tmp", "defaultTimeoutSeconds": 0, "tools": []}
        """)

        val ex = shouldThrow<IllegalArgumentException> { BashConfigParser.parse(config) }
        ex.message shouldContain "defaultTimeoutSeconds"
    }

    "negative defaultTimeoutSeconds should throw" {
        val config = json("""
            {"workingDirectory": "/tmp", "defaultTimeoutSeconds": -5, "tools": []}
        """)

        val ex = shouldThrow<IllegalArgumentException> { BashConfigParser.parse(config) }
        ex.message shouldContain "defaultTimeoutSeconds"
    }

    // --- tools array ---

    "missing tools array should produce empty list" {
        val config = json("""{"workingDirectory": "/tmp"}""")

        BashConfigParser.parse(config).tools shouldHaveSize 0
    }

    "null tools should produce empty list" {
        val config = json("""{"workingDirectory": "/tmp", "tools": null}""")

        BashConfigParser.parse(config).tools shouldHaveSize 0
    }

    "empty tools array should produce empty list" {
        val config = json("""{"workingDirectory": "/tmp", "tools": []}""")

        BashConfigParser.parse(config).tools shouldHaveSize 0
    }

    "duplicate tool names should throw" {
        val config = json("""
            {
                "workingDirectory": "/tmp",
                "tools": [
                    {"name": "foo", "description": "desc", "command": "echo a"},
                    {"name": "foo", "description": "desc", "command": "echo b"}
                ]
            }
        """)

        val ex = shouldThrow<IllegalArgumentException> { BashConfigParser.parse(config) }
        ex.message shouldContain "duplicate"
        ex.message shouldContain "foo"
    }

    // --- per-tool validation ---

    "tool with blank name should throw" {
        val config = json("""
            {
                "workingDirectory": "/tmp",
                "tools": [{"name": "", "description": "desc", "command": "echo hi"}]
            }
        """)

        val ex = shouldThrow<IllegalArgumentException> { BashConfigParser.parse(config) }
        ex.message shouldContain "name"
    }

    "tool with blank description should throw" {
        val config = json("""
            {
                "workingDirectory": "/tmp",
                "tools": [{"name": "t", "description": "  ", "command": "echo hi"}]
            }
        """)

        val ex = shouldThrow<IllegalArgumentException> { BashConfigParser.parse(config) }
        ex.message shouldContain "description"
    }

    "tool with blank command should throw" {
        val config = json("""
            {
                "workingDirectory": "/tmp",
                "tools": [{"name": "t", "description": "desc", "command": ""}]
            }
        """)

        val ex = shouldThrow<IllegalArgumentException> { BashConfigParser.parse(config) }
        ex.message shouldContain "command"
    }

    // --- PARAMETERS placeholder ---

    "tool with PARAMETERS in command and parametersDescription should parse" {
        val config = json("""
            {
                "workingDirectory": "/tmp",
                "tools": [{
                    "name": "search",
                    "description": "Searches",
                    "command": "grep -r PARAMETERS .",
                    "parametersDescription": "The pattern to search for"
                }]
            }
        """)

        val tool = BashConfigParser.parse(config).tools[0]
        tool.parametersDescription shouldBe "The pattern to search for"
    }

    "tool with PARAMETERS in command but no parametersDescription should throw" {
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

        val ex = shouldThrow<IllegalArgumentException> { BashConfigParser.parse(config) }
        ex.message shouldContain "parametersDescription"
    }

    "tool with parametersDescription but no PARAMETERS placeholder should throw" {
        val config = json("""
            {
                "workingDirectory": "/tmp",
                "tools": [{
                    "name": "list",
                    "description": "Lists",
                    "command": "ls -la",
                    "parametersDescription": "This makes no sense here"
                }]
            }
        """)

        val ex = shouldThrow<IllegalArgumentException> { BashConfigParser.parse(config) }
        ex.message shouldContain "PARAMETERS"
    }

    "raw bash tool (command IS PARAMETERS) with parametersDescription should parse" {
        val config = json("""
            {
                "workingDirectory": "/tmp",
                "tools": [{
                    "name": "bash",
                    "description": "Runs any bash command",
                    "command": "PARAMETERS",
                    "parametersDescription": "The full bash command to execute"
                }]
            }
        """)

        val tool = BashConfigParser.parse(config).tools[0]
        tool.command shouldBe "PARAMETERS"
        tool.parametersDescription shouldBe "The full bash command to execute"
    }

    // --- optional per-tool fields ---

    "tool with optional path should parse" {
        val config = json("""
            {
                "workingDirectory": "/tmp",
                "tools": [{
                    "name": "t",
                    "description": "desc",
                    "command": "ls",
                    "path": "subdir"
                }]
            }
        """)

        BashConfigParser.parse(config).tools[0].path shouldBe "subdir"
    }

    "tool with per-tool timeoutSeconds should override default" {
        val config = json("""
            {
                "workingDirectory": "/tmp",
                "defaultTimeoutSeconds": 30,
                "tools": [{
                    "name": "slow",
                    "description": "A slow command",
                    "command": "sleep 60",
                    "timeoutSeconds": 90
                }]
            }
        """)

        BashConfigParser.parse(config).tools[0].timeoutSeconds shouldBe 90L
    }

    "tool with zero timeoutSeconds should throw" {
        val config = json("""
            {
                "workingDirectory": "/tmp",
                "tools": [{
                    "name": "t",
                    "description": "desc",
                    "command": "ls",
                    "timeoutSeconds": 0
                }]
            }
        """)

        val ex = shouldThrow<IllegalArgumentException> { BashConfigParser.parse(config) }
        ex.message shouldContain "timeoutSeconds"
    }

    "multiple valid tools should all be parsed" {
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

        val result = BashConfigParser.parse(config)
        result.tools shouldHaveSize 3
        result.tools.map { it.name } shouldBe listOf("test", "search", "bash")
    }
})
