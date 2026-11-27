package io.whozoss.agentos.plugins.kotlin

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class KotlinConfigParserUnitSpec :
    StringSpec({
        val mapper = jacksonObjectMapper()

        fun json(block: String) = mapper.readTree(block)

        "a valid absolute projectDirectory should parse" {
            KotlinConfigParser.parse(config = json("""{"projectDirectory": "/tmp"}""")).projectDirectory shouldBe "/tmp"
        }

        "the projectDirectory should be trimmed" {
            KotlinConfigParser.parse(config = json("""{"projectDirectory": "  /tmp  "}""")).projectDirectory shouldBe "/tmp"
        }

        "missing projectDirectory should throw with descriptive message" {
            shouldThrow<IllegalArgumentException> { KotlinConfigParser.parse(config = json("{}")) }.message shouldContain "projectDirectory"
        }

        "JSON null projectDirectory should throw" {
            shouldThrow<IllegalArgumentException> {
                KotlinConfigParser.parse(config = json("""{"projectDirectory": null}"""))
            }.message shouldContain "projectDirectory"
        }

        "blank projectDirectory should throw" {
            shouldThrow<IllegalArgumentException> {
                KotlinConfigParser.parse(config = json("""{"projectDirectory": "   "}"""))
            }.message shouldContain "projectDirectory"
        }

        "relative projectDirectory should throw" {
            shouldThrow<IllegalArgumentException> {
                KotlinConfigParser.parse(config = json("""{"projectDirectory": "relative/path"}"""))
            }.message shouldContain "absolute"
        }

        "an absolute serverBinary should parse, trimmed" {
            // given
            val config = json("""{"projectDirectory": "/tmp", "serverBinary": "  /opt/lsp/intellij-server  "}""")

            // when
            val parsed = KotlinConfigParser.parse(config = config)

            // then
            parsed.serverBinary shouldBe "/opt/lsp/intellij-server"
        }

        "a missing, null or blank serverBinary means none" {
            listOf(
                """{"projectDirectory": "/tmp"}""",
                """{"projectDirectory": "/tmp", "serverBinary": null}""",
                """{"projectDirectory": "/tmp", "serverBinary": "   "}""",
            ).forEach { block ->
                KotlinConfigParser.parse(config = json(block)).serverBinary shouldBe null
            }
        }

        "a relative serverBinary should throw" {
            shouldThrow<IllegalArgumentException> {
                KotlinConfigParser.parse(config = json("""{"projectDirectory": "/tmp", "serverBinary": "bin/intellij-server"}"""))
            }.message shouldContain "serverBinary"
        }
    })
