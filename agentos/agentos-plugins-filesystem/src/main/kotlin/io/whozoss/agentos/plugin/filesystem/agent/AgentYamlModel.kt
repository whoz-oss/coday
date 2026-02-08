package io.whozoss.agentos.plugin.filesystem.agent

/**
 * YAML model for agent configuration files
 */
data class AgentYamlModel(
    val name: String,
    val description: String,
    val aiProvider: String? = null,
    val modelSize: String? = null,
    val modelName: String? = null,
    val mandatoryDocs: List<String>? = null,
    val optionalDocs: List<OptionalDoc>? = null,
    val instructions: String? = null,
    val integrations: Map<String, Any?>? = null,
    val capabilities: List<String>? = null,
    val contexts: List<String>? = null,
    val tags: List<String>? = null,
    val priority: Int? = null,
    val version: String? = null,
    val status: String? = null,
)

/**
 * Optional documentation reference
 */
data class OptionalDoc(
    val path: String,
    val description: String? = null,
)
