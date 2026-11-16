package io.whozoss.agentos.plugins.gradle

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files

class GradleToolProviderUnitSpec :
    StringSpec({

        val provider = GradleToolProvider()
        val mapper = jacksonObjectMapper()

        fun json(block: String) = mapper.readTree(block)

        fun <T> withTempProject(block: (String) -> T): T {
            val dir = Files.createTempDirectory("agentos-gradle-provider").toFile()
            try {
                // forward slashes: the path is embedded in a JSON string
                return block(dir.absolutePath.replace('\\', '/'))
            } finally {
                dir.deleteRecursively()
            }
        }

        // --- integrationType ---

        "integrationType should be GRADLE" {
            provider.integrationType shouldBe "GRADLE"
        }

        // --- configSchema ---

        "configSchema should be a JSON object schema requiring projectDirectory" {
            val schema = provider.configSchema
            schema.get("type").asText() shouldBe "object"
            schema.get("required").toString() shouldContain "projectDirectory"
            schema.get("additionalProperties").asBoolean() shouldBe false
        }

        "configSchema should document the path tokens of projectDirectory" {
            val description = provider.configSchema.get("properties").get("projectDirectory").get("description").asText()

            description shouldContain "{{CASE_EXCHANGE}}/repo"
            description shouldContain "{{NAMESPACE_CONFIG_PATH}}"
        }

        // --- null / missing / invalid config: the provider must not throw, returns empty ---

        "null config should return empty tool list" {
            provider.provideTools(null, "TEST").shouldBeEmpty()
        }

        "JSON null config should return empty tool list" {
            provider.provideTools(json("null"), "TEST").shouldBeEmpty()
        }

        "missing projectDirectory should return empty tool list" {
            provider.provideTools(json("{}"), "TEST").shouldBeEmpty()
        }

        "relative projectDirectory should return empty tool list" {
            provider.provideTools(json("""{"projectDirectory": "relative/path"}"""), "TEST").shouldBeEmpty()
        }

        // --- valid config ---

        "valid config should return the three tools prefixed with the config name" {
            withTempProject { dir ->
                val tools = provider.provideTools(json("""{"projectDirectory": "$dir"}"""), "MY_PROJECT")

                tools shouldHaveSize 3
                tools.map { it.name } shouldBe
                    listOf("MY_PROJECT__gradle_tests", "MY_PROJECT__gradle_compile", "MY_PROJECT__gradle_dependency_insight")
            }
        }

        "null configName should produce tools without prefix" {
            withTempProject { dir ->
                val tools = provider.provideTools(json("""{"projectDirectory": "$dir"}"""), null)

                tools.map { it.name } shouldBe listOf("gradle_tests", "gradle_compile", "gradle_dependency_insight")
            }
        }

        "every tool should declare a version and a schema requiring timeoutSeconds" {
            withTempProject { dir ->
                val tools = provider.provideTools(json("""{"projectDirectory": "$dir"}"""), null)

                tools.forEach {
                    it.version shouldBe "1.0.0"
                    it.inputSchema shouldContain "timeoutSeconds"
                }
            }
        }
    })
