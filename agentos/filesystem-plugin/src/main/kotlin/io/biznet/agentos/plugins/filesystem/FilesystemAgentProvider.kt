package io.biznet.agentos.plugins.filesystem

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.KotlinModule
import io.biznet.agentos.agents.domain.Agent
import io.biznet.agentos.agents.domain.AgentStatus
import io.biznet.agentos.agents.domain.ContextType
import io.biznet.agentos.plugins.AgentPlugin
import org.pf4j.Extension
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths

/**
 * Provides agents loaded from YAML files in the filesystem
 */
@Extension
class FilesystemAgentProvider : AgentPlugin {
    private val logger = LoggerFactory.getLogger(FilesystemAgentProvider::class.java)
    private val yamlMapper = ObjectMapper(YAMLFactory()).registerModule(KotlinModule.Builder().build())

    // Configuration - can be overridden via system properties or environment variables
    private val agentsDirectory: String =
        System.getProperty(
            "agentos.agents.directory",
            System.getenv("AGENTOS_AGENTS_DIRECTORY") ?: "agents",
        )

    override fun getPluginId(): String = "filesystem-agents"

    override fun getVersion(): String = "1.0.0"

    override fun getDescription(): String = "Loads agents from YAML files in the filesystem"

    override fun getAgents(): List<Agent> {
        val agents = mutableListOf<Agent>()

        val agentsPath = Paths.get(agentsDirectory)
        if (!Files.exists(agentsPath)) {
            logger.warn("Agents directory does not exist: $agentsPath")
            return emptyList()
        }

        if (!Files.isDirectory(agentsPath)) {
            logger.error("Agents path is not a directory: $agentsPath")
            return emptyList()
        }

        logger.info("Loading agents from directory: $agentsPath")

        try {
            Files
                .walk(agentsPath)
                .filter { Files.isRegularFile(it) }
                .filter { it.toString().endsWith(".yaml") || it.toString().endsWith(".yml") }
                .forEach { yamlFile ->
                    try {
                        logger.debug("Processing agent file: $yamlFile")
                        val agent = loadAgentFromYaml(yamlFile.toFile())
                        agents.add(agent)
                        logger.info("Loaded agent '${agent.id}' from ${yamlFile.fileName}")
                    } catch (e: Exception) {
                        logger.error("Failed to load agent from $yamlFile: ${e.message}", e)
                    }
                }
        } catch (e: Exception) {
            logger.error("Failed to scan agents directory: ${e.message}", e)
        }

        return agents
    }

    private fun loadAgentFromYaml(file: File): Agent {
        val yamlModel = yamlMapper.readValue(file, AgentYamlModel::class.java)

        // Generate ID from filename (remove extension and normalize)
        val agentId =
            file.nameWithoutExtension
                .lowercase()
                .replace(Regex("[^a-z0-9-]"), "-")
                .replace(Regex("-+"), "-")
                .trim('-')

        // Extract capabilities from the YAML
        val capabilities = mutableListOf<String>()

        // Add explicit capabilities if provided
        yamlModel.capabilities?.let { capabilities.addAll(it) }

        // Infer capabilities from AI provider and model
        yamlModel.aiProvider?.let { capabilities.add("ai-$it") }
        yamlModel.modelSize?.let { capabilities.add("model-${it.lowercase()}") }

        // Add capabilities based on integrations
        yamlModel.integrations?.keys?.forEach { integration ->
            capabilities.add(integration.lowercase())
        }

        // If no capabilities specified, add a default one
        if (capabilities.isEmpty()) {
            capabilities.add("general-assistance")
        }

        // Parse context types
        val contextTypes = parseContextTypes(yamlModel.contexts)

        // Parse tags
        val tags = mutableSetOf("filesystem-agent")
        yamlModel.tags?.let { tags.addAll(it) }
        yamlModel.aiProvider?.let { tags.add("ai-$it") }

        // Parse status
        val status = parseStatus(yamlModel.status)

        return Agent(
            id = agentId,
            name = yamlModel.name,
            description = yamlModel.description,
            version = yamlModel.version ?: "1.0.0",
            capabilities = capabilities.distinct(),
            requiredContext = contextTypes,
            tags = tags,
            priority = yamlModel.priority ?: 5,
            status = status,
        )
    }

    private fun parseContextTypes(contexts: List<String>?): Set<ContextType> {
        if (contexts.isNullOrEmpty()) {
            return setOf(ContextType.GENERAL)
        }

        val contextTypes = mutableSetOf<ContextType>()
        contexts.forEach { contextStr ->
            try {
                val contextType = ContextType.valueOf(contextStr.uppercase().replace("-", "_"))
                contextTypes.add(contextType)
            } catch (e: IllegalArgumentException) {
                logger.warn("Unknown context type: $contextStr, using GENERAL")
                contextTypes.add(ContextType.GENERAL)
            }
        }

        return contextTypes.ifEmpty { setOf(ContextType.GENERAL) }
    }

    private fun parseStatus(statusStr: String?): AgentStatus {
        if (statusStr == null) return AgentStatus.ACTIVE

        return try {
            AgentStatus.valueOf(statusStr.uppercase())
        } catch (e: IllegalArgumentException) {
            logger.warn("Unknown status: $statusStr, using ACTIVE")
            AgentStatus.ACTIVE
        }
    }

    override fun initialize() {
        logger.info("FilesystemAgentProvider initialized")
        logger.info("Agents directory: $agentsDirectory")
        val agents = getAgents()
        logger.info("Loaded ${agents.size} agent(s) from filesystem")
        agents.forEach { agent ->
            logger.info("  - ${agent.id}: ${agent.name}")
        }
    }

    override fun destroy() {
        logger.info("FilesystemAgentProvider destroyed")
    }
}
