package io.whozoss.agentos.persistence.neo4j

import io.kotest.core.spec.style.StringSpec
import io.kotest.extensions.spring.SpringExtension
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
import java.util.UUID

@SpringBootTest
@ActiveProfiles("test", "embedded-neo4j")
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
        "bindings roundtrip lifecycle, settings and observed branch independently of the title" {
            val ns = namespaces.save(Namespace(name = "workspace"))
            val root = cases.save(Case(namespaceId = ns.id, title = "Case title"))
            val value = bindings.create(CaseResourceBinding(rootCaseId = root.id, namespaceId = ns.id,
                integrationConfigId = UUID.randomUUID(), status = CaseResourceStatus.READY,
                settingsJson = "{}", branchName = "chosen-by-agent", setupStarted = true, setupCompleted = true))
            bindings.findByRootCaseId(root.id) shouldBe value
        }
        "ordinary deletion hides only the deleted case and retains its node and descendants" {
            val ns = namespaces.save(Namespace(name = "deletion"))
            val root = cases.save(Case(namespaceId = ns.id))
            val child = cases.save(Case(namespaceId = ns.id, parentCaseId = root.id))
            val ordinary = cases.save(Case(namespaceId = ns.id))
            caseService.delete(root.id) shouldBe true
            caseService.findByParent(ns.id).map { it.id }.toSet() shouldBe setOf(child.id, ordinary.id)
            cases.findByIds(listOf(root.id)) shouldBe emptyList()
            cases.findByIds(listOf(root.id), withRemoved = true).single().metadata.removed shouldBe true
            cases.findByIds(listOf(child.id)).single().parentCaseId shouldBe root.id
        }
        "REST-style child creation records graph ancestry and internal inventory includes removed children" {
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
            cases.findIncludingRemovedByNamespace(ns.id).map { it.id }.toSet() shouldBe setOf(root.id, child.id, grandchild.id, unrelated.id)
            roots.familyMembers(root).map { it.id }.toSet() shouldBe setOf(root.id, child.id, grandchild.id)
        }

    }
}
