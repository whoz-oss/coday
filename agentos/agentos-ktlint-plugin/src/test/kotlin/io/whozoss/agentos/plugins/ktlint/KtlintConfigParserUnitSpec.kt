package io.whozoss.agentos.plugins.ktlint

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class KtlintConfigParserUnitSpec :
    StringSpec({

        val mapper = jacksonObjectMapper()

        fun json(block: String) = mapper.readTree(block)

        "a valid absolute projectDirectory should parse" {
            KtlintConfigParser.parse(json("""{"projectDirectory": "/tmp"}""")).projectDirectory shouldBe "/tmp"
        }

        "the projectDirectory should be trimmed" {
            KtlintConfigParser.parse(json("""{"projectDirectory": "  /tmp  "}""")).projectDirectory shouldBe "/tmp"
        }

        "missing projectDirectory should throw with descriptive message" {
            val ex = shouldThrow<IllegalArgumentException> { KtlintConfigParser.parse(json("{}")) }
            ex.message shouldContain "projectDirectory"
        }

        "JSON null projectDirectory should throw" {
            val ex = shouldThrow<IllegalArgumentException> { KtlintConfigParser.parse(json("""{"projectDirectory": null}""")) }
            ex.message shouldContain "projectDirectory"
        }

        "blank projectDirectory should throw" {
            val ex = shouldThrow<IllegalArgumentException> { KtlintConfigParser.parse(json("""{"projectDirectory": "   "}""")) }
            ex.message shouldContain "projectDirectory"
        }

        "relative projectDirectory should throw" {
            val ex = shouldThrow<IllegalArgumentException> { KtlintConfigParser.parse(json("""{"projectDirectory": "relative/path"}""")) }
            ex.message shouldContain "absolute"
        }
    })
