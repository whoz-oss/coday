package io.whozoss.agentos.aiModel

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.KotlinModule
import io.whozoss.agentos.aiProvider.AiProviderRepository
import io.whozoss.agentos.namespace.NamespaceRepository
import io.whozoss.agentos.plugin.filesystem.FilesystemYamlCacheRegistry
import io.whozoss.agentos.sdk.aiProvider.AiModel
import io.whozoss.agentos.sdk.aiProvider.AiProvider
import io.whozoss.agentos.sdk.aiProvider.ModelPricing
import io.whozoss.agentos.sdk.entity.EntityMetadata
import mu.KLogging
import java.nio.file.Path
import java.time.Duration
import java.util.UUID

/**
 * Decorator over a delegate [AiModelRepository] that augments namespace reads with [AiModel]
 * entries loaded from YAML files under `<namespace.configPath>/ai-models/`.
 *
 * A file names its provider (`provider: requesty`) instead of an id: the provider, which holds
 * the API key, stays in each installation's database. It is resolved at read time among the
 * namespace-scoped providers, then platform providers; a model whose provider does not exist is
 * skipped, so a checkout without that provider simply does not get the model.
 *
 * Filesystem models are namespace-scoped and take part in alias resolution like persisted ones
 * (scope, then [AiModel.priority]). Collision rule, mirroring the other filesystem decorators:
 * a persisted namespace model with the same provider and alias (or apiModelName when there is
 * no alias) wins and the filesystem entry is dropped. The filesystem is never written; such a
 * model is changed by editing its file.
 *
 * Filesystem reads are cached per directory with a configurable [ttl] (default 5 minutes).
 */
class FilesystemAiModelRepository(
    private val delegate: AiModelRepository,
    private val namespaceRepository: NamespaceRepository,
    private val aiProviderRepository: AiProviderRepository,
    private val yamlMapper: ObjectMapper = ObjectMapper(YAMLFactory()).registerModule(KotlinModule.Builder().build()),
    ttl: Duration = Duration.ofMinutes(5),
) : AiModelRepository by delegate {
    private val cacheRegistry =
        FilesystemYamlCacheRegistry(
            parser = ::parseYamlFile,
            ttl = ttl,
        )

    /** Namespace-scoped models: persisted plus filesystem. */
    override fun findByNamespaceId(namespaceId: UUID): List<AiModel> {
        val persisted = delegate.findByNamespaceId(namespaceId)
        return persisted + filesystemModels(namespaceId, persisted)
    }

    /** Runtime alias resolution set: namespace-scoped (persisted plus filesystem) and platform models. */
    override fun findAllForNamespace(namespaceId: UUID): List<AiModel> {
        val fromDelegate = delegate.findAllForNamespace(namespaceId)
        return fromDelegate + filesystemModels(namespaceId, fromDelegate.filter { it.namespaceId == namespaceId })
    }

    private fun filesystemModels(
        namespaceId: UUID,
        persistedInNamespace: List<AiModel>,
    ): List<AiModel> {
        val configPath =
            namespaceRepository.findByIds(listOf(namespaceId)).firstOrNull()?.configPath
                ?: return emptyList()
        val definitions = cacheRegistry.getAll(Path.of(configPath, AI_MODELS_SUBDIR))
        if (definitions.isEmpty()) return emptyList()

        val providers = providersByName(namespaceId)
        val persistedKeys = persistedInNamespace.mapTo(HashSet()) { key(it.aiProviderId, it.alias ?: it.apiModelName) }
        return definitions.mapNotNull { definition ->
            val provider = providers[definition.provider.lowercase()]
            if (provider == null) {
                logger.debug { "[FilesystemAiModelRepository] Skipping ${definition.source}: no provider '${definition.provider}' in namespace $namespaceId" }
                return@mapNotNull null
            }
            definition
                .toAiModel(namespaceId, provider)
                .takeUnless { key(it.aiProviderId, it.alias ?: it.apiModelName) in persistedKeys }
        }
    }

    /** Namespace-scoped providers shadow platform providers of the same name. */
    private fun providersByName(namespaceId: UUID): Map<String, AiProvider> =
        (aiProviderRepository.findPlatformLevel() + aiProviderRepository.findByNamespaceId(namespaceId).filter { it.userId == null })
            .associateBy { it.name.lowercase() }

    private fun key(
        providerId: UUID,
        name: String,
    ) = "$providerId|${name.lowercase()}"

    private fun parseYamlFile(
        directory: Path,
        file: Path,
    ): AiModelDefinition? {
        val yaml = yamlMapper.readValue(file.toFile(), AiModelYamlModel::class.java)
        if (yaml.apiModelName.isBlank() || yaml.provider.isBlank()) {
            logger.warn { "[FilesystemAiModelRepository] Skipping $file: 'apiModelName' and 'provider' are required" }
            return null
        }
        return AiModelDefinition(yaml, directory.relativize(file).toString())
    }

    private data class AiModelDefinition(
        val yaml: AiModelYamlModel,
        val source: String,
    ) {
        val provider: String get() = yaml.provider

        fun toAiModel(
            namespaceId: UUID,
            provider: AiProvider,
        ) = AiModel(
            // Stable id per namespace, provider and model so identity survives restarts.
            metadata =
                EntityMetadata(
                    id =
                        UUID.nameUUIDFromBytes(
                            "filesystem-ai-model:$namespaceId:${provider.name}:${yaml.alias ?: yaml.apiModelName}"
                                .toByteArray(Charsets.UTF_8),
                        ),
                ),
            aiProviderId = provider.id,
            namespaceId = namespaceId,
            apiModelName = yaml.apiModelName,
            description = yaml.description,
            alias = yaml.alias,
            priority = yaml.priority,
            temperature = yaml.temperature,
            maxCompletionTokens = yaml.maxCompletionTokens,
            contextWindow = yaml.contextWindow,
            pricing = yaml.pricing,
        )
    }

    companion object : KLogging() {
        private const val AI_MODELS_SUBDIR = "ai-models"
    }
}

/**
 * YAML model for AI model files read from `<configPath>/ai-models/`.
 *
 * [apiModelName] and [provider] (the provider's name) are required. Prices are in the platform
 * currency per million tokens, as on [AiModel.pricing].
 *
 * Example:
 * ```yaml
 * alias: BIG
 * apiModelName: anthropic/claude-sonnet-5-5
 * provider: requesty
 * priority: 10
 * contextWindow: 1000000
 * pricing:
 *   inputMTokens: 2
 *   outputMTokens: 10
 *   cacheRead: 0.2
 *   cacheWrite: 2.5
 * ```
 */
@JsonIgnoreProperties(ignoreUnknown = true)
private data class AiModelYamlModel(
    val apiModelName: String = "",
    val provider: String = "",
    val alias: String? = null,
    val description: String? = null,
    val priority: Int = 0,
    val temperature: Double? = null,
    val maxCompletionTokens: Int? = null,
    val contextWindow: Long? = null,
    val pricing: ModelPricing? = null,
)
