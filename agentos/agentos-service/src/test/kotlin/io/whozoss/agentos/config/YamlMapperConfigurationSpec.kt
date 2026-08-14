package io.whozoss.agentos.config

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * Guards the YAML serialization contracts for [yamlMapper] and [yamlExportMapper].
 *
 * Instantiates the mappers directly from [ObjectMapperConfiguration] — no Spring
 * context needed, no Neo4j, no slow startup.
 *
 * [yamlMapper] — reads YAML files from the filesystem into Kotlin data classes
 * (AgentConfig, IntegrationConfig, Prompt). Must support the Kotlin module.
 *
 * [yamlExportMapper] — serializes export models to clean, human-readable YAML:
 * no `---` document start marker.
 */
class YamlMapperConfigurationSpec : StringSpec() {

    private val config = ObjectMapperConfiguration()
    private val yamlMapper: ObjectMapper = config.yamlMapper()
    private val yamlExportMapper: ObjectMapper = config.yamlExportMapper()

    init {
        // -------------------------------------------------------------------------
        // yamlMapper — reading YAML into Kotlin data classes
        // -------------------------------------------------------------------------

        "yamlMapper deserializes a minimal agent YAML into a data class" {
            val yaml = """
                name: my-agent
                description: A test agent
                instructions: Do things
            """.trimIndent()

            val model = yamlMapper.readValue<AgentYamlFixture>(yaml)

            model.name shouldBe "my-agent"
            model.description shouldBe "A test agent"
            model.instructions shouldBe "Do things"
            model.modelName shouldBe null
        }

        "yamlMapper ignores unknown fields" {
            val yaml = """
                name: my-agent
                unknownField: should be ignored
            """.trimIndent()

            val model = yamlMapper.readValue<AgentYamlFixture>(yaml)
            model.name shouldBe "my-agent"
        }

        "yamlMapper deserializes a map-of-nullable-lists (integrations field)" {
            val yaml = """
                name: my-agent
                integrations:
                  FILES:
                  JIRA:
                    - GetIssue
                    - CreateIssue
            """.trimIndent()

            val model = yamlMapper.readValue<AgentYamlFixture>(yaml)

            model.integrations shouldBe mapOf(
                "FILES" to null,
                "JIRA" to listOf("GetIssue", "CreateIssue"),
            )
        }

        "yamlMapper deserializes an integration config YAML with parameters" {
            val yaml = """
                name: JIRA
                integrationType: JIRA
                description: Jira instance
                parameters:
                  baseUrl: https://company.atlassian.net
                  project: MYPROJ
            """.trimIndent()

            val model = yamlMapper.readValue<IntegrationYamlFixture>(yaml)

            model.name shouldBe "JIRA"
            model.integrationType shouldBe "JIRA"
            model.description shouldBe "Jira instance"
            model.parameters?.get("baseUrl")?.asText() shouldBe "https://company.atlassian.net"
            model.parameters?.get("project")?.asText() shouldBe "MYPROJ"
        }

        "yamlMapper deserializes a prompt YAML with content list and parameters" {
            val yaml = """
                name: plan
                description: Create a plan
                content:
                  - "Analyse: {{ARGUMENTS}}"
                  - "Plan for {{scope}}"
                parameters:
                  - name: scope
                    description: what to plan
                    defaultValue: ""
            """.trimIndent()

            val model = yamlMapper.readValue<PromptYamlFixture>(yaml)

            model.name shouldBe "plan"
            model.content shouldBe listOf("Analyse: {{ARGUMENTS}}", "Plan for {{scope}}")
            model.parameters?.first()?.name shouldBe "scope"
        }

        // -------------------------------------------------------------------------
        // yamlExportMapper — the only spec that belongs to this mapper specifically
        // -------------------------------------------------------------------------

        "yamlExportMapper serializes a map and does not emit a document start marker" {
            val yaml = yamlExportMapper.writeValueAsString(mapOf("name" to "export", "version" to 1))
            yaml shouldContain "name:"
            yaml shouldNotContain "---"
        }
    }
}

// ---------------------------------------------------------------------------
// Local YAML fixture models — mirror the private models in the repositories
// ---------------------------------------------------------------------------

@JsonIgnoreProperties(ignoreUnknown = true)
private data class AgentYamlFixture(
    val name: String = "",
    val description: String? = null,
    val instructions: String? = null,
    val modelName: String? = null,
    val integrations: Map<String, List<String>?>? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class IntegrationYamlFixture(
    val name: String = "",
    val integrationType: String = "",
    val description: String? = null,
    val parameters: JsonNode? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class PromptYamlFixture(
    val name: String = "",
    val description: String? = null,
    val content: List<String>? = null,
    val parameters: List<PromptParameterFixture>? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class PromptParameterFixture(
    val name: String = "",
    val description: String? = null,
    val defaultValue: String = "",
)
