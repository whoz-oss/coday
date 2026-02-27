package io.whozoss.agentos.namespace

import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.sdk.entity.InMemoryEntityRepository
import org.springframework.stereotype.Repository
import java.util.UUID

/**
 * In-memory implementation of NamespaceRepository.
 *
 * Uses the generic InMemoryEntityRepository with:
 * - Entity ID: namespace.metadata.id (automatic via Entity interface)
 * - Parent ID: Unit (namespaces are root-level, no parent)
 * - Ordering: by name (alphabetical)
 */
@Repository
class InMemoryNamespaceRepository :
    InMemoryEntityRepository<Namespace, Unit>(
        parentIdExtractor = { Unit },
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
