package io.whozoss.agentos.configFile

import io.swagger.v3.oas.annotations.media.Schema
import io.whozoss.agentos.plugin.filesystem.FilesystemEntityKind
import jakarta.validation.constraints.NotBlank
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
    @field:Schema(description = "Hash of the files as AgentOS read them, to send back with write-to-file.")
    val fileHash: String,
    @field:Schema(description = "Names of the other namespaces that read the same configPath: writing the file changes them too.")
    val alsoReadBy: List<String> = emptyList(),
    @field:Schema(
        description =
            "Why the copy cannot be written to its file now, in words for the user: the notes, a credential in " +
                "clear, and what deleting the copy would delete with it. Empty when write-to-file is possible.",
    )
    val cannotWrite: List<String> = emptyList(),
    @field:Schema(description = "Why the copy cannot be discarded now: what deleting it would delete with it. Empty when it can.")
    val cannotDiscard: List<String> = emptyList(),
)

@Schema(name = "ConfigFileWriteRequest")
data class ConfigFileWriteRequestDto(
    @field:NotBlank
    @field:Schema(description = "The fileHash of the diff the user looked at: the write is refused if the file changed since.")
    val expectedFileHash: String,
)

@Schema(name = "ConfigFileWriteResult")
data class ConfigFileWriteResultDto(
    @field:Schema(description = "Path of the file relative to the namespace's configPath.")
    val path: String,
    @field:Schema(description = "False when nothing had to be written: the copy already said the same as its file, or it was discarded.")
    val written: Boolean,
    @field:Schema(description = "True when the database copy is deleted, so that AgentOS reads the file again.")
    val copyDeleted: Boolean,
    @field:Schema(description = "When the copy could not be deleted: what happened, in words for the user.", nullable = true)
    val message: String? = null,
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
