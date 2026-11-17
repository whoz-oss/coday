package io.whozoss.agentos.plugins.gradle

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.File

class GradleConfigParserUnitSpec :
    StringSpec({

        val mapper = jacksonObjectMapper()

        fun json(block: String) = mapper.readTree(block)

        "a valid absolute projectDirectory should parse" {
            GradleConfigParser.parse(json("""{"projectDirectory": "/tmp"}""")).projectDirectory shouldBe "/tmp"
        }

        "the projectDirectory should be trimmed" {
            GradleConfigParser.parse(json("""{"projectDirectory": "  /tmp  "}""")).projectDirectory shouldBe "/tmp"
        }

        "missing projectDirectory should throw with descriptive message" {
            val ex = shouldThrow<IllegalArgumentException> { GradleConfigParser.parse(json("{}")) }
            ex.message shouldContain "projectDirectory"
        }

        "JSON null projectDirectory should throw" {
            val ex = shouldThrow<IllegalArgumentException> { GradleConfigParser.parse(json("""{"projectDirectory": null}""")) }
            ex.message shouldContain "projectDirectory"
        }

        "blank projectDirectory should throw" {
            val ex = shouldThrow<IllegalArgumentException> { GradleConfigParser.parse(json("""{"projectDirectory": "   "}""")) }
            ex.message shouldContain "projectDirectory"
        }

        "javaHome is optional and unset by default" {
            GradleConfigParser.parse(json("""{"projectDirectory": "/tmp"}""")).javaHome shouldBe null
        }

        "an absolute javaHome should parse" {
            GradleConfigParser.parse(json("""{"projectDirectory": "/tmp", "javaHome": "/jdk"}""")).javaHome shouldBe "/jdk"
        }

        "JSON null or blank javaHome means unset" {
            GradleConfigParser.parse(json("""{"projectDirectory": "/tmp", "javaHome": null}""")).javaHome shouldBe null
            GradleConfigParser.parse(json("""{"projectDirectory": "/tmp", "javaHome": "  "}""")).javaHome shouldBe null
        }

        "gradlewDirectory is optional: unset, the gradle directory is the project directory" {
            val config = GradleConfigParser.parse(json("""{"projectDirectory": "/tmp/p"}"""))

            config.gradlewDirectory shouldBe null
            config.gradleDirectory shouldBe File("/tmp/p")
        }

        "a relative gradlewDirectory is resolved against the project directory" {
            val config = GradleConfigParser.parse(json("""{"projectDirectory": "/tmp/p", "gradlewDirectory": "backend"}"""))

            config.gradlewDirectory shouldBe "backend"
            config.gradleDirectory shouldBe File("/tmp/p/backend")
        }

        "an absolute gradlewDirectory inside the project directory is accepted" {
            val config = GradleConfigParser.parse(json("""{"projectDirectory": "/tmp/p", "gradlewDirectory": "/tmp/p/backend"}"""))

            config.gradleDirectory shouldBe File("/tmp/p/backend")
        }

        "a gradlewDirectory with a dot or a dot-dot that stays inside the project is normalized" {
            GradleConfigParser.parse(json("""{"projectDirectory": "/tmp/p", "gradlewDirectory": "."}""")).gradleDirectory shouldBe File("/tmp/p")
            GradleConfigParser.parse(json("""{"projectDirectory": "/tmp/p", "gradlewDirectory": "a/../backend"}""")).gradleDirectory shouldBe
                File("/tmp/p/backend")
        }

        "JSON null or blank gradlewDirectory means unset" {
            GradleConfigParser.parse(json("""{"projectDirectory": "/tmp/p", "gradlewDirectory": null}""")).gradlewDirectory shouldBe null
            GradleConfigParser.parse(json("""{"projectDirectory": "/tmp/p", "gradlewDirectory": "  "}""")).gradlewDirectory shouldBe null
        }

        "a gradlewDirectory that climbs out of the project directory should throw" {
            listOf("..", "../other", "backend/../../other").forEach { directory ->
                val ex =
                    shouldThrow<IllegalArgumentException> {
                        GradleConfigParser.parse(json("""{"projectDirectory": "/tmp/p", "gradlewDirectory": "$directory"}"""))
                    }
                ex.message shouldContain "gradlewDirectory"
            }
        }

        "an absolute gradlewDirectory outside the project directory should throw" {
            val ex =
                shouldThrow<IllegalArgumentException> {
                    GradleConfigParser.parse(json("""{"projectDirectory": "/tmp/p", "gradlewDirectory": "/etc"}"""))
                }
            ex.message shouldContain "gradlewDirectory"
        }

        "a sibling directory that merely starts with the project name is not inside it" {
            val ex =
                shouldThrow<IllegalArgumentException> {
                    GradleConfigParser.parse(json("""{"projectDirectory": "/tmp/p", "gradlewDirectory": "/tmp/p-other"}"""))
                }
            ex.message shouldContain "gradlewDirectory"
        }

        "inheritEnvironment is empty by default" {
            GradleConfigParser.parse(json("""{"projectDirectory": "/tmp"}""")).inheritEnvironment shouldBe emptySet<String>()
        }

        "inheritEnvironment lists the variables to pass through" {
            val config = json("""{"projectDirectory": "/tmp", "inheritEnvironment": ["ORG_GRADLE_PROJECT_token", " B_C ", ""]}""")

            GradleConfigParser.parse(config).inheritEnvironment shouldBe setOf("ORG_GRADLE_PROJECT_token", "B_C")
        }

        "JSON null inheritEnvironment means none" {
            GradleConfigParser.parse(json("""{"projectDirectory": "/tmp", "inheritEnvironment": null}""")).inheritEnvironment shouldBe
                emptySet<String>()
        }

        "an invalid variable name in inheritEnvironment should throw" {
            listOf("BAD NAME", "A=1", "*", "1ABC").forEach { name ->
                val ex =
                    shouldThrow<IllegalArgumentException> {
                        GradleConfigParser.parse(json("""{"projectDirectory": "/tmp", "inheritEnvironment": ["$name"]}"""))
                    }
                ex.message shouldContain "inheritEnvironment"
            }
        }

        "inheritEnvironment that is not an array should throw" {
            val ex =
                shouldThrow<IllegalArgumentException> {
                    GradleConfigParser.parse(json("""{"projectDirectory": "/tmp", "inheritEnvironment": "TOKEN"}"""))
                }
            ex.message shouldContain "inheritEnvironment"
        }

        "relative javaHome should throw" {
            val ex = shouldThrow<IllegalArgumentException> { GradleConfigParser.parse(json("""{"projectDirectory": "/tmp", "javaHome": "jdk"}""")) }
            ex.message shouldContain "javaHome"
        }

        "relative projectDirectory should throw" {
            val ex = shouldThrow<IllegalArgumentException> { GradleConfigParser.parse(json("""{"projectDirectory": "relative/path"}""")) }
            ex.message shouldContain "absolute"
        }
    })
