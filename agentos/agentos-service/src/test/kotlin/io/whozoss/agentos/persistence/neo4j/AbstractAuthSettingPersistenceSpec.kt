package io.whozoss.agentos.persistence.neo4j

import io.kotest.core.spec.style.StringSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.whozoss.agentos.authSetting.AuthSetting
import io.whozoss.agentos.authSetting.AuthSettingRepository
import io.whozoss.agentos.authSetting.AuthType
import io.whozoss.agentos.authSetting.authSettingFromDataMap
import io.whozoss.agentos.authSetting.toDataMap
import io.whozoss.agentos.namespace.Namespace
import io.whozoss.agentos.namespace.NamespaceRepository
import io.whozoss.agentos.sdk.entity.EntityMetadata
import org.neo4j.driver.Driver
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID

/**
 * Shared AuthSetting persistence contract tests.
 *
 * Subclasses activate a specific persistence mode (embedded harness or Testcontainers)
 * and inherit all test cases, ensuring both modes satisfy the same contract.
 *
 * A [Namespace] node must exist before namespace-scoped [AuthSetting] nodes are saved
 * because [io.whozoss.agentos.authSetting.AuthSettingNodeNeo4jRepository.findActiveByNamespaceId]
 * traverses the BELONGS_TO edge to the Namespace node (indirectly, via the scalar
 * `namespaceId` property kept in sync by [io.whozoss.agentos.authSetting.Neo4jAuthSettingRepository.save]).
 * [namespaceRepo] is used to pre-create namespaces.
 *
 * Encryption (of [AuthSetting]'s serialised data map) is exercised transitively: every
 * `save` / `find*` call round-trips through [io.whozoss.agentos.encryption.FieldEncryptor]
 * inside [io.whozoss.agentos.authSetting.Neo4jAuthSettingRepository]. The test-only key/salt
 * from `application-test.yml` activate [io.whozoss.agentos.encryption.SpringFieldEncryptor],
 * so a passing `data map round-trips correctly` test also proves the encrypt/decrypt cycle.
 */
