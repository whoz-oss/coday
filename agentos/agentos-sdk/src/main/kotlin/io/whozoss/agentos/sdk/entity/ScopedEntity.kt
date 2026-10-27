package io.whozoss.agentos.sdk.entity

import java.util.UUID

/**
 * Marker interface for overlay entities that carry the four-scope ownership pair
 * `(namespaceId, userId)`.
 *
 * The pair encodes four distinct overlay scopes:
 * - **Platform** `(null, null)` — visible to the whole environment; only Super Admins may write.
 * - **Namespace-shared** `(ns, null)` — shared by all members of namespace `ns`.
 * - **User-global** `(null, user)` — personal to `user`, applied across all namespaces.
 * - **User × namespace** `(ns, user)` — personal to `user` and scoped to namespace `ns`.
 *
 * Several entities already carry both fields but none declares them as a common type, so every
 * service re-derives the same scope table by hand. Declaring the pair here is what lets a single
 * policy (see `ScopedAccessPolicy`) reason about any overlay entity without knowing its concrete
 * type.
 */
interface ScopedEntity : Entity {
    val namespaceId: UUID?
    val userId: UUID?
}
