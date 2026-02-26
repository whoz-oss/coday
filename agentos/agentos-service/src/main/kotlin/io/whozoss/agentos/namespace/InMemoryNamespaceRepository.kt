package io.whozoss.agentos.namespace

import io.whozoss.agentos.sdk.entity.InMemoryEntityRepository
import org.springframework.stereotype.Repository

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
    InMemoryEntityRepository<NamespaceModel, Unit>(
        parentIdExtractor = { Unit },
        comparator = compareBy { it.name },
    ),
    NamespaceRepository
