package io.whozoss.agentos.persistence.neo4j

import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.spec.style.StringSpec
import io.kotest.data.forAll
import io.kotest.data.headers
import io.kotest.data.row
import io.kotest.data.table
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.whozoss.agentos.caseFlow.*
import io.whozoss.agentos.git.*
import io.whozoss.agentos.namespace.Namespace
import io.whozoss.agentos.namespace.NamespaceRepository
import org.neo4j.driver.Driver
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.DefaultApplicationArguments
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource
import java.time.Instant
import java.util.UUID

@SpringBootTest
@ActiveProfiles("test", "embedded-neo4j")
@TestPropertySource(properties = ["agentos.git.workspaces.enabled=true"])
@Import(EmbeddedNeo4jTestConfiguration::class)
class EmbeddedNeo4jWorkspacePersistenceSpec : StringSpec() {
    override fun extensions() = listOf(SpringExtension)
    @Autowired lateinit var cases: CaseRepository
    @Autowired lateinit var namespaces: NamespaceRepository
    @Autowired lateinit var bindings: CaseResourceBindingService
    @Autowired lateinit var caseService: CaseService
    @Autowired lateinit var driver: Driver
    @Autowired lateinit var roots: GitExchangeRootResolver
    @Autowired lateinit var schema: CaseResourceBindingSchemaInitializer

