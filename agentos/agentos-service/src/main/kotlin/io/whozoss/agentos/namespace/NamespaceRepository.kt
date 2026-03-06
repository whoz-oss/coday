package io.whozoss.agentos.namespace

import io.whozoss.agentos.entity.EntityRepository

/**
 * Repository for NamespaceModel persistence.
 *
 * Namespaces are root-level entities with no logical parent.
 * The parent key is the fixed string "all" so all namespaces share a single directory.
 */
interface NamespaceRepository : EntityRepository<Namespace, String>
