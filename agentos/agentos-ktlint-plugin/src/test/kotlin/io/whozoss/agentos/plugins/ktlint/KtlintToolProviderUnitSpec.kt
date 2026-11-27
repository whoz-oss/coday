package io.whozoss.agentos.plugins.ktlint

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files

class KtlintToolProviderUnitSpec :
    StringSpec({

        val provider = KtlintToolProvider()
        val mapper = jacksonObjectMapper()

        fun json(block: String) = mapper.readTree(block)

        fun <T> withTempProject(block: (String) -> T): T {
            val dir = Files.createTempDirectory("agentos-ktlint-provider").toFile()
            try {
                // forward slashes: the path is embedded in a JSON string
                return block(dir.absolutePath.replace('\\', '/'))
            } finally {
                dir.deleteRecursively()
            }
        }

        // --- integrationType ---

        "integrationType should be KTLINT" {
            provider.integrationType shouldBe "KTLINT"
        }

        // --- configSchema ---

        "configSchema should be a JSON object schema requiring projectDirectory" {
            val schema = provider.configSchema
            schema.get("type").asText() shouldBe "object"
            schema.get("required").toString() shouldContain "projectDirectory"
            schema.get("additionalProperties").asBoolean() shouldBe false
        }

        "configSchema should document the path tokens of projectDirectory" {
            val description =
                provider.configSchema
                    .get("properties")
                    .get("projectDirectory")
                    .get("description")
                    .asText()

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

        "nonexistent projectDirectory should return empty tool list" {
            withTempProject { dir ->
                provider.provideTools(json("""{"projectDirectory": "$dir/does-not-exist"}"""), "TEST").shouldBeEmpty()
            }
        }

        "projectDirectory that is a file should return empty tool list" {
            withTempProject { dir ->
                java.io.File(dir, "file.txt").writeText("x")

                provider.provideTools(json("""{"projectDirectory": "$dir/file.txt"}"""), "TEST").shouldBeEmpty()
            }
        }

        // --- valid config ---

        "valid config should return the two tools prefixed with the config name" {
            withTempProject { dir ->
                val tools = provider.provideTools(json("""{"projectDirectory": "$dir"}"""), "MY_PROJECT")

                tools shouldHaveSize 2
                tools.map { it.name } shouldBe listOf("MY_PROJECT__ktlint_check", "MY_PROJECT__ktlint_format")
                tools[0].shouldBeInstanceOf<KtlintCheckTool>()
                tools[1].shouldBeInstanceOf<KtlintFormatTool>()
            }
        }

        "null configName should produce tools without prefix" {
            withTempProject { dir ->
                val tools = provider.provideTools(json("""{"projectDirectory": "$dir"}"""), null)

                tools.map { it.name } shouldBe listOf("ktlint_check", "ktlint_format")
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

        // --- every project uses the one engine shared by the plugin ---

        fun engineOf(tool: io.whozoss.agentos.sdk.tool.StandardTool<*>): Any =
            when (tool) {
                is KtlintCheckTool -> {
                    tool.bounded.runner
                        .shouldBeInstanceOf<KtlintRunner>()
                        .engine
                }

                is KtlintFormatTool -> {
                    tool.bounded.runner
                        .shouldBeInstanceOf<KtlintRunner>()
                        .engine
                }

                else -> {
                    error("Not a ktlint tool: ${tool.name}")
                }
            }

        "two provideTools calls on the same project use the very same shared ktlint engine" {
            withTempProject { dir ->
                val first = provider.provideTools(json("""{"projectDirectory": "$dir"}"""), "ONE")
                val second = provider.provideTools(json("""{"projectDirectory": "$dir/."}"""), "TWO")

                (engineOf(tool = first.first()) === engineOf(tool = second.last())) shouldBe true
            }
        }

        "provideTools on two different projects uses the same shared ktlint engine" {
            withTempProject { dir ->
                withTempProject { otherDir ->
                    val first = provider.provideTools(json("""{"projectDirectory": "$dir"}"""), null)
                    val second = provider.provideTools(json("""{"projectDirectory": "$otherDir"}"""), null)

                    (engineOf(tool = first.first()) === engineOf(tool = second.first())) shouldBe true
                }
            }
        }

        "tools of a valid config should work on the files of the configured project" {
            withTempProject { dir ->
                java.io.File(dir, "Sample.kt").writeText(WILDCARD_IMPORT_SOURCE)
                val tools = provider.provideTools(json("""{"projectDirectory": "$dir"}"""), null)
                val check = tools.filterIsInstance<KtlintCheckTool>().single()

                check.check(files = listOf("Sample.kt"), timeoutSeconds = TIMEOUT_SECONDS).failureText() shouldContain "no-wildcard-imports"
            }
        }
    })
