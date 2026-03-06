package io.whozoss.agentos.namespace

import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.agentos.entity.EntityRepository
import io.whozoss.agentos.entity.FilesystemEntityRepository
import java.nio.file.Path
import kotlin.io.path.exists

/**
 * File-system implementation of [NamespaceRepository].
 *
 * Storage layout: `<dataDir>/namespaces/all/<namespaceId>.json`
 *
 * Supplies an O(1) [findFileByIdFn] that constructs the path directly from
 * the fixed parent directory, avoiding the default O(n) tree scan.
 */
class FilesystemNamespaceRepository(
    dataDir: Path,
    objectMapper: ObjectMapper,
) : NamespaceRepository,
    EntityRepository<Namespace, String> by FilesystemEntityRepository(
        rootDir = dataDir.resolve("namespaces"),
        entityClass = Namespace::class.java,
        objectMapper = objectMapper,
        parentIdExtractor = { NamespaceRepository.NAMESPACE_PARENT_KEY },
        comparator = compareBy { it.name },
        findFileByIdFn = { id ->
            val file = dataDir.resolve("namespaces").resolve(NamespaceRepository.NAMESPACE_PARENT_KEY).resolve("$id.json")
            file.takeIf { it.exists() }
        },
    )
