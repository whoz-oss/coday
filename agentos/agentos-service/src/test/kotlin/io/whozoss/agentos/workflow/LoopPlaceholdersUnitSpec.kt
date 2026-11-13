package io.whozoss.agentos.workflow

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.databind.node.TextNode
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith

/**
 * Placeholders are written with `\${...}` in ordinary strings and with `$$"""` raw strings (where a single `$` is
 * literal), so that Kotlin never interpolates them.
 */
class LoopPlaceholdersUnitSpec :
    StringSpec({
        val mapper = ObjectMapper()

        fun json(text: String): JsonNode = mapper.readTree(text)

        val loopData =
            json(
                $$"""
                {
                  "name": "Alice", "count": 3, "ratio": 1.5, "flag": true, "nothing": null,
                  "args": {"query": "kotlin", "levels": [1, 2]},
                  "list": ["a", "b"],
                  "nested": {"inner": {"leaf": "deep"}},
                  "with-dash": "dashed", "_under": "underscored",
                  "tricky": "${loopData.name}"
                }
                """.trimIndent(),
            )

        fun resolveJson(template: String): JsonNode = LoopPlaceholders.resolve(json(template), loopData, "step 's' args")

        fun resolveText(text: String): JsonNode = LoopPlaceholders.resolve(TextNode.valueOf(text), loopData, "step 's' args")

        // -------------------------------------------------------------------------
        // Whole-value replacement keeps the type
        // -------------------------------------------------------------------------

        "a whole-value placeholder is replaced by the value, whatever its type" {
            resolveText("\${loopData.args}") shouldBe json("""{"query": "kotlin", "levels": [1, 2]}""")
            resolveText("\${loopData.list}") shouldBe json("""["a", "b"]""")
            resolveText("\${loopData.count}") shouldBe json("3")
            resolveText("\${loopData.ratio}") shouldBe json("1.5")
            resolveText("\${loopData.flag}") shouldBe json("true")
            resolveText("\${loopData.nothing}") shouldBe json("null")
            resolveText("\${loopData.name}") shouldBe TextNode.valueOf("Alice")
        }

        "the root placeholder is the whole loopData" {
            resolveText("\${loopData}") shouldBe loopData
        }

        "keys can be nested, dashed or start with an underscore" {
            resolveText("\${loopData.nested.inner.leaf}") shouldBe TextNode.valueOf("deep")
            resolveText("\${loopData.with-dash}") shouldBe TextNode.valueOf("dashed")
            resolveText("\${loopData._under}") shouldBe TextNode.valueOf("underscored")
        }

        // -------------------------------------------------------------------------
        // Interpolation
        // -------------------------------------------------------------------------

        "a placeholder inside a longer text is interpolated with scalars" {
            resolveText("Hello \${loopData.name}, you have \${loopData.count} items (\${loopData.flag}, \${loopData.ratio})") shouldBe
                TextNode.valueOf("Hello Alice, you have 3 items (true, 1.5)")
        }

        "several placeholders can share a text" {
            resolveText("\${loopData.name}-\${loopData.nested.inner.leaf}") shouldBe TextNode.valueOf("Alice-deep")
        }

        "embedding an object, an array or a null in a text is an error" {
            listOf("args" to "an object", "list" to "an array", "nothing" to "null").forEach { (key, kind) ->
                val error = shouldThrow<IllegalArgumentException> { resolveText("x \${loopData.$key} y") }
                error.message!! shouldContain kind
                error.message!! shouldContain "\${loopData.$key}"
            }
        }

        // -------------------------------------------------------------------------
        // Where substitution happens
        // -------------------------------------------------------------------------

        "strings are resolved at any depth of objects and arrays" {
            resolveJson(
                $$"""{"a": {"b": ["${loopData.name}", {"c": "${loopData.count}"}]}, "d": 7, "e": "plain"}""",
            ) shouldBe json("""{"a": {"b": ["Alice", {"c": 3}]}, "d": 7, "e": "plain"}""")
        }

        "object keys are never resolved" {
            resolveJson($$"""{"${loopData.name}": "v"}""") shouldBe json($$"""{"${loopData.name}": "v"}""")
        }

        "the template and loopData are left untouched" {
            val template = json($$"""{"a": "${loopData.args}"}""")
            val before = template.deepCopy<JsonNode>()

            val resolved = LoopPlaceholders.resolve(template, loopData)
            (resolved.get("a") as ObjectNode).put("changed", true)

            template shouldBe before
            loopData.get("args") shouldBe json("""{"query": "kotlin", "levels": [1, 2]}""")
        }

        // -------------------------------------------------------------------------
        // No re-evaluation
        // -------------------------------------------------------------------------

        "a placeholder found in a loopData value stays literal, whole or interpolated" {
            resolveText("\${loopData.tricky}") shouldBe TextNode.valueOf("\${loopData.name}")
            resolveText("say \${loopData.tricky}!") shouldBe TextNode.valueOf("say \${loopData.name}!")
        }

        // -------------------------------------------------------------------------
        // Errors
        // -------------------------------------------------------------------------

        "a key missing from loopData is an error naming the key and the location" {
            val error =
                shouldThrow<IllegalArgumentException> {
                    resolveJson($$"""{"x": ["${loopData.nested.nope}"]}""")
                }

            error.message!! shouldStartWith "step 's' args.x[0]:"
            error.message!! shouldContain "'loopData.nested.nope' is not defined in loopData"
        }

        "going through a scalar to reach a key is a missing key" {
            shouldThrow<IllegalArgumentException> { resolveText("\${loopData.name.first}") }.message!! shouldContain
                "'loopData.name.first' is not defined"
        }

        "malformed placeholders are errors, alone or inside a text" {
            listOf(
                "\${loopData.}",
                "\${loopData..a}",
                "\${loopData.a b}",
                "\${loopData.list[0]}",
                "\${loopData.name:-x}",
                "\${loopData.1a}",
                "\${other.name}",
                "\${steps.search.out}",
                "\${loopData",
                "\${}",
            ).forEach { text ->
                shouldThrow<IllegalArgumentException> { resolveText(text) }.message!! shouldContain "placeholder"
                shouldThrow<IllegalArgumentException> { resolveText("before $text after") }.message!! shouldContain "placeholder"
            }
        }

        "a dollar sign that does not open a placeholder is plain text" {
            resolveText("costs \$5 and {loopData.name}") shouldBe TextNode.valueOf("costs \$5 and {loopData.name}")
        }
    })
