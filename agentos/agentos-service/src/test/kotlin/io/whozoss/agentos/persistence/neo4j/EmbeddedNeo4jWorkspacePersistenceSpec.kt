package io.whozoss.agentos.persistence.neo4j

import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.core.spec.style.StringSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import io.whozoss.agentos.caseFlow.*
import io.whozoss.agentos.caseEvent.CaseConversationHistory
import io.whozoss.agentos.git.*
import io.whozoss.agentos.namespace.Namespace
import io.whozoss.agentos.namespace.NamespaceRepository
import io.whozoss.agentos.sdk.actor.Actor
import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.caseEvent.MessageContent
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
    @Autowired lateinit var receipts: CaseCommandReceiptRepository
    @Autowired lateinit var bindings: CaseResourceBindingService
    @Autowired lateinit var caseService: CaseService
    @Autowired lateinit var driver: Driver
    @Autowired lateinit var mapper: ObjectMapper
    @Autowired lateinit var journal: CaseCommandJournal
    @Autowired lateinit var conversationHistory: CaseConversationHistory
    @Autowired lateinit var roots: ExchangeRootResolver

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
        "a crash after the last receipt is completed reconciles RUNNING back to IDLE" {
            val ns = namespaces.save(Namespace(name = "completed-inbox"))
            val root = cases.save(Case(namespaceId = ns.id, status = io.whozoss.agentos.sdk.caseFlow.CaseStatus.RUNNING))
            receipts.save(CaseCommandReceipt("${root.id}:request", root.id.toString(), "{}", "{}", state = "DONE"))
            (caseService as CaseServiceImpl).recoverUnstartedWorkspaceCommands()
            cases.findByIds(listOf(root.id)).single().status shouldBe io.whozoss.agentos.sdk.caseFlow.CaseStatus.IDLE
        }
        "killed equipped cases refuse new instructions before a receipt is accepted" {
            val ns = namespaces.save(Namespace(name = "killed-inbox"))
            val root = cases.save(Case(namespaceId = ns.id, status = io.whozoss.agentos.sdk.caseFlow.CaseStatus.KILLED))
            bindings.create(CaseResourceBinding(rootCaseId = root.id, namespaceId = ns.id, integrationConfigId = UUID.randomUUID()))
            io.kotest.assertions.throwables.shouldThrow<io.whozoss.agentos.exception.ConflictException> {
                caseService.addMessage(root.id, Actor(UUID.randomUUID().toString(), "User", ActorRole.USER), listOf(MessageContent.Text("New instruction")), requestId = UUID.randomUUID())
            }
            receipts.forCase(root.id.toString()) shouldBe emptyList()
        }
        "Neo4j inbox persists expanded payload and refuses uncertain turns after restart" {
            val ns = namespaces.save(Namespace(name = "inbox"))
            val root = cases.save(Case(namespaceId = ns.id))
            bindings.create(CaseResourceBinding(rootCaseId = root.id, namespaceId = ns.id, integrationConfigId = UUID.randomUUID(), status = CaseResourceStatus.REQUESTED))
            val command = DurableCaseCommand(actor = Actor(UUID.randomUUID().toString(), "Author", ActorRole.USER),
                content = listOf(MessageContent.Text("expanded command")), sessionContext = mapOf("ticket" to "WZ-123"))
            val request = UUID.randomUUID()
            val received = journal.append(root.id, request, mapOf("text" to "/workflow"), listOf(command))
            conversationHistory.findByCase(root.id) shouldBe listOf(received)
            conversationHistory.findByCase(cases.save(Case(namespaceId = ns.id)).id) shouldBe emptyList()
            receipts.pendingCases() shouldBe listOf(root.id.toString())
            journal.hasPending(root.id) shouldBe true
            journal.hasUnfinished(root.id) shouldBe true
            journal.isWaiting(root.id) shouldBe false
            val unrelatedId = UUID.randomUUID()
            receipts.hasState(unrelatedId.toString(), listOf("QUEUED")) shouldBe false
            receipts.firstInState(unrelatedId.toString(), listOf("QUEUED")) shouldBe null
            mapper.readValue(receipts.forCase(root.id.toString()).single().batchJson, CaseCommandBatch::class.java).commands shouldBe listOf(command)
            journal.next(root.id) shouldBe command
            receipts.firstInState(root.id.toString(), listOf("STARTED"))?.id shouldBe "${root.id}:$request"
            receipts.recoverInterrupted()
            journal.recoveryRequired(root.id) shouldBe true
            journal.next(root.id) shouldBe null
            journal.acknowledge(root.id)
            receipts.pendingCases() shouldBe emptyList()
            journal.hasUnfinished(root.id) shouldBe false
            receipts.forCase(root.id.toString()).single().state shouldBe "CANCELLED"
            // An accepted instruction remains visible even if a crash and explicit cancellation
            // happened before the runtime had materialized its MessageEvent.
            conversationHistory.findByCase(root.id) shouldBe listOf(received)
        }
    }
}
