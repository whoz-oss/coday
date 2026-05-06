package io.whozoss.agentos.persistence

import java.util.UUID

/**
 * Shared encoding for the `tripleKey` discriminator used by user-level overlay entities
 * (`IntegrationConfig`, `AiProvider`, `AiModel`).
 *
 * The discriminator concatenates the 3 components of the business unique-key
 * `(namespaceId, userId, name)` into a single non-null String. It backs a single-property
 * UNIQUE CONSTRAINT in Neo4j, which:
 * - enforces uniqueness across triple modes — composite constraints on `(namespaceId,
 *   userId, name)` are silently exempted by Neo4j 5.x as soon as one component is NULL,
 *   making them unable to enforce the actual business rule;
 * - provisions an index that supports the reconciliation lookup (`findActiveByTripleKey`)
 *   with an exact seek instead of a label scan;
 * - lets `delete()` rewrite the key to a per-id [tombstone] form so the unique slot is
 *   freed and the user can immediately recreate `(ns, user, name)` after a soft-delete;
 * - turns concurrent creates with the same triple into a database-level 409 instead of
 *   silently producing duplicates.
 *
 * Cf. RFC `rfc-user-level-overlays.md` §D11 (chunk 1a).
 *
 * NOT thread-unsafe — pure functions, no state.
 */
object TripleKeyEncoding {
    /**
     * Sentinel substituted for a NULL id component when computing the active key. `_` is
     * outside the UUID alphabet (hex + dashes), so it cannot collide with a real UUID
     * string and the resulting key remains injective across the three triple modes.
     */
    const val NULL_ID_SENTINEL: String = "_"

    /**
     * Separator placed between the components of the active key. Distinct from any
     * character produced by [UUID.toString] (UUIDs only emit hex digits and `-`), so a
     * parser would not be ambiguous about component boundaries (we never parse, but the
     * property holds).
     */
    const val SEPARATOR: String = ":"

    /**
     * Prefix for tombstone keys. Distinct from any active key (no active key starts with
     * a non-UUID/non-`_` segment), so soft-deleted rows never compete for the unique slot
     * with their would-be replacements. Each tombstone embeds the row id, which is itself
     * unique, so multiple tombstones never collide either.
     */
    const val TOMBSTONE_PREFIX: String = "tombstone:"

    /**
     * Build the active discriminator from the triple. `name` is taken verbatim — caller
     * is responsible for any normalization (trim, case-fold, etc.) consistent with their
     * matching contract.
     */
    fun activeKey(
        namespaceId: UUID?,
        userId: UUID?,
        name: String,
    ): String =
        (namespaceId?.toString() ?: NULL_ID_SENTINEL) +
            SEPARATOR +
            (userId?.toString() ?: NULL_ID_SENTINEL) +
            SEPARATOR +
            name

    /**
     * Build the tombstone discriminator for a soft-deleted row identified by [id]. The
     * row id is unique, so this key is unique by construction across all soft-deleted
     * rows of the same entity type.
     */
    fun tombstoneKey(id: String): String = TOMBSTONE_PREFIX + id
}
