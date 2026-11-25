package io.whozoss.agentos.configFile

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import io.whozoss.agentos.agentConfig.AgentConfigRepository
import io.whozoss.agentos.agentConfig.AgentConfigService
import io.whozoss.agentos.configAgent.ConfigSecretPolicy
import io.whozoss.agentos.exception.ConflictException
import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.exception.UnprocessableEntityException
import io.whozoss.agentos.integrationConfig.IntegrationConfigRepository
import io.whozoss.agentos.integrationConfig.IntegrationConfigService
import io.whozoss.agentos.namespace.NamespaceRepository
import io.whozoss.agentos.plugin.filesystem.ConfigFile
import io.whozoss.agentos.plugin.filesystem.ConfigFileSource
import io.whozoss.agentos.plugin.filesystem.FilesystemEntityKind
import io.whozoss.agentos.prompt.PromptRepository
import io.whozoss.agentos.prompt.PromptService
import io.whozoss.agentos.scheduledPrompt.ScheduledPromptRepository
import io.whozoss.agentos.sdk.api.configChange.ConfigKind
import io.whozoss.agentos.sdk.entity.Entity
import io.whozoss.agentos.skill.SkillRepository
import io.whozoss.agentos.skill.SkillService
import mu.KLogging
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.name

/**
 * Tells, for each config file of a namespace, whether AgentOS reads the file or a database copy that
 * masks it; shows the differences between the two; writes a copy back to its file, or discards it.
 *
 * Both sides are rendered with the file models ([agentFileModel] and its siblings), the ones the YAML
 * exports write: a copy is "modified" only when it differs from its file in what AgentOS reads, never
 * because of the file's formatting or comments. The same rendering is what [writeToFile] writes and
 * [download] hands out, so the three always agree.
 *
 * Permissions are the callers' concern.
 */
