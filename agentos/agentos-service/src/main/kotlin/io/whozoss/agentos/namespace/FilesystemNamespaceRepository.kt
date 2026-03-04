package io.whozoss.agentos.namespace

import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.agentos.entity.FilesystemEntityRepository
import io.whozoss.agentos.sdk.entity.EntityRepository
import java.nio.file.Path
import kotlin.io.path.exists

/**
 * File-system implementation of [NamespaceRepository].
 *
 * Storage layout: `<dataDir>/namespaces/kotlin.Unit/<namespaceId>.json`
 *
 * Supplies an O(1) [findFileByIdFn] that constructs the path directly from
 * the fixed parent directory, avoiding the default O(n) tree scan.
 */
class FilesystemNamespaceRepository(
    dataDir: Path,
    objectMapper: ObjectMapper,
) : NamespaceRepository,
    EntityRepository<Namespace, Unit> by FilesystemEntityRepository(
        rootDir = dataDir.resolve("namespaces"),
        entityClass = Namespace::class.java,
        objectMapper = objectMapper,
        parentIdExtractor = { },
        comparator = compareBy { it.name },
        findFileByIdFn = { id ->
            val file = dataDir.resolve("namespaces").resolve(Unit.toString()).resolve("$id.json")
            file.takeIf { it.exists() }
        },
    )
