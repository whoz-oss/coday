package io.whozoss.agentos.authSetting

import io.whozoss.agentos.reconciliation.MergeStrategy
import io.whozoss.agentos.sdk.authSetting.AuthSetting
import org.springframework.stereotype.Component

/**
 * [MergeStrategy] for [AuthSetting].
 *
 * Identity fields (`id`, `metadata`, `namespaceId`, `userId`, `name`) are always preserved
 * from `base` (the lower-precedence layer). Functional fields apply override-wins semantics:
 *
 * - `authType`: override wins (in practice all layers share the same authType due to the
 *   cross-layer consistency guard enforced at write time).
 * - `description`: override wins when non-null; otherwise `base.description` is preserved.
 * - `data`: deep merge of the map — for each key in `override.data`, if the value is
 *   non-blank it wins; otherwise the `base` value for that key is preserved. Keys present
 *   only in `base` are always preserved. Keys present only in `override` with a non-blank
 *   value are added to the result.
 */
@Component
class AuthSettingMergeStrategy : MergeStrategy<AuthSetting> {
    override fun merge(
        base: AuthSetting,
        override: AuthSetting,
    ): AuthSetting {
        val mergedData = mergeData(base.data, override.data)
        return base.copy(
            authType = override.authType,
            description = override.description ?: base.description,
            data = mergedData,
        )
    }

    /**
     * Deep-merge two data maps.
     *
     * For every key in [overrideMap]: if the value is non-blank it wins; otherwise the
     * [baseMap] value for that key is preserved. Keys only in [baseMap] are always kept.
     * Keys only in [overrideMap] with a non-blank value are added.
     */
    private fun mergeData(
        baseMap: Map<String, String>,
        overrideMap: Map<String, String>,
    ): Map<String, String> {
        val result = baseMap.toMutableMap()
        for ((key, overrideValue) in overrideMap) {
            if (overrideValue.isNotBlank()) {
                result[key] = overrideValue
            }
            // blank override → preserve base value (or absent if base also lacks the key)
        }
        return result
    }
}
