package io.whozoss.agentos.integrationConfig

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.springframework.web.server.ResponseStatusException
import io.whozoss.agentos.sdk.entity.EntityMetadata
import java.util.UUID

/**
 * Unit tests for [IntegrationConfigServiceImpl].
 *
 * Uses an [InMemoryIntegrationConfigRepository] to keep tests fast and isolated.
 * Each test builds its own service instance to guarantee full isolation.
 */
class IntegrationConfigServiceImplSpec : StringSpec() {
    private val mapper = ObjectMapper().registerKotlinModule().findAndRegisterModules()

    private fun newService(): IntegrationConfigServiceImpl =
        IntegrationConfigServiceImpl(InMemoryIntegrationConfigRepository(), IntegrationConfigMergeStrategy())

    private fun config(
        namespaceId: UUID? = UUID.randomUUID(),
        userId: UUID? = null,
        name: String = "JIRA",
        integrationType: String = "JIRA",
        description: String? = null,
        parametersJson: String? = null,
    ): IntegrationConfig =
        IntegrationConfig(
            metadata = EntityMetadata(),
            namespaceId = namespaceId,
            userId = userId,
            name = name,
            integrationType = integrationType,
            description = description,
            parameters = parametersJson?.let { mapper.readTree(it) },
        )

    init {

        // -------------------------------------------------------------------------
        // Create / Read
        // -------------------------------------------------------------------------

        "create and findById returns the same config" {
            val service = newService()
            val cfg = config()

            val saved = service.create(cfg)
            val found = service.findById(saved.metadata.id)

            found.shouldNotBeNull()
            found.metadata.id shouldBe saved.metadata.id
            found.name shouldBe "JIRA"
            found.integrationType shouldBe "JIRA"
        }

        "findById returns null for unknown id" {
            val service = newService()
            service.findById(UUID.randomUUID()).shouldBeNull()
        }

        "findByParent returns only configs for the given namespace" {
            val service = newService()
            val nsA = UUID.randomUUID()
            val nsB = UUID.randomUUID()

            service.create(config(namespaceId = nsA, name = "JIRA"))
            service.create(config(namespaceId = nsA, name = "SLACK"))
            service.create(config(namespaceId = nsB, name = "GITHUB"))

            service.findByParent(nsA) shouldHaveSize 2
            service.findByParent(nsB) shouldHaveSize 1
            service.findByParent(UUID.randomUUID()).shouldBeEmpty()
        }

        "findByParent returns configs sorted by name" {
            val service = newService()
            val nsId = UUID.randomUUID()

            service.create(config(namespaceId = nsId, name = "SLACK"))
            service.create(config(namespaceId = nsId, name = "GITHUB"))
            service.create(config(namespaceId = nsId, name = "JIRA"))

            val names = service.findByParent(nsId).map { it.name }
            names shouldBe listOf("GITHUB", "JIRA", "SLACK")
        }

        // -------------------------------------------------------------------------
        // findByNamespaceAndName
        // -------------------------------------------------------------------------

        "findByNamespaceAndName returns the matching config" {
            val service = newService()
            val nsId = UUID.randomUUID()
            service.create(config(namespaceId = nsId, name = "JIRA"))

            val found = service.findByNamespaceAndName(nsId, "JIRA")
            found.shouldNotBeNull()
            found.name shouldBe "JIRA"
        }

        "findByNamespaceAndName returns null when name does not exist" {
            val service = newService()
            service.findByNamespaceAndName(UUID.randomUUID(), "JIRA").shouldBeNull()
        }

        "findByNamespaceAndName is namespace-isolated" {
            val service = newService()
            val nsA = UUID.randomUUID()
            val nsB = UUID.randomUUID()
            service.create(config(namespaceId = nsA, name = "JIRA"))

            service.findByNamespaceAndName(nsB, "JIRA").shouldBeNull()
        }

        // -------------------------------------------------------------------------
        // Uniqueness constraint on create
        // -------------------------------------------------------------------------

        "create throws 409 when (namespaceId, name) already exists" {
            val service = newService()
            val nsId = UUID.randomUUID()
            service.create(config(namespaceId = nsId, name = "JIRA"))

            shouldThrow<ResponseStatusException> {
                service.create(config(namespaceId = nsId, name = "JIRA"))
            }.statusCode.value() shouldBe 409
        }

        "create allows same name in different namespaces" {
            val service = newService()
            val nsA = UUID.randomUUID()
            val nsB = UUID.randomUUID()

            service.create(config(namespaceId = nsA, name = "JIRA"))
            service.create(config(namespaceId = nsB, name = "JIRA")) // must not throw

            service.findByParent(nsA) shouldHaveSize 1
            service.findByParent(nsB) shouldHaveSize 1
        }

        // -------------------------------------------------------------------------
        // Triple-mode (story 6.1, AC1, AC2, FR27bis)
        // -------------------------------------------------------------------------

        "create succeeds with namespaceId=null and userId=null (platform scope)" {
            val service = newService()
            val saved = service.create(config(namespaceId = null, userId = null))
            saved.namespaceId shouldBe null
            saved.userId shouldBe null
        }

        "create succeeds with namespaceId only (Epic 4 path preserved)" {
            val service = newService()
            val saved = service.create(config(namespaceId = UUID.randomUUID(), userId = null))
            saved.shouldNotBeNull()
        }

        "create succeeds with userId only (user-global)" {
            val service = newService()
            val saved = service.create(config(namespaceId = null, userId = UUID.randomUUID()))
            saved.shouldNotBeNull()
        }

        "create succeeds with both namespaceId and userId (user × namespace overlay)" {
            val service = newService()
            val saved = service.create(config(namespaceId = UUID.randomUUID(), userId = UUID.randomUUID()))
            saved.shouldNotBeNull()
        }

        "create throws 409 on duplicate user-only triple (null, user, name)" {
            val service = newService()
            val userId = UUID.randomUUID()
            service.create(config(namespaceId = null, userId = userId, name = "JIRA"))

            shouldThrow<ResponseStatusException> {
                service.create(config(namespaceId = null, userId = userId, name = "JIRA"))
            }.statusCode.value() shouldBe 409
        }

        "create throws 409 on duplicate user × namespace triple (ns, user, name)" {
            val service = newService()
            val nsId = UUID.randomUUID()
            val userId = UUID.randomUUID()
            service.create(config(namespaceId = nsId, userId = userId, name = "JIRA"))

            shouldThrow<ResponseStatusException> {
                service.create(config(namespaceId = nsId, userId = userId, name = "JIRA"))
            }.statusCode.value() shouldBe 409
        }

        "three rows differing only by scope coexist for the same name (AC2 grid)" {
            // (ns=A, user=null, name) × (ns=null, user=alice, name) × (ns=A, user=alice, name)
            // — all three valid, none colliding under the (namespaceId, userId, name) uniqueness.
            val service = newService()
            val nsA = UUID.randomUUID()
            val alice = UUID.randomUUID()

            val nsOnly = service.create(config(namespaceId = nsA, userId = null, name = "JIRA"))
            val userOnly = service.create(config(namespaceId = null, userId = alice, name = "JIRA"))
            val userNamespace = service.create(config(namespaceId = nsA, userId = alice, name = "JIRA"))

            // All three are persisted as distinct rows
            (setOf(nsOnly.id, userOnly.id, userNamespace.id)).size shouldBe 3

            // findByTriple retrieves each one independently
            service.findByTriple(nsA, null, "JIRA")?.id shouldBe nsOnly.id
            service.findByTriple(null, alice, "JIRA")?.id shouldBe userOnly.id
            service.findByTriple(nsA, alice, "JIRA")?.id shouldBe userNamespace.id
        }

        "update conflict check uses the triple, not just (namespaceId, name)" {
            // A user-overlay row with the same (namespaceId, name) as a namespace-shared row
            // must NOT block renaming the user-overlay; the conflict check must be triple-aware.
            val service = newService()
            val nsId = UUID.randomUUID()
            val alice = UUID.randomUUID()
            service.create(config(namespaceId = nsId, userId = null, name = "JIRA"))
            val aliceCfg = service.create(config(namespaceId = nsId, userId = alice, name = "JIRA-SECONDARY"))

            // Renaming alice's overlay to "JIRA" must succeed because the existing (nsId, null, "JIRA")
            // row has a different scope — the triple is (nsId, alice, "JIRA"), not used yet.
            val renamed = service.update(aliceCfg.copy(name = "JIRA"))
            renamed.name shouldBe "JIRA"
        }

        "update throws 409 when renaming would collide within the same scope" {
            val service = newService()
            val nsId = UUID.randomUUID()
            val alice = UUID.randomUUID()
            service.create(config(namespaceId = nsId, userId = alice, name = "OLD"))
            val toUpdate = service.create(config(namespaceId = nsId, userId = alice, name = "OTHER"))

            shouldThrow<ResponseStatusException> {
                service.update(toUpdate.copy(name = "OLD"))
            }.statusCode.value() shouldBe 409
        }

        // -------------------------------------------------------------------------
        // Parameters (JsonNode)
        // -------------------------------------------------------------------------

        "config with null parameters is persisted and retrieved correctly" {
            val service = newService()
            val cfg = config(parametersJson = null)
            val saved = service.create(cfg)

            val found = service.findById(saved.metadata.id)
            found.shouldNotBeNull()
            found.parameters.shouldBeNull()
        }

        "config with nested JSON parameters is preserved" {
            val service = newService()
            val json = """{"apiUrl": "https://jira.example.com", "apiKey": "secret", "projects": ["PROJ", "DEV"]}"""
            val cfg = config(parametersJson = json)
            val saved = service.create(cfg)

            val found = service.findById(saved.metadata.id)
            found.shouldNotBeNull()
            found.parameters.shouldNotBeNull()
            found.parameters!!.get("apiUrl").asText() shouldBe "https://jira.example.com"
            found.parameters.get("projects").size() shouldBe 2
        }

        // -------------------------------------------------------------------------
        // Delete
        // -------------------------------------------------------------------------

        "delete soft-deletes the config" {
            val service = newService()
            val nsId = UUID.randomUUID()
            val cfg = service.create(config(namespaceId = nsId))

            service.delete(cfg.metadata.id) shouldBe true

            service.findById(cfg.metadata.id).shouldBeNull()
            service.findByParent(nsId).shouldBeEmpty()
        }

        "delete returns false for unknown id" {
            val service = newService()
            service.delete(UUID.randomUUID()) shouldBe false
        }

        "deleteByParent removes all configs for a namespace" {
            val service = newService()
            val nsId = UUID.randomUUID()

            service.create(config(namespaceId = nsId, name = "JIRA"))
            service.create(config(namespaceId = nsId, name = "SLACK"))

            val count = service.deleteByParent(nsId)
            count shouldBe 2
            service.findByParent(nsId).shouldBeEmpty()
        }

        "deleteByParent does not affect other namespaces" {
            val service = newService()
            val nsA = UUID.randomUUID()
            val nsB = UUID.randomUUID()

            service.create(config(namespaceId = nsA, name = "JIRA"))
            service.create(config(namespaceId = nsB, name = "JIRA"))

            service.deleteByParent(nsA)

            service.findByParent(nsA).shouldBeEmpty()
            service.findByParent(nsB) shouldHaveSize 1
        }

        // -------------------------------------------------------------------------
        // Update
        // -------------------------------------------------------------------------

        "description is preserved through create and findById" {
            val service = newService()
            val cfg = config(description = "My integration")
            val saved = service.create(cfg)

            val found = service.findById(saved.metadata.id)

            found?.description shouldBe "My integration"
        }

        "description is preserved through update" {
            val service = newService()
            val original = service.create(config(description = "original"))

            val updated = service.update(original.copy(description = "updated"))

            updated.description shouldBe "updated"
            service.findById(original.metadata.id)?.description shouldBe "updated"
        }

        "update replaces the config" {
            val service = newService()
            val nsId = UUID.randomUUID()
            val original = service.create(config(namespaceId = nsId, name = "JIRA", integrationType = "JIRA"))

            val updated = service.update(original.copy(integrationType = "JIRA_V2"))

            updated.integrationType shouldBe "JIRA_V2"
            service.findById(original.metadata.id)?.integrationType shouldBe "JIRA_V2"
        }

        // -------------------------------------------------------------------------
        // IG-3 — cross-layer integrationType consistency at create/update
        // -------------------------------------------------------------------------

        "create rejects user×ns override with integrationType differing from the NS layer" {
            val service = newService()
            val nsId = UUID.randomUUID()
            val userId = UUID.randomUUID()
            // NS-shared layer with type=JIRA
            service.create(config(namespaceId = nsId, name = "JIRA", integrationType = "JIRA"))
            // User tries to create a user×ns override with the same name but different type
            shouldThrow<ResponseStatusException> {
                service.create(
                    config(namespaceId = nsId, userId = userId, name = "JIRA", integrationType = "FILE_ACCESS"),
                )
            }
        }

        "create rejects user-global override with integrationType differing from a user×ns layer of the same user" {
            val service = newService()
            val nsId = UUID.randomUUID()
            val userId = UUID.randomUUID()
            // user×ns layer for this user
            service.create(
                config(namespaceId = nsId, userId = userId, name = "JIRA", integrationType = "JIRA"),
            )
            // Same user creates a user-global with the same name but different type
            shouldThrow<ResponseStatusException> {
                service.create(
                    config(namespaceId = null, userId = userId, name = "JIRA", integrationType = "FILE_ACCESS"),
                )
            }
        }

        "create rejects user×ns override with integrationType differing from the user-global of the same user" {
            val service = newService()
            val nsId = UUID.randomUUID()
            val userId = UUID.randomUUID()
            // user-global layer for this user
            service.create(
                config(namespaceId = null, userId = userId, name = "JIRA", integrationType = "JIRA"),
            )
            // Same user adds a user×ns override with a different type — would break the merge
            shouldThrow<ResponseStatusException> {
                service.create(
                    config(namespaceId = nsId, userId = userId, name = "JIRA", integrationType = "FILE_ACCESS"),
                )
            }
        }

        "create accepts user×ns override with the SAME integrationType as the NS layer" {
            val service = newService()
            val nsId = UUID.randomUUID()
            val userId = UUID.randomUUID()
            service.create(config(namespaceId = nsId, name = "JIRA", integrationType = "JIRA"))
            // Should NOT throw — same integrationType is the legitimate override case
            val override = service.create(
                config(namespaceId = nsId, userId = userId, name = "JIRA", integrationType = "JIRA"),
            )
            override.integrationType shouldBe "JIRA"
        }

        "create allows two different users to have user-overrides with different integrationTypes (cross-user is by design)" {
            val service = newService()
            val nsId = UUID.randomUUID()
            val userA = UUID.randomUUID()
            val userB = UUID.randomUUID()
            // user A user-global with type=JIRA
            service.create(config(namespaceId = null, userId = userA, name = "MAIL", integrationType = "JIRA"))
            // user B user-global with the same name but type=FILE_ACCESS — no merge between users
            // → legitimate, must NOT throw
            val configB = service.create(
                config(namespaceId = null, userId = userB, name = "MAIL", integrationType = "FILE_ACCESS"),
            )
            configB.integrationType shouldBe "FILE_ACCESS"
        }

        "update rejects rename that introduces an integrationType conflict with another layer" {
            val service = newService()
            val nsId = UUID.randomUUID()
            val userId = UUID.randomUUID()
            service.create(config(namespaceId = nsId, name = "JIRA", integrationType = "JIRA"))
            val userOverride = service.create(
                config(namespaceId = nsId, userId = userId, name = "FILES", integrationType = "FILE_ACCESS"),
            )
            // Rename the user override from "FILES" to "JIRA" — would now collide with the NS
            // layer's "JIRA"/JIRA but the user override carries integrationType=FILE_ACCESS
            shouldThrow<ResponseStatusException> {
                service.update(userOverride.copy(name = "JIRA"))
            }
        }

        // -------------------------------------------------------------------------
        // IG-3 — platform layer checks in assertConsistentIntegrationTypeAcrossLayers
        // -------------------------------------------------------------------------

        "create rejects NS-shared config whose integrationType conflicts with an existing platform config" {
            val service = newService()
            val nsId = UUID.randomUUID()
            // Platform layer with type=JIRA
            service.create(config(namespaceId = null, userId = null, name = "JIRA", integrationType = "JIRA"))
            // NS-shared layer with the same name but a different type — would break the merge
            shouldThrow<ResponseStatusException> {
                service.create(
                    config(namespaceId = nsId, userId = null, name = "JIRA", integrationType = "FILE_ACCESS"),
                )
            }
        }

        "create rejects user-global config whose integrationType conflicts with an existing platform config" {
            val service = newService()
            val userId = UUID.randomUUID()
            service.create(config(namespaceId = null, userId = null, name = "JIRA", integrationType = "JIRA"))
            shouldThrow<ResponseStatusException> {
                service.create(
                    config(namespaceId = null, userId = userId, name = "JIRA", integrationType = "FILE_ACCESS"),
                )
            }
        }

        // -------------------------------------------------------------------------
        // findEffective — 4-tier parameter-level merge
        // -------------------------------------------------------------------------

        "findEffective returns empty list when no configs exist for context" {
            val service = newService()
            service.findEffective(UUID.randomUUID(), UUID.randomUUID()).shouldBeEmpty()
        }

        "findEffective returns single platform layer as-is when it is the only layer" {
            val service = newService()
            val nsId = UUID.randomUUID()
            val userId = UUID.randomUUID()
            val platform = service.create(
                config(
                    namespaceId = null,
                    userId = null,
                    name = "JIRA",
                    parametersJson = """{"apiUrl": "https://platform.example.com"}""",
                ),
            )

            val effective = service.findEffective(nsId, userId)

            effective shouldHaveSize 1
            effective.first().id shouldBe platform.id
            effective.first().parameters!!.get("apiUrl").asText() shouldBe "https://platform.example.com"
        }

        "findEffective merges platform and NS-shared layers, NS-shared params win" {
            val service = newService()
            val nsId = UUID.randomUUID()
            val userId = UUID.randomUUID()
            // Platform: base apiUrl + sharedKey
            service.create(
                config(
                    namespaceId = null,
                    userId = null,
                    name = "JIRA",
                    parametersJson = """{"apiUrl": "https://platform.example.com", "sharedKey": "from-platform"}""",
                ),
            )
            // NS-shared: overrides apiUrl, leaves sharedKey inherited from platform
            service.create(
                config(
                    namespaceId = nsId,
                    userId = null,
                    name = "JIRA",
                    parametersJson = """{"apiUrl": "https://ns.example.com"}""",
                ),
            )

            val effective = service.findEffective(nsId, userId)

            effective shouldHaveSize 1
            val params = effective.first().parameters!!
            params.get("apiUrl").asText() shouldBe "https://ns.example.com"
            params.get("sharedKey").asText() shouldBe "from-platform"
        }

        "findEffective merges platform and user-global layers, user-global params win" {
            val service = newService()
            val nsId = UUID.randomUUID()
            val userId = UUID.randomUUID()
            service.create(
                config(
                    namespaceId = null,
                    userId = null,
                    name = "JIRA",
                    parametersJson = """{"apiUrl": "https://platform.example.com", "token": "platform-token"}""",
                ),
            )
            service.create(
                config(
                    namespaceId = null,
                    userId = userId,
                    name = "JIRA",
                    parametersJson = """{"token": "user-token"}""",
                ),
            )

            val effective = service.findEffective(nsId, userId)

            effective shouldHaveSize 1
            val params = effective.first().parameters!!
            params.get("apiUrl").asText() shouldBe "https://platform.example.com"
            params.get("token").asText() shouldBe "user-token"
        }

        "findEffective merges all four layers, highest-precedence layer wins per key" {
            val service = newService()
            val nsId = UUID.randomUUID()
            val userId = UUID.randomUUID()
            // Platform: base defaults
            service.create(
                config(
                    namespaceId = null,
                    userId = null,
                    name = "JIRA",
                    parametersJson = """{"apiUrl": "https://platform.example.com", "platformOnly": "p"}""",
                ),
            )
            // NS-shared: overrides apiUrl
            service.create(
                config(
                    namespaceId = nsId,
                    userId = null,
                    name = "JIRA",
                    parametersJson = """{"apiUrl": "https://ns.example.com", "nsOnly": "ns"}""",
                ),
            )
            // User-global: overrides token
            service.create(
                config(
                    namespaceId = null,
                    userId = userId,
                    name = "JIRA",
                    parametersJson = """{"token": "user-global-token", "userOnly": "ug"}""",
                ),
            )
            // User × namespace: overrides apiUrl to its final value
            service.create(
                config(
                    namespaceId = nsId,
                    userId = userId,
                    name = "JIRA",
                    parametersJson = """{"apiUrl": "https://user-ns.example.com"}""",
                ),
            )

            val effective = service.findEffective(nsId, userId)

            effective shouldHaveSize 1
            val params = effective.first().parameters!!
            // user×ns wins for apiUrl
            params.get("apiUrl").asText() shouldBe "https://user-ns.example.com"
            // user-global wins for token
            params.get("token").asText() shouldBe "user-global-token"
            // keys present only in lower layers are still inherited
            params.get("platformOnly").asText() shouldBe "p"
            params.get("nsOnly").asText() shouldBe "ns"
            params.get("userOnly").asText() shouldBe "ug"
        }

        "findEffective: explicit null in override inherits from base (no credential blanking)" {
            val service = newService()
            val nsId = UUID.randomUUID()
            val userId = UUID.randomUUID()
            service.create(
                config(
                    namespaceId = null,
                    userId = null,
                    name = "JIRA",
                    parametersJson = """{"apiKey": "secret"}""",
                ),
            )
            // NS-shared sends explicit null for apiKey — must NOT blank the platform value
            service.create(
                config(
                    namespaceId = nsId,
                    userId = null,
                    name = "JIRA",
                    parametersJson = """{"apiKey": null, "otherKey": "value"}""",
                ),
            )

            val effective = service.findEffective(nsId, userId)

            effective shouldHaveSize 1
            val params = effective.first().parameters!!
            params.get("apiKey").asText() shouldBe "secret"
            params.get("otherKey").asText() shouldBe "value"
        }

        "findEffective: array in override replaces wholesale (no concat)" {
            val service = newService()
            val nsId = UUID.randomUUID()
            val userId = UUID.randomUUID()
            service.create(
                config(
                    namespaceId = null,
                    userId = null,
                    name = "JIRA",
                    parametersJson = """{"projects": ["PROJ-A", "PROJ-B"]}""",
                ),
            )
            service.create(
                config(
                    namespaceId = nsId,
                    userId = null,
                    name = "JIRA",
                    parametersJson = """{"projects": ["PROJ-C"]}""",
                ),
            )

            val effective = service.findEffective(nsId, userId)

            effective shouldHaveSize 1
            val projects = effective.first().parameters!!.get("projects")
            projects.size() shouldBe 1
            projects[0].asText() shouldBe "PROJ-C"
        }

        "findEffective result identity comes from the platform layer (lowest precedence)" {
            val service = newService()
            val nsId = UUID.randomUUID()
            val userId = UUID.randomUUID()
            val platform = service.create(
                config(namespaceId = null, userId = null, name = "JIRA"),
            )
            service.create(
                config(namespaceId = nsId, userId = userId, name = "JIRA"),
            )

            val effective = service.findEffective(nsId, userId)

            effective shouldHaveSize 1
            // id, namespaceId, userId come from the platform (lowest) layer
            effective.first().id shouldBe platform.id
            effective.first().namespaceId shouldBe null
            effective.first().userId shouldBe null
        }

        "findEffective keeps configs with different names independent" {
            val service = newService()
            val nsId = UUID.randomUUID()
            val userId = UUID.randomUUID()
            service.create(
                config(
                    namespaceId = null,
                    userId = null,
                    name = "JIRA",
                    parametersJson = """{"url": "jira"}""",
                ),
            )
            service.create(
                config(
                    namespaceId = nsId,
                    userId = null,
                    name = "SLACK",
                    parametersJson = """{"url": "slack"}""",
                ),
            )

            val effective = service.findEffective(nsId, userId)

            effective shouldHaveSize 2
            val byName = effective.associateBy { it.name }
            byName["JIRA"]!!.parameters!!.get("url").asText() shouldBe "jira"
            byName["SLACK"]!!.parameters!!.get("url").asText() shouldBe "slack"
        }

        "findEffective ignores configs from other namespaces or users" {
            val service = newService()
            val nsA = UUID.randomUUID()
            val nsB = UUID.randomUUID()
            val alice = UUID.randomUUID()
            val bob = UUID.randomUUID()
            // NS-B shared — must NOT appear for nsA/alice context
            service.create(config(namespaceId = nsB, userId = null, name = "JIRA"))
            // Bob's user-global — must NOT appear for alice context
            service.create(config(namespaceId = null, userId = bob, name = "JIRA"))

            service.findEffective(nsA, alice).shouldBeEmpty()
        }
    }
}
