package io.whozoss.agentos.plugin.filesystem

import java.util.UUID

/** Kind of entity loaded from a namespace's `configPath`; [prefix] is part of the generated id. */
enum class FilesystemEntityKind(
    val prefix: String,
) {
    AGENT("agent"),
    PROMPT("prompt"),
    INTEGRATION("integration"),
    SKILL("skill"),
}

/**
 * Stable id of an entity loaded from a namespace's `configPath`.
 *
 * The id MUST include [namespaceId]: the same [name] in two namespaces must give two different
 * ids, otherwise `findByIds` mixes them up.
 *
 * It is computed when the entity is bound to its namespace, never in the parser: the YAML cache is
 * keyed by directory and several namespaces can share the same `configPath`, so the parser cannot
 * know which namespace the parsed entity belongs to.
 */
fun filesystemEntityId(
    kind: FilesystemEntityKind,
    namespaceId: UUID,
    name: String,
): UUID = UUID.nameUUIDFromBytes("filesystem-${kind.prefix}:$namespaceId:$name".toByteArray(Charsets.UTF_8))
