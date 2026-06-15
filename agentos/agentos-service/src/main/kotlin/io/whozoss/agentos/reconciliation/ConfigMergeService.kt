package io.whozoss.agentos.reconciliation

import io.whozoss.agentos.exception.ConfigNotFoundException
import io.whozoss.agentos.sdk.entity.Entity
import java.util.UUID

/**
 * Lookup contract: fetches a single entity instance matching a (namespaceId, userId, name) triple.
 *
 * Implementations are entity-specific adapters around their repository's `findByTriple` method.
 * They are pure NULL-tolerant point lookups — no merge logic happens here.
 *
 * @param T the entity type being reconciled (e.g. [io.whozoss.agentos.integrationConfig.IntegrationConfig])
 */
interface ConfigLookup<T : Entity> {
    /**
     * Fetch the single entity matching `(namespaceId, userId, name)`. NULL parameters match
     * rows where the corresponding column is NULL (i.e. the property is absent on the Neo4j
     * node). Returns null when no row matches.
     */
    fun findByTriple(
        namespaceId: UUID?,
        userId: UUID?,
        name: String,
    ): T?
}

/**
 * Strategy contract: merges an `override` instance on top of a `base` instance, parameter by
 * parameter. The result must carry the identity of the layer it is being applied to (story 6.4
 * relies on [Entity.id] traceability when caching the resolved config per run).
 *
 * Implementations decide which fields are subject to the per-key override semantics
 * (typically the `parameters` JsonNode) and which are identity fields that the merge MUST NOT
 * change (`id`, `name`, `namespaceId`, `userId`).
 *
 * @param T the entity type being reconciled
 */
interface MergeStrategy<T : Entity> {
    /**
     * Returns a new instance combining `base` and `override` such that any parameter present
     * in `override` wins, and any parameter absent in `override` is inherited from `base`.
     *
     * The function is associative when applied left-to-right along the precedence chain
     * `namespace → user-global → user × namespace` — applying it twice along that chain yields
     * the final 3-tier merge with no special-case branches in the caller.
     */
    fun merge(
        base: T,
        override: T,
    ): T
}

/**
 * Generic 4-tier reconciliation engine for platform × namespace × user-overlay configuration entities.
 *
 * Resolves a single entity from up to four layers, applying [MergeStrategy.merge] left-to-right
 * along the precedence chain (lowest to highest):
 *
 * | Layer                | Precedence | Lookup triple                  |
 * |----------------------|-----------|--------------------------------|
 * | Platform             | lowest    | `(null,        null,    name)` |
 * | Namespace shared     | low       | `(namespaceId, null,    name)` |
 * | User-global          | high      | `(null,        userId,  name)` |
 * | User × namespace     | highest   | `(namespaceId, userId,  name)` |
 *
 * The platform layer is an environment-wide default, automatically visible across all namespaces
 * without manual setup. It is stored as a `(namespaceId=null, userId=null)` row and folded
 * first so every more-specific layer can override it.
 *
 * Each existing layer is folded onto the accumulator via [MergeStrategy.merge]; missing layers
 * are skipped. When all four layers are absent the call fails with [ConfigNotFoundException]
 * (FR13, NFR-REL-2).
 *
 * Open class with explicit constructor injection: per-entity beans are wired by
 * [io.whozoss.agentos.reconciliation.MergeConfiguration]. Spring cannot
 * auto-resolve `ConfigLookup<T>` / `MergeStrategy<T>` from a single
 * `@Service ConfigMergeService<T>` declaration because Kotlin's type erasure leaves
 * the runtime container with no way to choose between competing parameterised beans — RFC Q7.
 *
 * @param T the entity type being reconciled
 */
class ConfigMergeService<T : Entity>(
    private val lookup: ConfigLookup<T>,
    private val mergeStrategy: MergeStrategy<T>,
) {
    /**
     * Resolve the entity for the given `(namespaceId, userId, name)` triple by folding
     * the four precedence layers from lowest to highest.
     *
     * The platform layer `(null, null, name)` is always consulted first as the base default.
     * Namespace-shared, user-global, and user×namespace layers are then folded on top.
     *
     * @throws ConfigNotFoundException when none of the four layers contains a row.
     */
    fun resolve(
        namespaceId: UUID,
        userId: UUID,
        name: String,
    ): T {
        val platform = lookup.findByTriple(null, null, name)
        val nsShared = lookup.findByTriple(namespaceId, null, name)
        val userGlobal = lookup.findByTriple(null, userId, name)
        val userNamespace = lookup.findByTriple(namespaceId, userId, name)

        val afterNsShared = foldLayer(platform, nsShared)
        val afterUserGlobal = foldLayer(afterNsShared, userGlobal)
        val afterUserNamespace = foldLayer(afterUserGlobal, userNamespace)

        return afterUserNamespace
            ?: throw ConfigNotFoundException(namespaceId, userId, name)
    }

    private fun foldLayer(
        accumulator: T?,
        higher: T?,
    ): T? =
        when {
            higher == null -> accumulator
            accumulator == null -> higher
            else -> mergeStrategy.merge(accumulator, higher)
        }
}
