package io.whozoss.agentos.plugins.filesystem.aiprovider

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.KotlinModule
import io.whozoss.agentos.sdk.model.AiApiType
import io.whozoss.agentos.sdk.model.AiProvider
import io.whozoss.agentos.sdk.plugin.AiProviderPlugin
import org.pf4j.Extension
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths

@Extension
class FilesystemAIProviderProvider : AiProviderPlugin {
    private val logger = LoggerFactory.getLogger(FilesystemAIProviderProvider::class.java)
    private val yamlMapper = ObjectMapper(YAMLFactory()).registerModule(KotlinModule.Builder().build())

    private val aiProviderDirectory: String =
        System.getProperty(
            "agentos.aiprovider.directory",
            System.getenv("AGENTOS_AIPROVIDER_DIRECTORY") ?: "aiprovider",
        )

    override fun getPluginId(): String = "filesystem-ai-providers"

    override fun getAiProviders(): List<AiProvider> {
        val aiProviders = mutableListOf<AiProvider>()

        val aiProviderPath = Paths.get(aiProviderDirectory)
        if (!Files.exists(aiProviderPath)) {
            logger.warn("AI provider directory does not exist: $aiProviderPath")
            return emptyList()
        }

        if (!Files.isDirectory(aiProviderPath)) {
            logger.error("AI provider path is not a directory: $aiProviderPath")
            return emptyList()
        }

        logger.info("Loading AI provider from directory: $aiProviderPath")

        try {
            Files
                .walk(aiProviderPath)
                .filter { Files.isRegularFile(it) }
                .filter { it.toString().endsWith(".yaml") || it.toString().endsWith(".yml") }
                .forEach { yamlFile ->
                    try {
                        logger.debug("Processing aiProvider file: {}", yamlFile)
                        val aiProvider = loadAiProviderFromYaml(yamlFile.toFile())
                        aiProviders.add(aiProvider)
                        logger.info("Loaded AI Provider '${aiProvider.id}' from ${yamlFile.fileName}")
                    } catch (e: Exception) {
                        logger.error("Failed to load AI Provider from $yamlFile: ${e.message}", e)
                    }
                }
        } catch (e: Exception) {
            logger.error("Failed to scan AI Provider directory: ${e.message}", e)
        }

        return aiProviders
    }

    override fun getDescription(): String = "Loads ai providers from YAML files in the filesystem"

    private fun loadAiProviderFromYaml(file: File): AiProvider {
        val yamlModel = yamlMapper.readValue(file, AiProviderYamlModel::class.java)

        return AiProvider(
            name = yamlModel.name,
            description = yamlModel.description,
            apiType = AiApiType.valueOf(yamlModel.apiType),
            defaultApiKey = yamlModel.defaultApiKey,
            baseUrl = yamlModel.baseUrl,
            baseModel = yamlModel.baseModel,
            temperature = yamlModel.temperature ?: 1.0,
            maxTokens = yamlModel.maxTokens,
        )
    }

    override fun initialize() {
        logger.info("FilesystemAgentProvider initialized")
        logger.info("Ai providers directory: $aiProviderDirectory")
        val aiProviders = getAiProviders()
        logger.info("Loaded ${aiProviders.size} Ai Providers(s) from filesystem")
        aiProviders.forEach { aiProvider ->
            logger.info("  - ${aiProvider.id}: ${aiProvider.name} (type: ${aiProvider.apiType})")
        }
    }

    override fun destroy() {
        logger.info("FilesystemAgentProvider destroyed")
    }
}
