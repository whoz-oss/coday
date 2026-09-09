package io.whozoss.agentos.reconciliation

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.whozoss.agentos.exception.ConfigNotFoundException
import io.whozoss.agentos.integrationConfig.IntegrationConfig
import io.whozoss.agentos.integrationConfig.IntegrationConfigMergeStrategy
import io.whozoss.agentos.sdk.entity.EntityMetadata
import java.util.UUID

/**
 * Unit tests for [ConfigMergeService] covering the eight precedence-layer
 * combinations enumerated in story 6.1 / AC10, plus the granular per-parameter merge
 * assertion from AC10's last row.
 *
 * Mock the [ConfigLookup] (one stub per layer) so the suite exercises the resolution logic
 * in isolation from any Neo4j wiring. The real [IntegrationConfigMergeStrategy] is used as
 * the [MergeStrategy] under test, which doubles as a contract test for the merge semantics
 * relied upon by the precedence cases.
 */
class ConfigMergeServiceSpec : StringSpec({

    val mapper = ObjectMapper().registerKotlinModule().findAndRegisterModules()
    val mergeStrategy = IntegrationConfigMergeStrategy()

    val NAMESPACE_ID = UUID.randomUUID()
    val USER_ID = UUID.randomUUID()
    val NAME = "JIRA"

    fun config(
        namespaceId: UUID?,
        userId: UUID?,
        parametersJson: String,
        description: String? = null,
    ): IntegrationConfig =
        IntegrationConfig(
            metadata = EntityMetadata(),
            namespaceId = namespaceId,
            userId = userId,
            name = NAME,
            integrationType = "JIRA",
            description = description,
            parameters = mapper.readTree(parametersJson),
        )

    fun newService(
        platform: IntegrationConfig? = null,
        namespaceShared: IntegrationConfig?,
        userGlobal: IntegrationConfig?,
        userNamespace: IntegrationConfig?,
    ): ConfigMergeService<IntegrationConfig> {
        val lookup = mockk<ConfigLookup<IntegrationConfig>>()
        every { lookup.findByTriple(null, null, NAME) } returns platform
        every { lookup.findByTriple(NAMESPACE_ID, null, NAME) } returns namespaceShared
        every { lookup.findByTriple(null, USER_ID, NAME) } returns userGlobal
        every { lookup.findByTriple(NAMESPACE_ID, USER_ID, NAME) } returns userNamespace
        return ConfigMergeService(lookup, mergeStrategy)
    }

    // -------------------------------------------------------------------------
    // AC10 — 8 combinations of (namespace shared, user-global, user × namespace)
    // Platform layer absent in all legacy cases (preserves existing semantics).
    // -------------------------------------------------------------------------

    "Cas 1 — namespace shared only → returns namespace shared (FR12)" {
        val nsShared = config(NAMESPACE_ID, null, """{"apiUrl":"https://ns.example.com"}""")
        val service = newService(namespaceShared = nsShared, userGlobal = null, userNamespace = null)

        val resolved = service.resolve(NAMESPACE_ID, USER_ID, NAME)
        resolved.parameters?.get("apiUrl")?.asText() shouldBe "https://ns.example.com"
        resolved.metadata.id shouldBe nsShared.metadata.id
    }

    "Cas 2 — user-global only → returns user-global standalone (FR12bis)" {
        val userGlobal = config(null, USER_ID, """{"apiKey":"user-global-key"}""")
        val service = newService(namespaceShared = null, userGlobal = userGlobal, userNamespace = null)

        val resolved = service.resolve(NAMESPACE_ID, USER_ID, NAME)
        resolved.parameters?.get("apiKey")?.asText() shouldBe "user-global-key"
        resolved.metadata.id shouldBe userGlobal.metadata.id
    }

    "Cas 3 — user × namespace only → returns user × namespace standalone (FR12ter)" {
        val userNamespace = config(NAMESPACE_ID, USER_ID, """{"apiKey":"user-ns-key"}""")
        val service = newService(namespaceShared = null, userGlobal = null, userNamespace = userNamespace)

        val resolved = service.resolve(NAMESPACE_ID, USER_ID, NAME)
        resolved.parameters?.get("apiKey")?.asText() shouldBe "user-ns-key"
        resolved.metadata.id shouldBe userNamespace.metadata.id
    }

    "Cas 4 — namespace + user-global → user-global wins per-key (FR9, FR10)" {
        val nsShared = config(NAMESPACE_ID, null, """{"apiUrl":"https://ns","apiKey":"ns-key"}""")
        val userGlobal = config(null, USER_ID, """{"apiKey":"user-key"}""")
        val service = newService(namespaceShared = nsShared, userGlobal = userGlobal, userNamespace = null)

        val resolved = service.resolve(NAMESPACE_ID, USER_ID, NAME)
        resolved.parameters?.get("apiUrl")?.asText() shouldBe "https://ns"
        resolved.parameters?.get("apiKey")?.asText() shouldBe "user-key"
    }

    "Cas 5 — namespace + user × namespace → user × namespace wins per-key" {
        val nsShared = config(NAMESPACE_ID, null, """{"apiUrl":"https://ns","apiKey":"ns-key"}""")
        val userNamespace = config(NAMESPACE_ID, USER_ID, """{"apiKey":"user-ns-key"}""")
        val service = newService(namespaceShared = nsShared, userGlobal = null, userNamespace = userNamespace)

        val resolved = service.resolve(NAMESPACE_ID, USER_ID, NAME)
        resolved.parameters?.get("apiUrl")?.asText() shouldBe "https://ns"
        resolved.parameters?.get("apiKey")?.asText() shouldBe "user-ns-key"
    }

    "Cas 6 — user-global + user × namespace → user × namespace wins per-key" {
        val userGlobal = config(null, USER_ID, """{"apiUrl":"https://global","apiKey":"global-key"}""")
        val userNamespace = config(NAMESPACE_ID, USER_ID, """{"apiKey":"user-ns-key"}""")
        val service = newService(namespaceShared = null, userGlobal = userGlobal, userNamespace = userNamespace)

        val resolved = service.resolve(NAMESPACE_ID, USER_ID, NAME)
        resolved.parameters?.get("apiUrl")?.asText() shouldBe "https://global"
        resolved.parameters?.get("apiKey")?.asText() shouldBe "user-ns-key"
    }

    "Cas 7 — all three layers → user × namespace > user-global > namespace per-key" {
        val nsShared = config(
            NAMESPACE_ID,
            null,
            """{"apiUrl":"https://ns","apiKey":"ns-key","tenant":"ns-tenant"}""",
        )
        val userGlobal = config(null, USER_ID, """{"apiKey":"global-key","tenant":"global-tenant"}""")
        val userNamespace = config(NAMESPACE_ID, USER_ID, """{"tenant":"user-ns-tenant"}""")
        val service = newService(namespaceShared = nsShared, userGlobal = userGlobal, userNamespace = userNamespace)

        val resolved = service.resolve(NAMESPACE_ID, USER_ID, NAME)
        resolved.parameters?.get("apiUrl")?.asText() shouldBe "https://ns"
        resolved.parameters?.get("apiKey")?.asText() shouldBe "global-key"
        resolved.parameters?.get("tenant")?.asText() shouldBe "user-ns-tenant"
    }

    "Cas 8 — no layer present → throws ConfigNotFoundException (FR13)" {
        val service = newService(namespaceShared = null, userGlobal = null, userNamespace = null)

        val ex = shouldThrow<ConfigNotFoundException> {
            service.resolve(NAMESPACE_ID, USER_ID, NAME)
        }
        ex.namespaceId shouldBe NAMESPACE_ID
        ex.userId shouldBe USER_ID
        ex.name shouldBe NAME
    }

    // -------------------------------------------------------------------------
    // Platform layer — 4-tier precedence cases
    // -------------------------------------------------------------------------

    "Cas P1 — platform only → returns platform as base" {
        val platform = config(null, null, """{"apiUrl":"https://platform"}""")
        val service = newService(platform = platform, namespaceShared = null, userGlobal = null, userNamespace = null)

        val resolved = service.resolve(NAMESPACE_ID, USER_ID, NAME)
        resolved.parameters?.get("apiUrl")?.asText() shouldBe "https://platform"
        resolved.metadata.id shouldBe platform.metadata.id
    }

    "Cas P2 — platform + namespace → namespace wins per-key, platform fills the rest" {
        val platform = config(null, null, """{"apiUrl":"https://platform","apiKey":"platform-key"}""")
        val nsShared = config(NAMESPACE_ID, null, """{"apiKey":"ns-key"}""")
        val service = newService(platform = platform, namespaceShared = nsShared, userGlobal = null, userNamespace = null)

        val resolved = service.resolve(NAMESPACE_ID, USER_ID, NAME)
        resolved.parameters?.get("apiUrl")?.asText() shouldBe "https://platform"
        resolved.parameters?.get("apiKey")?.asText() shouldBe "ns-key"
    }

    "Cas P3 — all four layers → user × namespace > user-global > namespace > platform per-key" {
        val platform = config(null, null, """{"apiUrl":"https://platform","apiKey":"platform-key","tenant":"platform-tenant","region":"eu"}""")
        val nsShared = config(NAMESPACE_ID, null, """{"apiKey":"ns-key","tenant":"ns-tenant"}""")
        val userGlobal = config(null, USER_ID, """{"tenant":"global-tenant"}""")
        val userNamespace = config(NAMESPACE_ID, USER_ID, """{"tenant":"user-ns-tenant"}""")
        val service = newService(platform = platform, namespaceShared = nsShared, userGlobal = userGlobal, userNamespace = userNamespace)

        val resolved = service.resolve(NAMESPACE_ID, USER_ID, NAME)
        resolved.parameters?.get("apiUrl")?.asText() shouldBe "https://platform"  // only in platform
        resolved.parameters?.get("apiKey")?.asText() shouldBe "ns-key"            // ns overrides platform
        resolved.parameters?.get("tenant")?.asText() shouldBe "user-ns-tenant"    // user-ns wins all
        resolved.parameters?.get("region")?.asText() shouldBe "eu"               // only in platform
    }

    "Cas P4 — platform only, no other layer → throws ConfigNotFoundException when platform absent" {
        val service = newService(platform = null, namespaceShared = null, userGlobal = null, userNamespace = null)

        shouldThrow<ConfigNotFoundException> {
            service.resolve(NAMESPACE_ID, USER_ID, NAME)
        }
    }

    // -------------------------------------------------------------------------
    // AC10 last row — granular per-parameter merge
    // -------------------------------------------------------------------------

    "granular per-parameter merge — user-global wins shared keys, base fills the rest" {
        // user-global = {a: 1, b: 2}, namespace = {a: 3, c: 4}
        // expected   = {a: 1, b: 2, c: 4}
        val nsShared = config(NAMESPACE_ID, null, """{"a":3,"c":4}""")
        val userGlobal = config(null, USER_ID, """{"a":1,"b":2}""")
        val service = newService(namespaceShared = nsShared, userGlobal = userGlobal, userNamespace = null)

        val resolved = service.resolve(NAMESPACE_ID, USER_ID, NAME)
        resolved.parameters?.get("a")?.asInt() shouldBe 1
        resolved.parameters?.get("b")?.asInt() shouldBe 2
        resolved.parameters?.get("c")?.asInt() shouldBe 4
    }
})
