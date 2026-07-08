package io.whozoss.agentos.authSetting

import io.whozoss.agentos.reconciliation.MergeStrategy
import io.whozoss.agentos.sdk.authSetting.AuthSetting
import io.whozoss.agentos.sdk.authSetting.authSettingFromDataMap
import io.whozoss.agentos.sdk.authSetting.toDataMap
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
 * - data: each subtype's typed properties are round-tripped through [toDataMap] so the
 *   deep map-merge logic can be applied uniformly across all auth types. The merged map is
 *   then reconstructed into the correct subtype via [authSettingFromDataMap]. For each key
 *   in `override.toDataMap()`, if the value is non-blank it wins; otherwise the `base`
 *   value for that key is preserved. Keys present only in `base` are always preserved.
 */
@Component
class AuthSettingMergeStrategy : MergeStrategy<AuthSetting> {
    override fun merge(
        base: AuthSetting,
        override: AuthSetting,
    ): AuthSetting {
        val mergedData = mergeData(base.toDataMap(), override.toDataMap())
        return authSettingFromDataMap(
            authType = override.authType,
            data = mergedData,
            metadata = base.metadata,
            namespaceId = base.namespaceId,
            userId = base.userId,
            name = base.name,
            description = override.description ?: base.description,
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
