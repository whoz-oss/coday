package io.whozoss.agentos.plugin.filesystem.aiprovider

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.KotlinModule
import io.whozoss.agentos.sdk.aiProvider.AiApiType
import io.whozoss.agentos.sdk.aiProvider.AiProvider
import io.whozoss.agentos.sdk.aiProvider.AiProviderPlugin
import io.whozoss.agentos.sdk.entity.EntityMetadata
import mu.KLogging
import org.pf4j.Extension
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.util.UUID

@Extension
class FilesystemAIProviderProvider : AiProviderPlugin {
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
            logger.warn { "AI provider directory does not exist: $aiProviderPath" }
            return emptyList()
        }

        if (!Files.isDirectory(aiProviderPath)) {
            logger.error { "AI provider path is not a directory: $aiProviderPath" }
            return emptyList()
        }

        logger.info { "Loading AI provider from directory: $aiProviderPath" }

        try {
            Files
                .walk(aiProviderPath)
                .filter { Files.isRegularFile(it) }
                .filter { it.toString().endsWith(".yaml") || it.toString().endsWith(".yml") }
                .forEach { yamlFile ->
                    try {
                        logger.debug { "Processing aiProvider file: $yamlFile" }
                        val aiProvider = loadAiProviderFromYaml(yamlFile.toFile())
                        aiProviders.add(aiProvider)
                        logger.info { "Loaded AI Provider '${aiProvider.name}' from ${yamlFile.fileName}" }
                    } catch (e: Exception) {
                        logger.error(e) { "Failed to load AI Provider from $yamlFile: ${e.message}" }
                    }
                }
        } catch (e: Exception) {
            logger.error(e) { "Failed to scan AI Provider directory: ${e.message}" }
        }

        return aiProviders
    }

    override fun getDescription(): String = "Loads ai providers from YAML files in the filesystem"

    private fun loadAiProviderFromYaml(file: File): AiProvider {
        val yamlModel = yamlMapper.readValue(file, AiProviderYamlModel::class.java)

        return AiProvider(
            metadata = EntityMetadata(id = deterministicId(yamlModel.name)),
            name = yamlModel.name,
            description = yamlModel.description,
            apiType = AiApiType.valueOf(yamlModel.apiType),
            baseUrl = yamlModel.baseUrl,
            apiKey = yamlModel.apiKey,
            headers = yamlModel.headers ?: emptyMap(),
        )
    }

    companion object : KLogging() {
        /**
         * Derives a stable UUID from a provider name so that AiModel entries loaded
         * by [FilesystemAiModelProvider] can reference the same provider across restarts
         * without a persistent store.
         */
        fun deterministicId(providerName: String): UUID =
            UUID.nameUUIDFromBytes("filesystem-aiprovider:$providerName".toByteArray(Charsets.UTF_8))
    }
}
