package io.whozoss.agentos.plugin.filesystem

import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.invariantSeparatorsPathString

/**
 * A config file of a namespace's `configPath`, as its repository read it.
 *
 * [entity] is the config the file defines, bound to the namespace (its id is the synthetic
 * [filesystemEntityId]). [overriddenBy] is the persisted config of the same scope and name, if any:
 * AgentOS then uses that copy and ignores the file.
 *
 * [root] (the namespace's `configPath`) and [file] are paths on the AgentOS host and never leave the
 * server; [relativePath] is what the API shows.
 */
data class ConfigFile<T>(
    val entity: T,
    val root: Path,
    val file: Path,
    val overriddenBy: T? = null,
) {
    /** [file] relative to [root], with `/` separators, e.g. `agents/coday.yaml`. */
    val relativePath: String get() = root.relativize(file).invariantSeparatorsPathString
}

/**
 * Lists the config files of a namespace, masked or not.
 *
 * The listings of the filesystem repositories drop a file whose name a persisted config of the same
 * scope already uses. This is the one place where such a file stays visible, with the copy that masks
 * it, so that the drift between the two can be shown. Repositories without a filesystem layer keep the
 * empty default.
 */
interface ConfigFileSource<T> {
    fun configFiles(namespaceId: UUID): List<ConfigFile<T>> = emptyList()

    /** Reads the config files of [namespaceId] again on the next call, after AgentOS wrote one of them. */
    fun reloadConfigFiles(namespaceId: UUID) {}
}
