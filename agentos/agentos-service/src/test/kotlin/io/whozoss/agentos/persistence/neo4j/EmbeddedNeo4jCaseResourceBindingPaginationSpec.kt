package io.whozoss.agentos.persistence.neo4j

import io.kotest.core.spec.style.StringSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldNotContainAnyOf
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.whozoss.agentos.git.CaseResourceBinding
import io.whozoss.agentos.git.CaseResourceBindingCursor
import io.whozoss.agentos.git.CaseResourceBindingNodeNeo4jRepository
import io.whozoss.agentos.git.CaseResourceBindingService
import io.whozoss.agentos.git.CaseResourceStatus
import io.whozoss.agentos.sdk.entity.EntityMetadata
import org.neo4j.driver.Driver
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.data.neo4j.repository.query.Query
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

@SpringBootTest
@ActiveProfiles("test", "embedded-neo4j")
@TestPropertySource(properties = ["agentos.git.workspaces.enabled=true"])
@Import(EmbeddedNeo4jTestConfiguration::class)
class EmbeddedNeo4jCaseResourceBindingPaginationSpec : StringSpec() {
    override fun extensions() = listOf(SpringExtension)
    @Autowired lateinit var bindings: CaseResourceBindingService
    @Autowired lateinit var driver: Driver

    init {
        beforeEach { Neo4jContainerSupport.clearDatabase(driver) }

        "equal timestamps use the ID as a tie breaker even when the previous page disappears" {
            val created = Instant.parse("2026-01-01T00:00:00Z")
            val namespace = UUID.randomUUID()
            fun row(id: Long, status: CaseResourceStatus = CaseResourceStatus.READY) =
                CaseResourceBinding(metadata = EntityMetadata(id = UUID(0, id)),
                    rootCaseId = UUID.randomUUID(), namespaceId = namespace,
                    integrationConfigId = UUID.randomUUID(), status = status)
            // Creation dates are audited on insert: set them in the database to control the order.
            fun createdAt(time: Instant, vararg ids: Long) =
                driver.session().use { session ->
                    session.run(
                        "MATCH (b:CaseResourceBinding) WHERE b.id IN ${'$'}ids SET b.created = datetime(${'$'}time)",
                        mapOf("ids" to ids.map { UUID(0, it).toString() }, "time" to time.toString()),
                    ).consume()
                }
            val rows = listOf(row(6), row(2), row(4), row(1), row(3), row(5))
            rows.forEach { bindings.create(it) }
            bindings.create(row(7, status = CaseResourceStatus.FAILED))
            bindings.create(row(8)).also { bindings.delete(it.id) }
            // An earlier timestamp sorts first even with an ID above every other row.
            bindings.create(row(9))
            createdAt(created, 1, 2, 3, 4, 5, 6, 7, 8)
            createdAt(created.minusSeconds(1), 9)
            val first = bindings.findByStatusIn(listOf(CaseResourceStatus.READY), 3)
            first.map { it.id } shouldBe listOf(UUID(0, 9), UUID(0, 1), UUID(0, 2))
            val cursor = CaseResourceBindingCursor.after(first.last())
            first.forEach { bindings.delete(it.id) }
            val second = bindings.findByStatusIn(listOf(CaseResourceStatus.READY), 3, cursor)
            second.map { it.id } shouldBe listOf(UUID(0, 3), UUID(0, 4), UUID(0, 5))
            bindings.markStatus(second.last().id, CaseResourceStatus.REMOVED)
            val third = bindings.findByStatusIn(listOf(CaseResourceStatus.READY), 3,
                CaseResourceBindingCursor.after(second.last()))
            third.map { it.id } shouldBe listOf(UUID(0, 6))
            bindings.findByStatusIn(listOf(CaseResourceStatus.READY), 3,
                CaseResourceBindingCursor.after(third.last())) shouldBe emptyList()
        }

        "bounded pages reach bindings beyond the former oldest ten thousand prefix" {
            val expected = (1L..10_007L).map { UUID(0, it) }
            driver.session().use { session ->
                session.run(
                    """
                    UNWIND ${'$'}ids AS id
                    CREATE (:CaseResourceBinding:ActiveCaseResourceBinding {
                        id: id, rootCaseId: id,
                        namespaceId: ${'$'}namespace, integrationConfigId: ${'$'}config,
                        status: 'READY', created: datetime('2026-01-01T00:00:00Z'),
                        modified: datetime('2026-01-01T00:00:00Z')
                    })
                    """.trimIndent(),
                    mapOf("ids" to expected.map { it.toString() }, "namespace" to UUID.randomUUID().toString(),
                        "config" to UUID.randomUUID().toString()),
                ).consume()
            }
            val first = bindings.findByStatusIn(listOf(CaseResourceStatus.READY), 5_000)
            val second = bindings.findByStatusIn(listOf(CaseResourceStatus.READY), 5_000,
                CaseResourceBindingCursor.after(first.last()))
            val third = bindings.findByStatusIn(listOf(CaseResourceStatus.READY), 5_000,
                CaseResourceBindingCursor.after(second.last()))
            listOf(first.size, second.size, third.size) shouldBe listOf(5_000, 5_000, 7)
            (first + second + third).map { it.id } shouldBe expected
        }

        "a first page seeks on status and the next pages walk the creation order without a sort" {
            // Operator and index of each step of the plan Neo4j chooses for a repository query.
            fun plan(method: String, params: Map<String, Any>): List<String> {
                val query =
                    CaseResourceBindingNodeNeo4jRepository::class.java.methods
                        .single { it.name == method }
                        .getAnnotation(Query::class.java)
                        .value
                val root = driver.session().use { it.run("EXPLAIN $query", params).consume().plan() }
                return generateSequence(listOf(root)) { level -> level.flatMap { it.children() }.ifEmpty { null } }
                    .flatten()
                    .map { "${it.operatorType().substringBefore('@')} ${it.arguments()["Details"]?.asString()}" }
                    .toList()
            }
            val statuses = CaseResourceStatus.entries.filter { it != CaseResourceStatus.REMOVED }.map { it.name }
            val first = plan("findActiveByStatusIn", mapOf("statuses" to statuses, "limit" to 5))
            first.single { it.startsWith("NodeIndexSeek") } shouldContain "(status, created)"
            val next =
                plan(
                    "findActiveByStatusInAfter",
                    mapOf(
                        "statuses" to statuses,
                        "limit" to 5,
                        "afterCreated" to Instant.parse("2026-01-01T00:00:00Z").atZone(ZoneOffset.UTC),
                        "afterId" to UUID(0, 1).toString(),
                    ),
                )
            next.single { it.startsWith("NodeIndexSeek") } shouldContain "(created, id)"
            next.map { it.substringBefore(' ') } shouldNotContainAnyOf listOf("Sort", "PartialSort", "Top", "PartialTop")
        }
    }
}
