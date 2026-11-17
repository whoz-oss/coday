package io.whozoss.agentos.configFile

import io.swagger.v3.oas.annotations.media.Schema
import io.whozoss.agentos.plugin.filesystem.FilesystemEntityKind
import java.util.UUID

/** Where AgentOS takes a config defined by a file of the namespace's `configPath`. */
enum class ConfigFileState {
    /** From the file: nothing in the database uses its name. */
    FILE,

    /** From a database copy of the same name: the file is ignored. */
    OVERRIDDEN,
}

@Schema(
    name = "ConfigFile",
    description =
        "A config file of a namespace's configPath. A config of the namespace that appears in no entry " +
            "lives only in the database.",
)
data class ConfigFileDto(
    val kind: FilesystemEntityKind,
    @field:Schema(description = "Name of the config the file defines.")
    val name: String,
    @field:Schema(
        description = "Path of the file relative to the namespace's configPath, with '/' separators.",
        example = "agents/coday.yaml",
    )
    val path: String,
    val state: ConfigFileState,
    @field:Schema(
        description =
            "Id of the config AgentOS uses under this name, as the lists return it: the file's own id when " +
                "the state is FILE, the database copy's when it is OVERRIDDEN.",
    )
    val configId: UUID,
    @field:Schema(
        description =
            "OVERRIDDEN only: true when the database copy differs from the file, compared field by field " +
                "in the file format (formatting and comments of the file are not compared).",
        nullable = true,
    )
    val modified: Boolean? = null,
)

@Schema(
    name = "ConfigFileDiff",
    description =
        "A database copy and the file it masks, both rendered in the file format, so that only the " +
            "differences AgentOS acts on show.",
)
data class ConfigFileDiffDto(
    val kind: FilesystemEntityKind,
    val name: String,
    @field:Schema(description = "Id of the database copy.")
    val configId: UUID,
    @field:Schema(description = "Path of the masked file relative to the namespace's configPath.")
    val path: String,
    val modified: Boolean,
    @field:Schema(description = "One entry per file: the file itself, plus the resources of a skill.")
    val files: List<ConfigFileDiffEntryDto>,
    @field:Schema(description = "What of the database copy its file cannot hold, in words for the user.")
    val notes: List<String> = emptyList(),
)

@Schema(name = "ConfigFileDiffEntry")
data class ConfigFileDiffEntryDto(
    @field:Schema(description = "Path relative to the namespace's configPath.")
    val path: String,
    @field:Schema(description = "The file as AgentOS reads it, in the file format. Null when there is no such file.", nullable = true)
    val fileContent: String?,
    @field:Schema(description = "The database copy in the file format. Null when the copy has no such file.", nullable = true)
    val configContent: String?,
)
