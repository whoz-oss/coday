package io.whozoss.agentos.authSetting

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.whozoss.agentos.sdk.authSetting.AuthSetting
import io.whozoss.agentos.sdk.authSetting.AuthType
import io.whozoss.agentos.sdk.authSetting.authSettingFromDataMap
import io.whozoss.agentos.sdk.authSetting.toDataMap
import io.whozoss.agentos.sdk.entity.EntityMetadata
import java.util.UUID

// Convenience accessor for tests that reason about the flat data map.
private val AuthSetting.data: Map<String, String> get() = toDataMap()

/**
 * Unit tests for [AuthSettingMergeStrategy].
 *
 * Covers identity preservation, field-by-field override semantics, and the data-map deep-merge
 * behaviour that distinguishes AuthSetting from the simpler AiProvider merge.
 */
class AuthSettingMergeStrategySpec : StringSpec({

    val strategy = AuthSettingMergeStrategy()

    val nsId = UUID.randomUUID()
    val userId = UUID.randomUUID()

    fun setting(
        ns: UUID? = nsId,
        uid: UUID? = null,
        name: String = "github",
        description: String? = "base desc",
        authType: AuthType = AuthType.OAUTH_DISCOVERABLE,
        data: Map<String, String> = mapOf("clientId" to "base-id", "clientSecret" to "base-secret"),
    ) = authSettingFromDataMap(
        authType = authType,
        data = data,
        metadata = EntityMetadata(),
        namespaceId = ns,
        userId = uid,
        name = name,
        description = description,
    )

    // -------------------------------------------------------------------------
    // Identity preservation
    // -------------------------------------------------------------------------

    "merge preserves base identity fields (id, metadata, namespaceId, userId, name)" {
        val base = setting(ns = nsId, uid = null, name = "github")
        val override = setting(ns = null, uid = userId, name = "override-name")

        val merged = strategy.merge(base, override)

        merged.metadata.id shouldBe base.metadata.id
        merged.namespaceId shouldBe base.namespaceId
        merged.userId shouldBe base.userId
        merged.name shouldBe base.name
    }

    // -------------------------------------------------------------------------
    // authType override
    // -------------------------------------------------------------------------

    "merge override wins on authType" {
        val base = setting(authType = AuthType.OAUTH_DISCOVERABLE)
        val override = setting(authType = AuthType.API_KEY)

        strategy.merge(base, override).authType shouldBe AuthType.API_KEY
    }

    // -------------------------------------------------------------------------
    // description override
    // -------------------------------------------------------------------------

    "merge override wins on description when non-null" {
        val base = setting(description = "base desc")
        val override = setting(description = "override desc")

        strategy.merge(base, override).description shouldBe "override desc"
    }

    "merge preserves base description when override description is null" {
        val base = setting(description = "base desc")
        val override = setting(description = null)

        strategy.merge(base, override).description shouldBe "base desc"
    }

    // -------------------------------------------------------------------------
    // Data map deep merge
    // -------------------------------------------------------------------------

    "data map: override key with non-blank value wins" {
        val base = setting(data = mapOf("clientId" to "base-id", "clientSecret" to "base-secret"))
        val override = setting(data = mapOf("clientId" to "override-id"))

        val merged = strategy.merge(base, override)
        merged.data["clientId"] shouldBe "override-id"
        merged.data["clientSecret"] shouldBe "base-secret"
    }

    "data map: override key with blank value preserves base value" {
        val base = setting(data = mapOf("clientId" to "base-id", "clientSecret" to "base-secret"))
        val override = setting(data = mapOf("clientId" to ""))

        val merged = strategy.merge(base, override)
        merged.data["clientId"] shouldBe "base-id"
        merged.data["clientSecret"] shouldBe "base-secret"
    }

    "data map: keys only in base are preserved" {
        val base = setting(data = mapOf("clientId" to "base-id", "discoveryUrl" to "https://base.discovery"))
        val override = setting(data = mapOf("clientId" to "override-id"))

        val merged = strategy.merge(base, override)
        merged.data["discoveryUrl"] shouldBe "https://base.discovery"
    }

    "data map: keys only in override with non-blank value are added" {
        val base = setting(data = mapOf("clientId" to "base-id"))
        val override = setting(data = mapOf("clientId" to "override-id", "scopes" to "read write"))

        val merged = strategy.merge(base, override)
        merged.data["scopes"] shouldBe "read write"
    }

    "data map: keys only in override with blank value are not added" {
        val base = setting(data = mapOf("clientId" to "base-id"))
        val override = setting(data = mapOf("clientId" to "override-id", "scopes" to ""))

        val merged = strategy.merge(base, override)
        merged.data.containsKey("scopes") shouldBe false
    }

    "data map: empty override map leaves base data unchanged" {
        val base = setting(data = mapOf("clientId" to "base-id", "clientSecret" to "base-secret"))
        val override = setting(data = emptyMap())

        val merged = strategy.merge(base, override)
        merged.data shouldBe mapOf("clientId" to "base-id", "clientSecret" to "base-secret")
    }

    "data map: empty base map with non-blank override produces override data" {
        val base = setting(data = emptyMap())
        val override = setting(data = mapOf("clientId" to "new-id", "clientSecret" to "new-secret"))

        val merged = strategy.merge(base, override)
        merged.data shouldBe mapOf("clientId" to "new-id", "clientSecret" to "new-secret")
    }

    // -------------------------------------------------------------------------
    // Multi-layer fold precedence
    // -------------------------------------------------------------------------

    "three-layer fold produces correct precedence" {
        val ns = setting(
            ns = nsId, uid = null,
            data = mapOf("clientId" to "ns-id", "discoveryUrl" to "https://ns.discovery"),
            description = "ns desc",
        )
        val global = setting(
            ns = null, uid = userId,
            data = mapOf("clientId" to "global-id"),
            description = null,
        )
        val userNs = setting(
            ns = nsId, uid = userId,
            data = mapOf("clientId" to ""),  // blank — should NOT override
            description = "user-ns desc",
        )

        val afterGlobal = strategy.merge(ns, global)
        val final = strategy.merge(afterGlobal, userNs)

        // global won clientId over ns; userNs blank does NOT override global
        final.data["clientId"] shouldBe "global-id"
        // discoveryUrl only in ns — preserved through all folds
        final.data["discoveryUrl"] shouldBe "https://ns.discovery"
        // description: userNs wins (non-null)
        final.description shouldBe "user-ns desc"
    }

    "merge result identity is from base (lower layer)" {
        val base = setting(ns = nsId, uid = null)
        val override = setting(ns = null, uid = userId)

        val merged = strategy.merge(base, override)
        merged.metadata.id shouldBe base.metadata.id
        merged.metadata.id shouldNotBe override.metadata.id
    }

    // -------------------------------------------------------------------------
    // OAUTH_MCP_DISCOVERABLE merge
    // -------------------------------------------------------------------------

    "OAUTH_MCP_DISCOVERABLE: merge preserves resourceUrl from base when override has none" {
        val base = setting(
            authType = AuthType.OAUTH_MCP_DISCOVERABLE,
            data = mapOf("resourceUrl" to "https://mcp.example.com", "clientId" to "base-client"),
        )
        val override = setting(
            authType = AuthType.OAUTH_MCP_DISCOVERABLE,
            data = mapOf("scopes" to "read write"),
        )

        val merged = strategy.merge(base, override)
        merged.data["resourceUrl"] shouldBe "https://mcp.example.com"
        merged.data["clientId"] shouldBe "base-client"
        merged.data["scopes"] shouldBe "read write"
    }

    "OAUTH_MCP_DISCOVERABLE: override clientId wins over base" {
        val base = setting(
            authType = AuthType.OAUTH_MCP_DISCOVERABLE,
            data = mapOf("resourceUrl" to "https://mcp.example.com", "clientId" to "base-client"),
        )
        val override = setting(
            authType = AuthType.OAUTH_MCP_DISCOVERABLE,
            data = mapOf("clientId" to "override-client"),
        )

        val merged = strategy.merge(base, override)
        merged.data["clientId"] shouldBe "override-client"
        merged.data["resourceUrl"] shouldBe "https://mcp.example.com"
    }
})
