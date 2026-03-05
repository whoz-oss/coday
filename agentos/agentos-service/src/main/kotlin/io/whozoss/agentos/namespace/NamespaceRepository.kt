package io.whozoss.agentos.namespace

import io.whozoss.agentos.entity.EntityRepository

/**
 * Repository for NamespaceModel persistence.
 *
 * Namespaces are root-level entities with no parent, so the parent type is Unit.
 */
interface NamespaceRepository : EntityRepository<Namespace, Unit>
