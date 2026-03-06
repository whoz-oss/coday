package io.whozoss.agentos.namespace

import io.whozoss.agentos.entity.EntityRepository

/**
 * Repository for NamespaceModel persistence.
 *
 * Namespaces are root-level entities grouped under a fixed parent key ([NAMESPACE_PARENT_KEY]).
 * All namespaces share a single directory named after that key.
 */
interface NamespaceRepository : EntityRepository<Namespace, String> {
    companion object {
        const val NAMESPACE_PARENT_KEY = "all"
    }
}
