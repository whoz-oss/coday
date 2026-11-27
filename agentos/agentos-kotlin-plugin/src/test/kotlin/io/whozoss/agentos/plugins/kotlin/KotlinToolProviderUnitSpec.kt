package io.whozoss.agentos.plugins.kotlin

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files

class KotlinToolProviderUnitSpec :
    StringSpec({
        val provider = KotlinToolProvider()
        val mapper = jacksonObjectMapper()

        fun json(block: String) = mapper.readTree(block)

        fun <T> withTempProject(block: (String) -> T): T {
            val dir = Files.createTempDirectory("agentos-kotlin-provider").toFile()
            return try {
                // forward slashes: the path is embedded in a JSON string
                block(dir.absolutePath.replace('\\', '/'))
            } finally {
                dir.deleteRecursively()
            }
        }

        "integrationType should be KOTLIN" {
            provider.integrationType shouldBe "KOTLIN"
        }

        "configSchema should be a closed JSON object schema that requires nothing, so a platform layer can set serverBinary alone" {
            // given
            val schema = provider.configSchema

            // when / then
            schema.get("type").asText() shouldBe "object"
            schema.has("required") shouldBe false
            schema.get("properties").has("projectDirectory") shouldBe true
            schema.get("properties").has("serverBinary") shouldBe true
            schema.get("additionalProperties").asBoolean() shouldBe false
        }

        "a configuration with a serverBinary but no projectDirectory should return empty tool list" {
            provider.provideTools(config = json("""{"serverBinary": "/opt/lsp/intellij-server"}"""), configName = "TEST").shouldBeEmpty()
        }

        "configSchema should document the path tokens and where the binary comes from" {
            // given
            val description =
                provider.configSchema
                    .get("properties")
                    .get("projectDirectory")
                    .get("description")
                    .asText()

            // when / then
            description shouldContain "{{CASE_EXCHANGE}}/repo"
            description shouldContain "{{NAMESPACE_CONFIG_PATH}}"
            provider.configSchema.get("description").asText() shouldContain ENV_SERVER_BINARY
        }

        "null config should return empty tool list" {
            provider.provideTools(config = null, configName = "TEST").shouldBeEmpty()
        }

        "JSON null config should return empty tool list" {
            provider.provideTools(config = json("null"), configName = "TEST").shouldBeEmpty()
        }

        "missing projectDirectory should return empty tool list" {
            provider.provideTools(config = json("{}"), configName = "TEST").shouldBeEmpty()
        }

        "relative projectDirectory should return empty tool list" {
            provider.provideTools(config = json("""{"projectDirectory": "relative/path"}"""), configName = "TEST").shouldBeEmpty()
        }

        "nonexistent projectDirectory should return empty tool list" {
            withTempProject { dir ->
                provider.provideTools(config = json("""{"projectDirectory": "$dir/does-not-exist"}"""), configName = "TEST").shouldBeEmpty()
            }
        }

        "projectDirectory that is a file should return empty tool list" {
            withTempProject { dir ->
                // given
                java.io.File(dir, "file.txt").writeText("x")

                // when / then
                provider.provideTools(config = json("""{"projectDirectory": "$dir/file.txt"}"""), configName = "TEST").shouldBeEmpty()
            }
        }

        "valid config should return the 18 tools prefixed with the config name, without starting any server" {
            withTempProject { dir ->
                // when
                val tools = provider.provideTools(config = json("""{"projectDirectory": "$dir"}"""), configName = "MY_PROJECT")

                // then
                tools shouldHaveSize 18
                tools.all { it.name.startsWith("MY_PROJECT__kotlin_lsp_") } shouldBe true
            }
        }

        "null configName should produce tools without prefix" {
            withTempProject { dir ->
                // when
                val tools = provider.provideTools(config = json("""{"projectDirectory": "$dir"}"""), configName = null)

                // then
                tools.all { it.name.startsWith("kotlin_lsp_") } shouldBe true
            }
        }
    })