abstract class AbstractAuthSettingPersistenceSpec : StringSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    lateinit var repo: AuthSettingRepository

    @Autowired
    lateinit var namespaceRepo: NamespaceRepository

    @Autowired
    lateinit var driver: Driver

    fun namespace() = Namespace(metadata = EntityMetadata(), name = "test-ns")

    fun setting(
        namespaceId: UUID? = null,
        userId: UUID? = null,
        name: String = "github",
        authType: AuthType = AuthType.API_KEY,
        data: Map<String, String> = mapOf("apiKey" to "secret-value"),
    ): AuthSetting =
        authSettingFromDataMap(
            authType = authType,
            data = data,
            metadata = EntityMetadata(),
            namespaceId = namespaceId,
            userId = userId,
            name = name,
            description = null,
        )

    // ---------------------------------------------------------------------------
    // Graph helpers — mirrors Neo4jChildLinkServiceSpec's direct-Cypher assertions
    // ---------------------------------------------------------------------------

    private fun edgeExists(
        childLabel: String,
        childId: String,
        parentLabel: String,
        parentId: String,
        rel: String = "BELONGS_TO",
    ): Boolean =
        driver.session().use {
            it.run(
                """
                MATCH (c:`$childLabel` {id: ${'$'}childId})
                      -[r:`$rel`]->
                      (p:`$parentLabel` {id: ${'$'}parentId})
                RETURN count(r) AS n
                """.trimIndent(),
                mapOf("childId" to childId, "parentId" to parentId),
            ).single().get("n").asInt() > 0
        }

    private fun anyOutgoingRelationship(childLabel: String, childId: String): Boolean =
        driver.session().use {
            it.run(
                "MATCH (c:`$childLabel` {id: \$childId})-[r]->() RETURN count(r) AS n",
                mapOf("childId" to childId),
            ).single().get("n").asInt() > 0
        }

    init {
        beforeEach { Neo4jContainerSupport.clearDatabase(driver) }

        // -------------------------------------------------------------------------
        // save / findById
        // -------------------------------------------------------------------------

        "save and findById returns the same setting" {
            val ns = namespaceRepo.save(namespace())
            val saved = repo.save(setting(namespaceId = ns.id, name = "github"))

            val found = repo.findById(saved.id)

            found.shouldNotBeNull()
            found.id shouldBe saved.id
            found.name shouldBe "github"
            found.authType shouldBe AuthType.API_KEY
        }

        "findById returns null for unknown id" {
            repo.findById(UUID.randomUUID()).shouldBeNull()
        }

        "data map round-trips correctly (proves the encrypt/decrypt cycle)" {
            val ns = namespaceRepo.save(namespace())
            val data = mapOf("clientId" to "my-client", "clientSecret" to "my-secret-value")
            val saved =
                repo.save(
                    setting(namespaceId = ns.id, name = "oauth", authType = AuthType.OAUTH_DISCOVERABLE, data = data),
                )

            val found = repo.findById(saved.id)

            found.shouldNotBeNull()
            found.toDataMap() shouldBe data
        }

        // -------------------------------------------------------------------------
        // findByNamespaceId — filters userId IS NULL
        // -------------------------------------------------------------------------

        "findByNamespaceId returns only namespace-shared settings (userId IS NULL)" {
            val ns = namespaceRepo.save(namespace())
            val userId = UUID.randomUUID()
            val shared = repo.save(setting(namespaceId = ns.id, userId = null, name = "github"))
            repo.save(setting(namespaceId = ns.id, userId = userId, name = "gitlab"))

            val result = repo.findByNamespaceId(ns.id)

            result shouldHaveSize 1
            result.first().id shouldBe shared.id
        }

        "findByNamespaceId returns only settings for the given namespace" {
            val nsA = namespaceRepo.save(namespace())
            val nsB = namespaceRepo.save(namespace())
            repo.save(setting(namespaceId = nsA.id, name = "github"))
            repo.save(setting(namespaceId = nsA.id, name = "gitlab"))
            repo.save(setting(namespaceId = nsB.id, name = "jira"))

            repo.findByNamespaceId(nsA.id) shouldHaveSize 2
            repo.findByNamespaceId(nsB.id) shouldHaveSize 1
            repo.findByNamespaceId(UUID.randomUUID()).shouldBeEmpty()
        }

        "findByNamespaceId returns settings sorted by name" {
            val ns = namespaceRepo.save(namespace())
            repo.save(setting(namespaceId = ns.id, name = "jira"))
            repo.save(setting(namespaceId = ns.id, name = "github"))
            repo.save(setting(namespaceId = ns.id, name = "gitlab"))

            repo.findByNamespaceId(ns.id).map { it.name } shouldBe listOf("github", "gitlab", "jira")
        }

        "findByParent delegates to findByNamespaceId" {
            val ns = namespaceRepo.save(namespace())
            repo.save(setting(namespaceId = ns.id, name = "github"))

            repo.findByParent(ns.id) shouldHaveSize 1
        }

        // -------------------------------------------------------------------------
        // findByUserId
        // -------------------------------------------------------------------------

        "findByUserId returns only settings for the given user, across scopes" {
            val ns = namespaceRepo.save(namespace())
            val userA = UUID.randomUUID()
            val userB = UUID.randomUUID()
            repo.save(setting(namespaceId = null, userId = userA, name = "github"))
            repo.save(setting(namespaceId = ns.id, userId = userA, name = "gitlab"))
            repo.save(setting(namespaceId = null, userId = userB, name = "jira"))

            repo.findByUserId(userA) shouldHaveSize 2
            repo.findByUserId(userB) shouldHaveSize 1
            repo.findByUserId(UUID.randomUUID()).shouldBeEmpty()
        }

        // -------------------------------------------------------------------------
        // findPlatformLevel
        // -------------------------------------------------------------------------

        "findPlatformLevel returns only settings with both namespaceId and userId null" {
            val platform = repo.save(setting(namespaceId = null, userId = null, name = "PLATFORM"))
            val ns = namespaceRepo.save(namespace())
            repo.save(setting(namespaceId = ns.id, userId = null, name = "NS_SHARED"))
            repo.save(setting(namespaceId = null, userId = UUID.randomUUID(), name = "USER_ONLY"))

            val result = repo.findPlatformLevel()

            result shouldHaveSize 1
            result.first().id shouldBe platform.id
        }

        // -------------------------------------------------------------------------
        // findByNamespaceIdAndUserIdAndName — exact triple
        // -------------------------------------------------------------------------

        "findByNamespaceIdAndUserIdAndName matches distinctly the 4 modes" {
            val ns = namespaceRepo.save(namespace())
            val userId = UUID.randomUUID()
            val platform = repo.save(setting(namespaceId = null, userId = null, name = "github"))
            val nsOnly = repo.save(setting(namespaceId = ns.id, userId = null, name = "github"))
            val userOnly = repo.save(setting(namespaceId = null, userId = userId, name = "github"))
            val nsAndUser = repo.save(setting(namespaceId = ns.id, userId = userId, name = "github"))

            repo.findByNamespaceIdAndUserIdAndName(null, null, "github")?.id shouldBe platform.id
            repo.findByNamespaceIdAndUserIdAndName(ns.id, null, "github")?.id shouldBe nsOnly.id
            repo.findByNamespaceIdAndUserIdAndName(null, userId, "github")?.id shouldBe userOnly.id
            repo.findByNamespaceIdAndUserIdAndName(ns.id, userId, "github")?.id shouldBe nsAndUser.id
        }

        "findByNamespaceIdAndUserIdAndName returns null when no row matches" {
            repo.findByNamespaceIdAndUserIdAndName(UUID.randomUUID(), null, "unknown").shouldBeNull()
        }

        "findByNamespaceIdAndUserIdAndName ignores soft-deleted rows" {
            val ns = namespaceRepo.save(namespace())
            val saved = repo.save(setting(namespaceId = ns.id, name = "github"))
            repo.delete(saved.id).shouldBeTrue()

            repo.findByNamespaceIdAndUserIdAndName(ns.id, null, "github").shouldBeNull()
        }

        // -------------------------------------------------------------------------
        // findAllForScope — the 4 layers in a single query
        // -------------------------------------------------------------------------

        "findAllForScope returns all four layers when all match the requested (namespaceId, userId)" {
            val ns = namespaceRepo.save(namespace())
            val userId = UUID.randomUUID()
            val platform = repo.save(setting(namespaceId = null, userId = null, name = "github"))
            val nsShared = repo.save(setting(namespaceId = ns.id, userId = null, name = "github"))
            val userGlobal = repo.save(setting(namespaceId = null, userId = userId, name = "github"))
            val userNs = repo.save(setting(namespaceId = ns.id, userId = userId, name = "github"))

            val result = repo.findAllForScope(ns.id, userId)

            result.map { it.id }.toSet() shouldBe setOf(platform.id, nsShared.id, userGlobal.id, userNs.id)
        }

        "findAllForScope excludes settings for a different namespace" {
            val ns1 = namespaceRepo.save(namespace())
            val ns2 = namespaceRepo.save(namespace())
            val userId = UUID.randomUUID()
            val forNs1 = repo.save(setting(namespaceId = ns1.id, userId = null, name = "github"))
            repo.save(setting(namespaceId = ns2.id, userId = null, name = "github"))
            // user×ns2 must NOT appear when querying for ns1
            repo.save(setting(namespaceId = ns2.id, userId = userId, name = "github"))

            val result = repo.findAllForScope(ns1.id, userId)

            result shouldHaveSize 1
            result.first().id shouldBe forNs1.id
        }

        "findAllForScope excludes settings for a different user" {
            val ns = namespaceRepo.save(namespace())
            val user1 = UUID.randomUUID()
            val user2 = UUID.randomUUID()
            val nsShared = repo.save(setting(namespaceId = ns.id, userId = null, name = "github"))
            val forUser1 = repo.save(setting(namespaceId = ns.id, userId = user1, name = "github"))
            // user2 override must NOT appear when querying for user1
            repo.save(setting(namespaceId = ns.id, userId = user2, name = "github"))

            val result = repo.findAllForScope(ns.id, user1)

            result shouldHaveSize 2
            result.map { it.id }.toSet() shouldBe setOf(nsShared.id, forUser1.id)
        }

        "findAllForScope excludes soft-deleted settings" {
            val ns = namespaceRepo.save(namespace())
            val userId = UUID.randomUUID()
            val active = repo.save(setting(namespaceId = ns.id, userId = null, name = "github"))
            val deleted = repo.save(setting(namespaceId = ns.id, userId = null, name = "gitlab"))
            repo.delete(deleted.id)

            val result = repo.findAllForScope(ns.id, userId)

            result shouldHaveSize 1
            result.first().id shouldBe active.id
        }

        "findAllForScope surfaces a user-global setting from any namespace" {
            val ns = namespaceRepo.save(namespace())
            val userId = UUID.randomUUID()
            val userGlobal = repo.save(setting(namespaceId = null, userId = userId, name = "github"))

            val result = repo.findAllForScope(ns.id, userId)

            result shouldHaveSize 1
            result.first().id shouldBe userGlobal.id
        }

        // -------------------------------------------------------------------------
        // Soft-delete + tripleKey tombstone (recreation immediately after delete)
        // -------------------------------------------------------------------------

        "delete soft-deletes the setting" {
            val ns = namespaceRepo.save(namespace())
            val saved = repo.save(setting(namespaceId = ns.id))

            repo.delete(saved.id).shouldBeTrue()
            repo.findById(saved.id).shouldBeNull()
            repo.findByNamespaceId(ns.id).shouldBeEmpty()
        }

        "double delete returns false" {
            val ns = namespaceRepo.save(namespace())
            val saved = repo.save(setting(namespaceId = ns.id))

            repo.delete(saved.id).shouldBeTrue()
            repo.delete(saved.id).shouldBeFalse()
        }

        "delete returns false for unknown id" {
            repo.delete(UUID.randomUUID()).shouldBeFalse()
        }

        "save after soft-delete on the same triple succeeds — tombstone frees the unique slot" {
            val ns = namespaceRepo.save(namespace())
            val first = repo.save(setting(namespaceId = ns.id, name = "github"))
            repo.delete(first.id).shouldBeTrue()

            val second = repo.save(setting(namespaceId = ns.id, name = "github"))

            second.id shouldNotBe first.id
            repo.findByNamespaceIdAndUserIdAndName(ns.id, null, "github")?.id shouldBe second.id
        }

        // -------------------------------------------------------------------------
        // deleteByParent
        // -------------------------------------------------------------------------

        "deleteByParent removes all settings in the namespace without touching others" {
            val ns1 = namespaceRepo.save(namespace())
            val ns2 = namespaceRepo.save(namespace())
            repo.save(setting(namespaceId = ns1.id, name = "github"))
            repo.save(setting(namespaceId = ns1.id, name = "gitlab"))
            val survivor = repo.save(setting(namespaceId = ns2.id, name = "jira"))

            val deleted = repo.deleteByParent(ns1.id)

            deleted shouldBe 2
            repo.findByNamespaceId(ns1.id).shouldBeEmpty()
            repo.findByNamespaceId(ns2.id) shouldHaveSize 1
            repo.findByNamespaceId(ns2.id).first().id shouldBe survivor.id
        }

        // -------------------------------------------------------------------------
        // BELONGS_TO link — created only for namespace-scoped settings
        // -------------------------------------------------------------------------

        "save links a namespace-scoped setting to its Namespace via BELONGS_TO" {
            val ns = namespaceRepo.save(namespace())
            val saved = repo.save(setting(namespaceId = ns.id, userId = null))

            edgeExists("AuthSetting", saved.id.toString(), "Namespace", ns.id.toString()).shouldBeTrue()
        }

        "save links a user×namespace setting to its Namespace via BELONGS_TO" {
            val ns = namespaceRepo.save(namespace())
            val saved = repo.save(setting(namespaceId = ns.id, userId = UUID.randomUUID()))

            edgeExists("AuthSetting", saved.id.toString(), "Namespace", ns.id.toString()).shouldBeTrue()
        }

        "save does not create a namespace edge for a user-global setting (namespaceId null)" {
            val saved = repo.save(setting(namespaceId = null, userId = UUID.randomUUID()))

            anyOutgoingRelationship("AuthSetting", saved.id.toString()).shouldBeFalse()
        }

        "save does not create a namespace edge for a platform setting (both null)" {
            val saved = repo.save(setting(namespaceId = null, userId = null))

            anyOutgoingRelationship("AuthSetting", saved.id.toString()).shouldBeFalse()
        }
    }
}
