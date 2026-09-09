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
 * - `parameters`: deep merge of [ObjectNode]s — keys present in `override.parameters`
 *   win; nested `ObjectNode` keys are merged recursively. **Arrays are replaced wholesale
 *   (no concat / union)**: an `override` of `{"hosts":["c"]}` over a base of
 *   `{"hosts":["a","b"]}` produces `{"hosts":["c"]}`. Same for primitives.
 * - **Explicit JSON `null` in `override` is treated as "inherit base"**, NOT as
 *   "wipe base". This avoids silent credential blanking when a client round-trips a
 *   masked payload that the JSON serializer marshals as `null`. To explicitly clear a
 *   key, the user should send the appropriate empty value for the consumer (e.g. `""`
 *   for a string), not JSON `null`.
 * - `description`: `override.description` wins when non-null; otherwise inherited from `base`.
 * - `authSettingName`: `override.authSettingName` wins when non-null; otherwise inherited from `base`.
 *   A higher-precedence layer can redirect authentication to a different [io.whozoss.agentos.authSetting.AuthSetting].
 * - `integrationType`: `override.integrationType` wins for symmetry. In normal usage all
 *   layers carry the same `integrationType` because the service create/update path
 *   rejects an override whose `integrationType` differs from a matching `name` in any
 *   lower layer (cf. IG-3 decision). So the override-wins rule never produces a
 *   plugin-switch surprise at runtime.
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
            authSettingName = override.authSettingName ?: base.authSettingName,
        )

    private fun mergeParameters(
        base: JsonNode?,
        override: JsonNode?,
    ): JsonNode? =
        when {
            override == null || override.isNull -> base
            base == null -> override
            base is ObjectNode && override is ObjectNode -> deepMergeObjectNode(base, override)
            else -> override
        }

    /**
     * Recursive deep-merge of two [ObjectNode] instances.
     *
     * Returns a NEW [ObjectNode] (does not mutate `base` or `override`) so the strategy is
     * safe to apply to entities that are shared across threads.
     *
     * Skip semantics: an explicit `NullNode` in `override` is treated as "inherit base"
     * (the key is left untouched in the result). This prevents silent credential blanking
     * when a client round-trips a masked DTO whose serializer emits `null` for masked
     * fields. To override a key with an empty value, the user must send the appropriate
     * empty type (e.g. `""` string), not JSON `null`.
     */
    private fun deepMergeObjectNode(
        base: ObjectNode,
        override: ObjectNode,
    ): ObjectNode {
        val result = base.deepCopy()
        override.fields().forEachRemaining { (key, overrideValue) ->
            if (overrideValue.isNull) return@forEachRemaining
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
