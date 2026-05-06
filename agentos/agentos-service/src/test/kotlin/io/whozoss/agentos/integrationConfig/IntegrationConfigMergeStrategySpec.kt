package io.whozoss.agentos.integrationConfig

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.whozoss.agentos.sdk.entity.EntityMetadata
import java.util.UUID

/**
 * Unit tests for [IntegrationConfigMergeStrategy].
 *
 * Focuses on the JsonNode merge semantics — flat overrides, deep object merge, array
 * replacement, null handling — that the [io.whozoss.agentos.reconciliation.ConfigReconciliationService]
 * relies on. The 3-tier resolution itself is covered by
 * [io.whozoss.agentos.reconciliation.ConfigReconciliationServiceSpec].
 */
class IntegrationConfigMergeStrategySpec : StringSpec({

    val mapper = ObjectMapper().registerKotlinModule().findAndRegisterModules()
    val strategy = IntegrationConfigMergeStrategy()
    val nsId = UUID.randomUUID()
    val userId = UUID.randomUUID()

    fun config(
        parametersJson: String?,
        description: String? = null,
        namespaceId: UUID? = nsId,
        userIdValue: UUID? = null,
    ): IntegrationConfig =
        IntegrationConfig(
            metadata = EntityMetadata(),
            namespaceId = namespaceId,
            userId = userIdValue,
            name = "JIRA",
            integrationType = "JIRA",
            description = description,
            parameters = parametersJson?.let { mapper.readTree(it) },
        )

    "flat override — keys in override win, base fills missing keys" {
        val base = config("""{"a":1,"b":2}""")
        val override = config("""{"a":99,"c":3}""")
        val result = strategy.merge(base, override)
        result.parameters?.get("a")?.asInt() shouldBe 99
        result.parameters?.get("b")?.asInt() shouldBe 2
        result.parameters?.get("c")?.asInt() shouldBe 3
    }

    "deep object merge — nested keys are merged recursively" {
        val base = config("""{"auth":{"token":"base-token","scheme":"Bearer"}}""")
        val override = config("""{"auth":{"token":"override-token"}}""")
        val result = strategy.merge(base, override)
        result.parameters?.get("auth")?.get("token")?.asText() shouldBe "override-token"
        result.parameters?.get("auth")?.get("scheme")?.asText() shouldBe "Bearer"
    }

    "deep object merge — multiple nesting levels are preserved" {
        val base = config("""{"l1":{"l2":{"keep":"yes","change":"no"}}}""")
        val override = config("""{"l1":{"l2":{"change":"yes","add":"new"}}}""")
        val result = strategy.merge(base, override)
        val l2 = result.parameters?.get("l1")?.get("l2")
        l2?.get("keep")?.asText() shouldBe "yes"
        l2?.get("change")?.asText() shouldBe "yes"
        l2?.get("add")?.asText() shouldBe "new"
    }

    "array values are replaced wholesale, not concatenated" {
        val base = config("""{"projects":["A","B","C"]}""")
        val override = config("""{"projects":["X"]}""")
        val result = strategy.merge(base, override)
        result.parameters?.get("projects")?.size() shouldBe 1
        result.parameters?.get("projects")?.get(0)?.asText() shouldBe "X"
    }

    // Documented fallback: when either side has a non-ObjectNode root (ArrayNode, ValueNode,
    // BooleanNode, NumberNode, etc.), the merge cannot proceed key-by-key, so override wins
    // wholesale. These tests pin that behaviour explicitly so a future refactor of
    // mergeParameters cannot silently change it.

    "ArrayNode override on ObjectNode base — override replaces base wholesale" {
        val base = config("""{"a":1,"b":2}""")
        val override = config("""["x","y","z"]""")
        val result = strategy.merge(base, override)
        result.parameters?.isArray shouldBe true
        result.parameters?.size() shouldBe 3
        result.parameters?.get(0)?.asText() shouldBe "x"
    }

    "ObjectNode override on ArrayNode base — override replaces base wholesale" {
        val base = config("""[1,2,3]""")
        val override = config("""{"key":"value"}""")
        val result = strategy.merge(base, override)
        result.parameters?.isObject shouldBe true
        result.parameters?.get("key")?.asText() shouldBe "value"
    }

    "primitive override on ObjectNode base — override replaces base wholesale" {
        val base = config("""{"a":1}""")
        val override = config(""""plain string"""")
        val result = strategy.merge(base, override)
        result.parameters?.isTextual shouldBe true
        result.parameters?.asText() shouldBe "plain string"
    }

    "null override.parameters falls back to base.parameters" {
        val base = config("""{"a":1}""")
        val override = config(parametersJson = null)
        val result = strategy.merge(base, override)
        result.parameters?.get("a")?.asInt() shouldBe 1
    }

    "null base.parameters falls back to override.parameters" {
        val base = config(parametersJson = null)
        val override = config("""{"a":1}""")
        val result = strategy.merge(base, override)
        result.parameters?.get("a")?.asInt() shouldBe 1
    }

    "both parameters null yields null" {
        val base = config(parametersJson = null)
        val override = config(parametersJson = null)
        val result = strategy.merge(base, override)
        result.parameters.shouldBeNull()
    }

    "description: override wins when set" {
        val base = config("""{}""", description = "base description")
        val override = config("""{}""", description = "override description")
        strategy.merge(base, override).description shouldBe "override description"
    }

    "description: base preserved when override null" {
        val base = config("""{}""", description = "base description")
        val override = config("""{}""", description = null)
        strategy.merge(base, override).description shouldBe "base description"
    }

    "merged result inherits the base layer's identity (id / scope / name) regardless of override identity" {
        // The 3-tier resolution folds layers left-to-right starting from the namespace-shared
        // layer; the merged result is a derived view consumed at run time and never persisted.
        // Story 6.1 RFC Q7 fixes the semantic: the merged config keeps the identity of the
        // BASE (lower-precedence) layer so caching and provenance lookups stay anchored on a
        // stable id.
        //
        // Concretely: even when the override layer has different scope and id, the merge MUST
        // keep base's identity untouched. Override only contributes the override-able fields
        // (parameters, integrationType, description).
        val baseUserId = UUID.randomUUID()
        val base = config(
            """{}""",
            description = "base description",
            namespaceId = nsId,
            userIdValue = baseUserId,
        )
        val override = config(
            """{}""",
            description = "override description",
            namespaceId = UUID.randomUUID(),
            userIdValue = UUID.randomUUID(),
        ).copy(name = "DIFFERENT-NAME")

        val result = strategy.merge(base, override)

        // Identity preserved from base
        result.metadata.id shouldBe base.metadata.id
        result.namespaceId shouldBe nsId
        result.userId shouldBe baseUserId
        result.name shouldBe "JIRA" // base's name, not override's "DIFFERENT-NAME"

        // Override-able fields take from override
        result.description shouldBe "override description"
    }

    "merge does not mutate the input nodes" {
        val base = config("""{"shared":{"a":1}}""")
        val override = config("""{"shared":{"a":99}}""")
        strategy.merge(base, override)
        // base must remain untouched after the merge
        base.parameters?.get("shared")?.get("a")?.asInt() shouldBe 1
        override.parameters?.get("shared")?.get("a")?.asInt() shouldBe 99
    }

    // -------------------------------------------------------------------------
    // NullNode handling — explicit JSON null in override means "inherit base"
    // (P6 / IG-2 protection against silent credential blanking on round-trip)
    // -------------------------------------------------------------------------

    "explicit null in override does NOT wipe base value (inherit-base semantics)" {
        val base = config("""{"apiKey":"sk-real","baseUrl":"https://x"}""")
        val override = config("""{"apiKey":null}""")
        val result = strategy.merge(base, override)
        result.parameters?.get("apiKey")?.asText() shouldBe "sk-real"
        result.parameters?.get("baseUrl")?.asText() shouldBe "https://x"
    }

    "explicit null in nested override does NOT wipe nested base value" {
        val base = config("""{"auth":{"token":"sk-real","scheme":"Bearer"}}""")
        val override = config("""{"auth":{"token":null}}""")
        val result = strategy.merge(base, override)
        result.parameters?.get("auth")?.get("token")?.asText() shouldBe "sk-real"
        result.parameters?.get("auth")?.get("scheme")?.asText() shouldBe "Bearer"
    }

    "explicit null parameters root in override falls back to base parameters" {
        val base = config("""{"a":1}""")
        val override = config("""null""")
        val result = strategy.merge(base, override)
        result.parameters?.get("a")?.asInt() shouldBe 1
    }

    "empty string in override DOES override (only JSON null inherits)" {
        // Documented escape hatch: to clear a string value, send "" not null.
        val base = config("""{"hint":"old"}""")
        val override = config("""{"hint":""}""")
        val result = strategy.merge(base, override)
        result.parameters?.get("hint")?.asText() shouldBe ""
    }
})