@Service
class ConfigFileService(
    agentConfigRepository: AgentConfigRepository,
    integrationConfigRepository: IntegrationConfigRepository,
    skillRepository: SkillRepository,
    promptRepository: PromptRepository,
    agentConfigService: AgentConfigService,
    integrationConfigService: IntegrationConfigService,
    skillService: SkillService,
    promptService: PromptService,
    scheduledPromptRepository: ScheduledPromptRepository,
    private val namespaceRepository: NamespaceRepository,
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
                delete = agentConfigService::delete,
                deleteBlockers = { agent ->
                    val prompts = promptRepository.countActiveByAgentConfigId(agent.metadata.id)
                    val schedulers = scheduledPromptRepository.countActiveByAgentConfigId(agent.metadata.id)
                    listOfNotNull(
                        attachedMessage(prompts, schedulers)?.let { attached ->
                            "$attached attached to this agent config: deleting it would delete them, and an agent file " +
                                "cannot hold them. Delete them first, or keep the database copy."
                        },
                    )
                },
            ),
            KindSupport(
                kind = FilesystemEntityKind.INTEGRATION,
                source = integrationConfigRepository,
                find = { integrationConfigRepository.findByIds(listOf(it)).firstOrNull() },
                sharedNamespaceOf = { config -> config.namespaceId.takeIf { config.userId == null } },
                nameOf = { it.name },
                render = { config, file -> single(file, integrationFileModel(config, file.root.toString())) },
                delete = integrationConfigService::delete,
                writeBlockers = { config -> clearTextCredentials(config.parameters) },
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
                delete = skillService::delete,
            ),
            KindSupport(
                kind = FilesystemEntityKind.PROMPT,
                source = promptRepository,
                find = { promptRepository.findByIds(listOf(it)).firstOrNull() },
                sharedNamespaceOf = { prompt -> prompt.namespaceId.takeIf { prompt.userId == null } },
                nameOf = { it.name },
                render = { prompt, file -> single(file, promptFileModel(prompt), promptFileLosses(prompt)) },
                delete = promptService::delete,
                deleteBlockers = { prompt ->
                    listOfNotNull(
                        "A scheduled prompt uses this prompt: deleting it is refused while the scheduler exists."
                            .takeIf { scheduledPromptRepository.existsActiveByPromptTemplateId(prompt.metadata.id) },
                    )
                },
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
    ): ConfigFileDiffDto = support(kind).diff(configId)

    /** The database copy [configId] in its file format: the file that [writeToFile] would write. */
    fun download(
        kind: FilesystemEntityKind,
        configId: UUID,
    ): ConfigFileDownload = support(kind).download(configId)

    /**
     * Writes the database copy [configId] to the file it masks, then deletes the copy so that AgentOS
     * reads the file again, and later changes of the file (a `git pull`) are not masked anymore.
     *
     * Nothing is written when [expectedFileHash] (from [diff]) no longer matches the file, when the copy
     * holds something its file cannot (a loop payload, a credential in clear), or when deleting the copy
     * would delete other configs with it. A failed write leaves the copy as it was.
     */
    fun writeToFile(
        kind: FilesystemEntityKind,
        configId: UUID,
        expectedFileHash: String,
    ): ConfigFileWriteResultDto = support(kind).writeToFile(configId, expectedFileHash)

    /** Deletes the database copy [configId] without writing anything: AgentOS reads its file again. */
    fun discard(
        kind: FilesystemEntityKind,
        configId: UUID,
    ): ConfigFileWriteResultDto = support(kind).discard(configId)

    private fun support(kind: FilesystemEntityKind): KindSupport<*> = requireNotNull(kinds[kind]) { "No config file support for $kind" }

    private fun single(
        file: ConfigFile<*>,
        model: Map<String, Any?>,
        notes: List<String> = emptyList(),
    ) = RenderedConfig(mapOf(file.relativePath to yamlMapper.writeValueAsString(model)), notes)

    private fun clearTextCredentials(parameters: JsonNode?): List<String> =
        ConfigSecretPolicy
            .clearTextSecrets(ConfigKind.INTEGRATION, null, JsonNodeFactory.instance.objectNode().set<JsonNode>("parameters", parameters), "")
            .map { pointer ->
                "The parameter ${pointer.removePrefix("/parameters/")} holds a credential in clear: a config file is meant " +
                    "to be committed. Move it to an auth setting first."
            }

    /** The other namespaces that read the files of [namespaceId]'s configPath: a write changes them too. */
    private fun alsoReadBy(namespaceId: UUID): List<String> {
        val namespaces = namespaceRepository.findByParent(NamespaceRepository.NAMESPACE_PARENT_KEY)
        val root = namespaces.firstOrNull { it.id == namespaceId }?.configPath?.let(::normalized) ?: return emptyList()
        return namespaces
            .filter { it.id != namespaceId && it.configPath?.let(::normalized) == root }
            .map { it.name }
            .sorted()
    }

    private fun normalized(path: String): Path = Path.of(path).toAbsolutePath().normalize()

    private inner class KindSupport<T : Entity>(
        val kind: FilesystemEntityKind,
        val source: ConfigFileSource<T>,
        val find: (UUID) -> T?,
        val sharedNamespaceOf: (T) -> UUID?,
        val nameOf: (T) -> String,
        val render: (T, ConfigFile<T>) -> RenderedConfig,
        val delete: (UUID) -> Boolean,
        /** What, in the copy, a file cannot hold safely: refuses the write, not the discard. */
        val writeBlockers: (T) -> List<String> = { emptyList() },
        /** What deleting the copy would break: refuses both the write and the discard. */
        val deleteBlockers: (T) -> List<String> = { emptyList() },
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
            val override = resolve(configId)
            val paths = (listOf(override.file.relativePath) + (override.fileSide.files.keys + override.copySide.files.keys).sorted()).distinct()
            return ConfigFileDiffDto(
                kind = kind,
                name = nameOf(override.copy),
                configId = configId,
                path = override.file.relativePath,
                modified = override.modified,
                files = paths.map { ConfigFileDiffEntryDto(it, override.fileSide.files[it], override.copySide.files[it]) },
                notes = override.copySide.notes,
                fileHash = override.fileHash(),
                alsoReadBy = alsoReadBy(override.namespaceId),
                cannotWrite = override.copySide.notes + writeBlockers(override.copy) + deleteBlockers(override.copy),
                cannotDiscard = deleteBlockers(override.copy),
            )
        }

        fun download(configId: UUID): ConfigFileDownload {
            val override = resolve(configId)
            val files = override.copySide.files
            if (kind != FilesystemEntityKind.SKILL) {
                return ConfigFileDownload(override.file.file.name, files.getValue(override.file.relativePath).toByteArray())
            }
            // A skill is a folder: hand it out as a zip of that folder, to unpack under skills/.
            val skillsFolder = override.file.root.resolve(SKILLS_FOLDER)
            val skillFolder = override.file.file.parent
            val zip = ByteArrayOutputStream()
            ZipOutputStream(zip).use { out ->
                files.toSortedMap().forEach { (path, content) ->
                    out.putNextEntry(ZipEntry(skillsFolder.relativize(override.file.root.resolve(path)).invariantSeparatorsPathString))
                    out.write(content.toByteArray())
                    out.closeEntry()
                }
            }
            return ConfigFileDownload("${skillFolder.name}.zip", zip.toByteArray())
        }

        fun writeToFile(
            configId: UUID,
            expectedFileHash: String,
        ): ConfigFileWriteResultDto {
            val override = resolve(configId)
            if (override.fileHash() != expectedFileHash) {
                throw ConflictException("The file ${override.file.relativePath} changed since you opened the differences: reload them.")
            }
            val refusals = override.copySide.notes + writeBlockers(override.copy) + deleteBlockers(override.copy)
            if (refusals.isNotEmpty()) throw UnprocessableEntityException(refusals.joinToString(" "))
            if (override.modified) write(override)
            source.reloadConfigFiles(override.namespaceId)
            return deleteCopy(override, written = override.modified)
        }

        fun discard(configId: UUID): ConfigFileWriteResultDto {
            val override = resolve(configId)
            val refusals = deleteBlockers(override.copy)
            if (refusals.isNotEmpty()) throw UnprocessableEntityException(refusals.joinToString(" "))
            return deleteCopy(override, written = false)
        }

        private fun write(override: Override<T>) {
            val root = override.file.root.toAbsolutePath().normalize()
            // The folder this config owns: the skill's folder, or the kind's folder of a single file.
            val owned = override.file.file.toAbsolutePath().normalize().parent
            val targets = override.copySide.files.mapKeys { (path, _) -> root.resolve(path).normalize() }
            val stale = (override.fileSide.files.keys - override.copySide.files.keys).map { root.resolve(it).normalize() }
            (targets.keys + stale).firstOrNull { !it.startsWith(owned) }?.let {
                throw UnprocessableEntityException("${root.relativize(it).invariantSeparatorsPathString} is outside the folder of this config.")
            }
            try {
                targets.forEach { (target, content) -> writeAtomically(target, content) }
                stale.forEach(Files::deleteIfExists)
            } catch (e: IOException) {
                logger.warn(e) { "[ConfigFileService] Cannot write ${override.file.file}" }
                throw ConfigFileWriteException(writeFailure(e, root.relativize(owned).invariantSeparatorsPathString))
            }
            logger.info { "[ConfigFileService] Wrote ${kind.name} '${nameOf(override.copy)}' to ${override.file.file}" }
        }

        private fun deleteCopy(
            override: Override<T>,
            written: Boolean,
        ): ConfigFileWriteResultDto {
            val path = override.file.relativePath
            val deleted =
                try {
                    delete(override.copy.metadata.id)
                } catch (e: Exception) {
                    logger.warn(e) { "[ConfigFileService] Cannot delete the copy ${override.copy.metadata.id} of $path" }
                    false
                }
            return ConfigFileWriteResultDto(
                path = path,
                written = written,
                copyDeleted = deleted,
                message =
                    if (deleted) {
                        null
                    } else {
                        "${if (written) "The file is written, but the" else "The"} database copy could not be deleted: AgentOS " +
                            "still uses it. Try again."
                    },
            )
        }

        private fun resolve(configId: UUID): Override<T> {
            val copy = find(configId) ?: throw ResourceNotFoundException("${kind.label} not found: $configId")
            val namespaceId = sharedNamespaceOf(copy)
            val file =
                namespaceId
                    ?.let { source.configFiles(it) }
                    ?.firstOrNull { it.overriddenBy?.metadata?.id == configId }
                    ?: throw ResourceNotFoundException("${kind.label} $configId does not override a file")
            return Override(copy, file, namespaceId, render(file.entity, file), render(copy, file))
        }
    }

    /** A database [copy] and the [file] it masks, both rendered in the file format. */
    private class Override<T>(
        val copy: T,
        val file: ConfigFile<T>,
        val namespaceId: UUID,
        val fileSide: RenderedConfig,
        val copySide: RenderedConfig,
    ) {
        val modified: Boolean get() = fileSide.files != copySide.files

        /**
         * Hash of the bytes of the files AgentOS read for this config, so that a write is refused when one of
         * them changed (or vanished) since the user looked at the differences.
         */
        fun fileHash(): String {
            val digest = MessageDigest.getInstance("SHA-256")
            fileSide.files.keys.sorted().forEach { path ->
                digest.update(path.toByteArray())
                digest.update(0)
                val bytes = runCatching { Files.readAllBytes(file.root.resolve(path)) }.getOrNull()
                digest.update(bytes ?: MISSING)
                digest.update(0)
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }

    /** A config in its file format: content by path relative to the namespace's `configPath`. */
    private data class RenderedConfig(
        val files: Map<String, String>,
        val notes: List<String> = emptyList(),
    )

    companion object : KLogging() {
        private const val SKILLS_FOLDER = "skills"
        private val MISSING = "\u0000missing\u0000".toByteArray()

        /** What a new config file gets on a POSIX file system: `rw-r--r--`, like a file a user creates. */
        private val NEW_FILE_PERMISSIONS = PosixFilePermissions.fromString("rw-r--r--")

        /**
         * Writes [content] to a temporary file of the same folder, then moves it over [target]: never a
         * half-written file. The temporary file is created private, so it takes the permissions of the file
         * it replaces before the move, or those of a file a user creates.
         */
        private fun writeAtomically(
            target: Path,
            content: String,
        ) {
            Files.createDirectories(target.parent)
            val permissions = runCatching { Files.getPosixFilePermissions(target) }.getOrNull() ?: NEW_FILE_PERMISSIONS
            val temporary = Files.createTempFile(target.parent, ".${target.name}.", ".tmp")
            try {
                Files.writeString(temporary, content)
                // A file system without POSIX permissions has nothing to keep.
                runCatching { Files.setPosixFilePermissions(temporary, permissions) }
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } finally {
                Files.deleteIfExists(temporary)
            }
        }

        /** The cause of a failed write, in words for the user, from the exception type rather than its raw message. */
        private fun writeFailure(
            e: IOException,
            folder: String,
        ): String =
            when {
                e is AccessDeniedException || (e is FileSystemException && e.reason?.contains("read-only", ignoreCase = true) == true) ->
                    "AgentOS is not allowed to write in $folder of this namespace's config folder."
                e is NoSuchFileException -> "The folder $folder no longer exists in this namespace's config folder."
                else -> "The file could not be written (${e.javaClass.simpleName})."
            }

        private fun attachedMessage(
            prompts: Int,
            schedulers: Int,
        ): String? =
            listOfNotNull(
                prompts.takeIf { it > 0 }?.let { "$it prompt${if (it > 1) "s" else ""}" },
                schedulers.takeIf { it > 0 }?.let { "$it scheduled prompt${if (it > 1) "s" else ""}" },
            ).takeIf { it.isNotEmpty() }
                ?.joinToString(" and ")
                ?.let { "$it ${if (prompts + schedulers > 1) "are" else "is"}" }
    }
}

private val FilesystemEntityKind.label: String get() = name.lowercase().replaceFirstChar { it.uppercase() }

/** A file to hand to the user: what [ConfigFileService.writeToFile] would write. */
class ConfigFileDownload(
    val fileName: String,
    val content: ByteArray,
)
