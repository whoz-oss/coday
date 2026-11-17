package io.whozoss.agentos.configFile

import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.agentos.agentConfig.AgentConfigRepository
import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.integrationConfig.IntegrationConfigRepository
import io.whozoss.agentos.plugin.filesystem.ConfigFile
import io.whozoss.agentos.plugin.filesystem.ConfigFileSource
import io.whozoss.agentos.plugin.filesystem.FilesystemEntityKind
import io.whozoss.agentos.prompt.PromptRepository
import io.whozoss.agentos.sdk.entity.Entity
import io.whozoss.agentos.skill.SkillRepository
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service
import java.util.UUID
import kotlin.io.path.invariantSeparatorsPathString

/**
 * Tells, for each config file of a namespace, whether AgentOS reads the file or a database copy that
 * masks it, and shows the differences between the two.
 *
 * Both sides are rendered with the file models ([agentFileModel] and its siblings), the ones the YAML
 * exports write: a copy is "modified" only when it differs from its file in what AgentOS reads, never
 * because of the file's formatting or comments.
 *
 * Permissions are the callers' concern: [list] answers for any namespace, [diff] for any config.
 */
@Service
class ConfigFileService(
    agentConfigRepository: AgentConfigRepository,
    integrationConfigRepository: IntegrationConfigRepository,
    skillRepository: SkillRepository,
    promptRepository: PromptRepository,
    @param:Qualifier("yamlExportMapper") private val yamlMapper: ObjectMapper,
) {
    private val kinds: Map<FilesystemEntityKind, KindSupport<*>> =
        listOf(
            KindSupport(
                kind = FilesystemEntityKind.AGENT,
                source = agentConfigRepository,
                find = { agentConfigRepository.findByIds(listOf(it)).firstOrNull() },
                sharedNamespaceOf = { it.namespaceId },
                nameOf = { it.name },
                render = { agent, file -> single(file, agentFileModel(agent, file.file.parent), agentFileLosses(agent)) },
            ),
            KindSupport(
                kind = FilesystemEntityKind.INTEGRATION,
                source = integrationConfigRepository,
                find = { integrationConfigRepository.findByIds(listOf(it)).firstOrNull() },
                sharedNamespaceOf = { config -> config.namespaceId.takeIf { config.userId == null } },
                nameOf = { it.name },
                render = { config, file -> single(file, integrationFileModel(config, file.root.toString())) },
            ),
            KindSupport(
                kind = FilesystemEntityKind.SKILL,
                source = skillRepository,
                find = { skillRepository.findByIds(listOf(it)).firstOrNull() },
                sharedNamespaceOf = { it.namespaceId },
                nameOf = { it.name },
                render = { skill, file ->
                    val directory = file.root.relativize(file.file.parent).invariantSeparatorsPathString
                    RenderedConfig(
                        files =
                            mapOf(file.relativePath to skillFileContent(skill, yamlMapper)) +
                                skill.resources.mapKeys { (resource, _) -> "$directory/$resource" },
                    )
                },
            ),
            KindSupport(
                kind = FilesystemEntityKind.PROMPT,
                source = promptRepository,
                find = { promptRepository.findByIds(listOf(it)).firstOrNull() },
                sharedNamespaceOf = { prompt -> prompt.namespaceId.takeIf { prompt.userId == null } },
                nameOf = { it.name },
                render = { prompt, file -> single(file, promptFileModel(prompt), promptFileLosses(prompt)) },
            ),
        ).associateBy { it.kind }

    /** The config files of [namespaceId], every kind, sorted by kind then name. */
    fun list(namespaceId: UUID): List<ConfigFileDto> = kinds.values.flatMap { it.statuses(namespaceId) }

    /**
     * The differences between the database copy [configId] and the file it masks.
     *
     * @throws ResourceNotFoundException when [configId] is unknown or masks no file (a file config, a
     * database-only config, a platform or personal one).
     */
    fun diff(
        kind: FilesystemEntityKind,
        configId: UUID,
    ): ConfigFileDiffDto = requireNotNull(kinds[kind]) { "No config file support for $kind" }.diff(configId)

    private fun single(
        file: ConfigFile<*>,
        model: Map<String, Any?>,
        notes: List<String> = emptyList(),
    ) = RenderedConfig(mapOf(file.relativePath to yamlMapper.writeValueAsString(model)), notes)

    private class KindSupport<T : Entity>(
        val kind: FilesystemEntityKind,
        val source: ConfigFileSource<T>,
        val find: (UUID) -> T?,
        val sharedNamespaceOf: (T) -> UUID?,
        val nameOf: (T) -> String,
        val render: (T, ConfigFile<T>) -> RenderedConfig,
    ) {
        fun statuses(namespaceId: UUID): List<ConfigFileDto> =
            source.configFiles(namespaceId).map { file ->
                val copy = file.overriddenBy
                ConfigFileDto(
                    kind = kind,
                    name = nameOf(file.entity),
                    path = file.relativePath,
                    state = if (copy == null) ConfigFileState.FILE else ConfigFileState.OVERRIDDEN,
                    configId = (copy ?: file.entity).metadata.id,
                    modified = copy?.let { render(it, file).files != render(file.entity, file).files },
                )
            }

        fun diff(configId: UUID): ConfigFileDiffDto {
            val copy = find(configId) ?: throw ResourceNotFoundException("${kind.label} not found: $configId")
            val file =
                sharedNamespaceOf(copy)
                    ?.let { namespaceId -> source.configFiles(namespaceId) }
                    ?.firstOrNull { it.overriddenBy?.metadata?.id == configId }
                    ?: throw ResourceNotFoundException("${kind.label} $configId does not override a file")
            val fileSide = render(file.entity, file)
            val copySide = render(copy, file)
            val paths = (listOf(file.relativePath) + (fileSide.files.keys + copySide.files.keys).sorted()).distinct()
            return ConfigFileDiffDto(
                kind = kind,
                name = nameOf(copy),
                configId = configId,
                path = file.relativePath,
                modified = fileSide.files != copySide.files,
                files = paths.map { ConfigFileDiffEntryDto(it, fileSide.files[it], copySide.files[it]) },
                notes = copySide.notes,
            )
        }
    }

    /** A config in its file format: content by path relative to the namespace's `configPath`. */
    private data class RenderedConfig(
        val files: Map<String, String>,
        val notes: List<String> = emptyList(),
    )
}

private val FilesystemEntityKind.label: String get() = name.lowercase().replaceFirstChar { it.uppercase() }
