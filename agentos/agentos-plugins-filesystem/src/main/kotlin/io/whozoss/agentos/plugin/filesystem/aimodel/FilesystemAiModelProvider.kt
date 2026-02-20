package io.whozoss.agentos.plugin.filesystem.aimodel

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.KotlinModule
import io.whozoss.agentos.sdk.aiProvider.AiModel
import io.whozoss.agentos.sdk.aiProvider.AiModelPlugin
import mu.KLogging
import org.pf4j.Extension
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths

/**
 * Loads AiModel configurations from YAML files in the filesystem.
 *
 * Reads all `.yaml` / `.yml` files from the configured directory and registers
 * each as an AiModel in the AiModelRegistry.
 *
 * Directory is resolved from (in priority order):
 * 1. System property `agentos.aimodel.directory`
 * 2. Environment variable `AGENTOS_AIMODEL_DIRECTORY`
 * 3. Default: `aimodel`
 */
@Extension
class FilesystemAiModelProvider : AiModelPlugin {
    private val yamlMapper = ObjectMapper(YAMLFactory()).registerModule(KotlinModule.Builder().build())

    private val aiModelDirectory: String =
        System.getProperty(
            "agentos.aimodel.directory",
            System.getenv("AGENTOS_AIMODEL_DIRECTORY") ?: "aimodel",
        )

    override fun getPluginId(): String = "filesystem-ai-models"

    override fun getDescription(): String = "Loads AI model configurations from YAML files in the filesystem"

    override fun getAiModels(): List<AiModel> {
        val models = mutableListOf<AiModel>()

        val modelPath = Paths.get(aiModelDirectory)
        if (!Files.exists(modelPath)) {
            logger.warn { "AI model directory does not exist: $modelPath" }
            return emptyList()
        }

        if (!Files.isDirectory(modelPath)) {
            logger.error { "AI model path is not a directory: $modelPath" }
            return emptyList()
        }

        logger.info { "Loading AI models from directory: $modelPath" }

        try {
            Files
                .walk(modelPath)
                .filter { Files.isRegularFile(it) }
                .filter { it.toString().endsWith(".yaml") || it.toString().endsWith(".yml") }
                .forEach { yamlFile ->
                    try {
                        logger.debug { "Processing AI model file: $yamlFile" }
                        val model = loadAiModelFromYaml(yamlFile.toFile())
                        models.add(model)
                        logger.info { "Loaded AI model '${model.name}' from ${yamlFile.fileName}" }
                    } catch (e: Exception) {
                        logger.error(e) { "Failed to load AI model from $yamlFile: ${e.message}" }
                    }
                }
        } catch (e: Exception) {
            logger.error(e) { "Failed to scan AI model directory: ${e.message}" }
        }

        return models
    }

    private fun loadAiModelFromYaml(file: File): AiModel {
        val yaml = yamlMapper.readValue(file, AiModelYamlModel::class.java)

        return AiModel(
            name = yaml.name,
            description = yaml.description,
            modelName = yaml.modelName,
            providerName = yaml.providerName,
            temperature = yaml.temperature,
            maxTokens = yaml.maxTokens,
            instructions = yaml.instructions,
        )
    }

    override fun initialize() {
        logger.info { "FilesystemAiModelProvider initialized" }
        logger.info { "AI model directory: $aiModelDirectory" }
        val models = getAiModels()
        logger.info { "Loaded ${models.size} AI model(s) from filesystem" }
        models.forEach { model ->
            logger.info { "  - ${model.name}: ${model.modelName} via ${model.providerName}" }
        }
    }

    override fun destroy() {
        logger.info { "FilesystemAiModelProvider destroyed" }
    }

    companion object : KLogging()
}
