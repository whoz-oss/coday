package io.whozoss.agentos.configFile

import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.agentos.agentConfig.AgentConfig
import io.whozoss.agentos.agentConfig.FilesystemAgentConfigRepository
import io.whozoss.agentos.integrationConfig.IntegrationConfig
import io.whozoss.agentos.plugin.filesystem.tokenizeConfigPath
import io.whozoss.agentos.prompt.FilesystemPromptRepository
import io.whozoss.agentos.prompt.Prompt
import io.whozoss.agentos.prompt.PromptParameter
import io.whozoss.agentos.sdk.api.agentConfig.ExecutionMode
import io.whozoss.agentos.skill.Skill
import java.nio.file.InvalidPathException
import java.nio.file.Path
import kotlin.io.path.invariantSeparatorsPathString

/*
 * What a config looks like in its file under a namespace's `configPath`: one model per kind, shared by
 * the YAML exports and by the comparison between a file and the database copy that masks it, so that
 * both always agree.
 *
 * Each model holds only the fields its filesystem repository reads, built explicitly with `buildMap`
 * so that null, blank and empty values are omitted without a mapper-level inclusion policy. Doing it
 * here keeps the filtering out of nested values that must survive verbatim: the `integrations` map of
 * an agent (a null value means all tools of the integration, an empty list an explicit opt-out) and the
 * empty `defaultValue` of a prompt parameter (a free-form optional parameter).
 */

/**
 * The file model of an agent, read by [FilesystemAgentConfigRepository].
 *
 * Scope fields (`id`, `namespaceId`, `enabled`, `externalMetadata`) are persistence artefacts with no
 * meaning in a file. The mode is written as `executionMode`, and only when it is not the default
 * `SIMPLE`: the legacy `advancedExecution` flag resolves to it.
 *
 * [fileDirectory] is the directory of the agent's file, when the model is rendered for a known file:
 * the repository resolves `docs` against it and stores absolute paths, so they are written back
 * relative to it. Without it (an export), `docs` are written as stored.
 */
internal fun agentFileModel(
    entity: AgentConfig,
    fileDirectory: Path? = null,
): Map<String, Any?> =
    buildMap {
        put("name", entity.name)
        entity.description?.takeIf { it.isNotBlank() }?.let { put("description", it) }
        entity.instructions?.takeIf { it.isNotBlank() }?.let { put("instructions", it) }
        entity.modelName?.takeIf { it.isNotBlank() }?.let { put("modelName", it) }
        entity.resolvedExecutionMode.takeIf { it != ExecutionMode.SIMPLE }?.let { put("executionMode", it.name) }
        entity.integrations?.takeIf { it.isNotEmpty() }?.let { put("integrations", it) }
        entity.subAgents?.takeIf { it.isNotEmpty() }?.let { put("subAgents", it) }
        entity.delegationTimeoutSeconds?.let { put("delegationTimeoutSeconds", it) }
        entity.docs?.takeIf { it.isNotEmpty() }?.let { docs ->
            put("docs", if (fileDirectory == null) docs else docs.map { relativeDoc(it, fileDirectory) })
        }
        entity.skillSelectors?.takeIf { it.isNotEmpty() }?.let { put("skillSelectors", it) }
    }

/** What of [entity] its file cannot hold, in words for the user. */
internal fun agentFileLosses(entity: AgentConfig): List<String> =
    listOfNotNull(
        entity.loopConfig?.let { "The agent file format has no loopConfig: the file cannot hold this agent's loop payload." },
    )

/**
 * Writes an absolute `docs` entry relative to [fileDirectory], keeping the trailing slash or slash-star
 * of a directory listing pattern (see [FilesystemAgentConfigRepository]). A relative entry, or one that
 * is not a path, is kept as is.
 */
private fun relativeDoc(
    entry: String,
    fileDirectory: Path,
): String {
    val suffix =
        when {
            entry.endsWith("/*") -> "/*"
            entry.endsWith("/") -> "/"
            else -> ""
        }
    val path =
        try {
            Path.of(entry.removeSuffix(suffix))
        } catch (_: InvalidPathException) {
            return entry
        }
    if (!path.isAbsolute) return entry
    val relative = fileDirectory.toAbsolutePath().normalize().relativize(path.normalize()).invariantSeparatorsPathString
    return relative.ifEmpty { "." } + suffix
}

/**
 * The file model of an integration, read by
 * [io.whozoss.agentos.integrationConfig.FilesystemIntegrationConfigRepository].
 *
 * `parameters` is a JSON tree the YAML mapper writes natively. When [configPath] is given (the model is
 * rendered for a namespace), every occurrence of it goes back to the `{{NAMESPACE_CONFIG_PATH}}` token
 * the repository substitutes at load time. `autoGrant` is only written when set: `false` is the default.
 */
internal fun integrationFileModel(
    entity: IntegrationConfig,
    configPath: String? = null,
): Map<String, Any?> =
    buildMap {
        put("name", entity.name)
        put("integrationType", entity.integrationType)
        entity.description?.takeIf { it.isNotBlank() }?.let { put("description", it) }
        entity.parameters?.let { put("parameters", if (configPath == null) it else tokenizeConfigPath(it, configPath)) }
        entity.authSettingName?.takeIf { it.isNotBlank() }?.let { put("authSettingName", it) }
        if (entity.autoGrant) put("autoGrant", true)
    }

/**
 * The file model of a prompt, read by [FilesystemPromptRepository]: `name`, `description`, `content`,
 * `parameters`. Scope metadata, `externalMetadata`, `title`, `sourceLanguage` and translations have no
 * place in it.
 *
 * **`agentConfigId` is left out even when set**: a file prompt cannot target an agent (see
 * [FilesystemPromptRepository]), and the raw id would mean nothing outside this database. The targeting
 * can be kept by starting the content with `@agentName`, which
 * [io.whozoss.agentos.caseFlow.CaseServiceImpl] resolves when the prompt is expanded.
 */
internal fun promptFileModel(entity: Prompt): Map<String, Any?> =
    buildMap {
        put("name", entity.name)
        entity.description?.takeIf { it.isNotBlank() }?.let { put("description", it) }
        put("content", entity.content)
        entity.parameters.takeIf { it.isNotEmpty() }?.let { params -> put("parameters", params.map { it.fileModel() }) }
    }

private fun PromptParameter.fileModel(): Map<String, Any?> =
    buildMap {
        put("name", name)
        description?.takeIf { it.isNotBlank() }?.let { put("description", it) }
        put("defaultValue", defaultValue)
    }

/** What of [entity] its file cannot hold, in words for the user. */
internal fun promptFileLosses(entity: Prompt): List<String> =
    listOfNotNull(
        entity.agentConfigId?.let {
            "A prompt file cannot target an agent: start its content with @agentName to keep the targeting."
        },
        entity.title?.takeIf { it.isNotBlank() }?.let { "The prompt file format has no title: the file cannot hold it." },
    )

/**
 * The `SKILL.md` of a skill, as [io.whozoss.agentos.skill.SkillFileParser] reads it: a YAML frontmatter
 * with `name` and `description`, a blank line, then the body verbatim. The parser drops that one blank
 * line, so the body survives a round trip unchanged.
 */
internal fun skillFileContent(
    entity: Skill,
    yamlMapper: ObjectMapper,
): String =
    "---\n" +
        yamlMapper.writeValueAsString(mapOf("name" to entity.name, "description" to entity.description)) +
        "---\n\n" +
        entity.body
