package io.whozoss.agentos.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.agentos.namespace.Namespace
import io.whozoss.agentos.namespace.NamespaceRepository
import mu.KLogging
import java.nio.file.Path

/**
 * File-system implementation of [NamespaceRepository].
 *
 * Storage layout:
 * ```
 * <dataDir>/namespaces/root/<namespaceId>.json
 * ```
 *
 * Namespaces are root-level entities (no parent), so a fixed parent key "root"
 * is used as the directory name.
 *
 * This bean is registered by [PersistenceConfiguration] when
 * `agentos.persistence.enabled=true`.
 */
class FilesystemNamespaceRepository(
    dataDir: Path,
    objectMapper: ObjectMapper,
) : FilesystemEntityRepository<Namespace, Unit>(
        rootDir = dataDir.resolve("namespaces"),
        entityClass = Namespace::class.java,
        objectMapper = objectMapper,
        parentIdExtractor = { },
        comparator = compareBy { it.name },
    ),
    NamespaceRepository {
    /**
     * Override findFileById to use the fixed "root" parent directory directly,
     * avoiding a full tree scan.
     */
    override fun findFileById(id: java.util.UUID): Path? {
        val file = rootDir.resolve(Unit.toString()).resolve("$id.json")
        return if (file.toFile().exists()) file else null
    }

    companion object : KLogging()
}
