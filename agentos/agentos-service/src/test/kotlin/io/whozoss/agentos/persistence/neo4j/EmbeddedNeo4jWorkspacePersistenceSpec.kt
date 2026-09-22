package io.whozoss.agentos.persistence.neo4j

import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.spec.style.StringSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.whozoss.agentos.caseFlow.*
import io.whozoss.agentos.git.*
import io.whozoss.agentos.namespace.Namespace
import io.whozoss.agentos.namespace.NamespaceRepository
import org.neo4j.driver.Driver
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource
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

    init {
        beforeEach { Neo4jContainerSupport.clearDatabase(driver) }
        "bindings roundtrip lifecycle and settings independently of the case title" {
            val ns = namespaces.save(Namespace(name = "workspace"))
            val root = cases.save(Case(namespaceId = ns.id, title = "Case title"))
            val value = bindings.create(CaseResourceBinding(rootCaseId = root.id, namespaceId = ns.id,
                integrationConfigId = UUID.randomUUID(), status = CaseResourceStatus.READY,
                settingsJson = "{}", branchName = "chosen-by-agent", setupStarted = true, setupCompleted = true))
            bindings.findByRootCaseId(root.id) shouldBe value
        }

        fun activeLabels(id: UUID): Int =
            driver.session().use { session ->
                session.run("MATCH (b:ActiveCaseResourceBinding {id: \$id}) RETURN count(b) AS n", mapOf("id" to id.toString()))
                    .single()["n"].asInt()
            }

        fun binding(rootCaseId: UUID, namespaceId: UUID = UUID.randomUUID()) =
            CaseResourceBinding(rootCaseId = rootCaseId, namespaceId = namespaceId, integrationConfigId = UUID.randomUUID())

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
    }
}
