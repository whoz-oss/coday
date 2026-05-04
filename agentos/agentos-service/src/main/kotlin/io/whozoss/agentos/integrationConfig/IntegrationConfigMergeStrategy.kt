package io.whozoss.agentos.integrationConfig

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import io.whozoss.agentos.reconciliation.MergeStrategy
import org.springframework.stereotype.Component

/**
 * [MergeStrategy] for [IntegrationConfig].
 *
 * The resulting [IntegrationConfig] keeps the identity of the lower-precedence layer
 * (`base`) — `id`, `metadata`, `namespaceId`, `userId`, `name` — because the merged
 * config is a derived view consumed at run time (story 6.4) and never persisted, and
 * keeping the base identity preserves provenance for caching and logging.
 *
 * Merge semantics, layer-by-layer (called once per fold step):
 * - `parameters`: deep merge — keys present in `override.parameters` win; nested
 *   `ObjectNode` keys are merged recursively; arrays / primitives in `override` replace
 *   their counterpart in `base`. Missing on both sides yields `null`.
 * - `description`: `override.description` wins when non-null; otherwise inherited from `base`.
 * - `integrationType`: `override.integrationType` wins. In normal usage all layers carry
 *   the same `integrationType` (a user-overlay is always for the same plugin), but the
 *   strategy keeps the override-wins rule for symmetry with the rest of the entity.
 *
 * Reference: AC5 / AC6 / AC10 last row.
 */
@Component
class IntegrationConfigMergeStrategy : MergeStrategy<IntegrationConfig> {
    override fun merge(
        base: IntegrationConfig,
        override: IntegrationConfig,
    ): IntegrationConfig =
        base.copy(
            integrationType = override.integrationType,
            description = override.description ?: base.description,
            parameters = mergeParameters(base.parameters, override.parameters),
        )

    private fun mergeParameters(
        base: JsonNode?,
        override: JsonNode?,
    ): JsonNode? =
        when {
            override == null -> base
            base == null -> override
            base is ObjectNode && override is ObjectNode -> deepMergeObjectNode(base, override)
            else -> override
        }

    /**
     * Recursive deep-merge of two [ObjectNode] instances.
     *
     * Returns a NEW [ObjectNode] (does not mutate `base` or `override`) so the strategy is
     * safe to apply to entities that are shared across threads.
     */
    private fun deepMergeObjectNode(
        base: ObjectNode,
        override: ObjectNode,
    ): ObjectNode {
        val result = base.deepCopy()
        override.fields().forEachRemaining { (key, overrideValue) ->
            val baseValue = result.get(key)
            val mergedValue =
                when {
                    baseValue is ObjectNode && overrideValue is ObjectNode ->
                        deepMergeObjectNode(baseValue, overrideValue)
                    else -> overrideValue
                }
            result.set<JsonNode>(key, mergedValue)
        }
        return result
    }
}