    init {
        beforeEach { Neo4jContainerSupport.clearDatabase(driver) }

        fun settings(namespaceId: UUID) = GitRepositorySettings(
            configId = UUID.randomUUID(), namespaceId = namespaceId, repositoryUrl = "https://forge.example/org/project.git",
            mainBranch = "develop", serviceAuthSettingId = UUID.randomUUID(), autoWorktreeForRootCases = true,
            setupCommand = "pnpm install --ignore-scripts",
        )

        "bindings roundtrip lifecycle and settings independently of the case title" {
            val ns = namespaces.save(Namespace(name = "workspace"))
            val root = cases.save(Case(namespaceId = ns.id, title = "Case title"))
            val value = bindings.create(CaseResourceBinding(rootCaseId = root.id, namespaceId = ns.id,
                integrationConfigId = UUID.randomUUID(), status = CaseResourceStatus.READY,
                settings = settings(ns.id), branchName = "chosen-by-agent", setup = SetupState.COMPLETED,
                summary = GitWorkspaceSummary(branchState = BranchState.PUSHED, prState = PrState.OPEN, prNumber = 42,
                    observedAt = Instant.parse("2026-10-06T08:00:00Z"))))
            bindings.findByRootCaseId(root.id) shouldBe value
            bindings.findByRootCaseId(root.id)?.settings?.setupCommand shouldBe "pnpm install --ignore-scripts"
        }

        fun activeLabels(id: UUID): Int =
            driver.session().use { session ->
                session.run("MATCH (b:ActiveCaseResourceBinding {id: \$id}) RETURN count(b) AS n", mapOf("id" to id.toString()))
                    .single()["n"].asInt()
            }

        fun binding(rootCaseId: UUID, namespaceId: UUID = UUID.randomUUID()) =
            CaseResourceBinding(rootCaseId = rootCaseId, namespaceId = namespaceId, integrationConfigId = UUID.randomUUID())

        "a binding whose stored settings cannot be read is still listed, without settings" {
            val namespaceId = UUID.randomUUID()
            val unreadable = bindings.create(binding(UUID.randomUUID(), namespaceId).copy(settings = settings(namespaceId)))
            val readable = bindings.create(binding(UUID.randomUUID(), namespaceId).copy(settings = settings(namespaceId)))
            driver.session().use { session ->
                session.run("MATCH (b:CaseResourceBinding {id: \$id}) SET b.settingsJson = '{not json'", mapOf("id" to unreadable.id.toString()))
                    .consume()
            }

            bindings.findByParent(namespaceId).associate { it.id to it.settings } shouldBe
                mapOf(unreadable.id to null, readable.id to readable.settings)
        }

        "bindings saved with the former setup flags read their setup state after the schema backfill" {
            table(
                headers("setupStarted", "setupCompleted", "setup"),
                row(false, false, SetupState.NOT_STARTED),
                row(true, false, SetupState.STARTED),
                row(true, true, SetupState.COMPLETED),
            ).forAll { started, completed, setup ->
                val former = bindings.create(binding(UUID.randomUUID()))
                driver.session().use { session ->
                    session.run(
                        "MATCH (b:CaseResourceBinding {id: \$id}) REMOVE b.setupState SET b.setupStarted = \$started, b.setupCompleted = \$completed",
                        mapOf("id" to former.id.toString(), "started" to started, "completed" to completed),
                    ).consume()
                }

                schema.run(DefaultApplicationArguments())

                bindings.findByRootCaseId(former.rootCaseId)?.setup shouldBe setup
            }
        }

        "an active binding carries the Active label and the database refuses a second one for the same root case" {
            val rootCaseId = UUID.randomUUID()
            val original = bindings.create(binding(rootCaseId))
            activeLabels(original.id) shouldBe 1
            bindings.update(original.copy(status = CaseResourceStatus.READY))
            activeLabels(original.id) shouldBe 1
            shouldThrowAny { bindings.create(binding(rootCaseId)) }
            bindings.delete(original.id) shouldBe true
            activeLabels(original.id) shouldBe 0
            bindings.findByRootCaseId(rootCaseId) shouldBe null
            val replacement = bindings.create(binding(rootCaseId))
            bindings.findByRootCaseId(rootCaseId)?.id shouldBe replacement.id
        }

        "the worker's binding sweeps are served by an index on status and creation" {
            driver.session().use { session ->
                val index =
                    session
                        .run(
                            "SHOW INDEXES YIELD name, labelsOrTypes, properties " +
                                "WHERE name = 'case_resource_binding_active_status' RETURN labelsOrTypes, properties",
                        ).single()
                index["labelsOrTypes"].asList { it.asString() } shouldBe listOf("ActiveCaseResourceBinding")
                index["properties"].asList { it.asString() } shouldBe listOf("status", "created")
            }
        }

        "deleting a namespace's bindings removes their Active label" {
            val namespaceId = UUID.randomUUID()
            val rows = listOf(bindings.create(binding(UUID.randomUUID(), namespaceId)), bindings.create(binding(UUID.randomUUID(), namespaceId)))
            bindings.deleteByParent(namespaceId) shouldBe 2
            rows.map { activeLabels(it.id) } shouldBe listOf(0, 0)
            bindings.findByParent(namespaceId) shouldBe emptyList()
        }

        "each write keeps the creation date and moves the modification date" {
            val original = bindings.create(binding(UUID.randomUUID()))
            Thread.sleep(5)
            val ready = bindings.update(original.copy(status = CaseResourceStatus.READY))
            val stored = bindings.findByRootCaseId(original.rootCaseId)!!
            stored.metadata.created shouldBe original.metadata.created
            stored.metadata.modified shouldBeGreaterThan original.metadata.modified
            ready.status shouldBe CaseResourceStatus.READY
        }

        "REST-style child creation records graph ancestry and the family lookup climbs through removed cases" {
            val ns = namespaces.save(Namespace(name = "hierarchy"))
            val root = caseService.create(Case(namespaceId = ns.id))
            val child = caseService.create(Case(namespaceId = ns.id, parentCaseId = root.id))
            // Historical records may carry parentCaseId without the PARENT_OF graph edge.
            val grandchild = cases.save(Case(namespaceId = ns.id, parentCaseId = child.id,
                status = io.whozoss.agentos.sdk.caseFlow.CaseStatus.KILLED))
            val unrelated = cases.save(Case(namespaceId = ns.id))
            val otherNs = namespaces.save(Namespace(name = "other-hierarchy"))
            cases.save(Case(namespaceId = otherNs.id, parentCaseId = root.id))
            cases.countAncestorDepth(child.id) shouldBe 1
            cases.findActiveDescendants(root.id).map { it.id } shouldBe listOf(child.id)
            cases.save(child.copy(metadata = child.metadata.copy(removed = true)))
            roots.familyAmong(root, cases.findByParent(ns.id)).map { it.id }.toSet() shouldBe setOf(root.id, grandchild.id)
            roots.familyAmong(root, listOf(unrelated)) shouldBe emptyList()
        }

        "a stale write is refused instead of overwriting a newer one" {
            val original = bindings.create(binding(UUID.randomUUID()))
            bindings.update(original.copy(status = CaseResourceStatus.PREPARING))
            shouldThrowAny { bindings.update(original.copy(status = CaseResourceStatus.READY)) }
            bindings.findByRootCaseId(original.rootCaseId)?.status shouldBe CaseResourceStatus.PREPARING
        }

        "a namespace's bindings are found through an index" {
            driver.session().use { session ->
                session.run("SHOW INDEXES YIELD name WHERE name = 'case_resource_binding_namespace_lookup' RETURN count(*) AS n")
                    .single()["n"].asInt() shouldBe 1
            }
        }
    }
}
