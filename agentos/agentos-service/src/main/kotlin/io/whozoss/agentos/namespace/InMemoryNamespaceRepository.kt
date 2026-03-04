package io.whozoss.agentos.namespace

import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.sdk.entity.InMemoryEntityRepository
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Repository
import java.util.UUID

/**
 * In-memory implementation of NamespaceRepository.
 *
 * Active only when `agentos.persistence.in-memory=true` (or the `in-memory` profile).
 * The default mode is file-system persistence via [io.whozoss.agentos.persistence.FilesystemNamespaceRepository].
 */
@Repository
@ConditionalOnProperty(prefix = "agentos.persistence", name = ["in-memory"], havingValue = "true")
class InMemoryNamespaceRepository :
    InMemoryEntityRepository<Namespace, Unit>(
        parentIdExtractor = { },
        comparator = compareBy { it.name },
    ),
    NamespaceRepository {
    init {
        save(
            Namespace(
                metadata = EntityMetadata(id = UUID.fromString("00000000-0000-0000-0000-000000000001")),
                name = "default_namespace",
                description = "Default namespace",
            ),
        )
    }
}
