package io.whozoss.agentos.persistence.neo4j

import io.kotest.core.spec.style.StringSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import io.whozoss.agentos.git.CaseResourceBinding
import io.whozoss.agentos.git.CaseResourceBindingCursor
import io.whozoss.agentos.git.CaseResourceBindingService
import io.whozoss.agentos.git.CaseResourceStatus
import io.whozoss.agentos.sdk.entity.EntityMetadata
import org.neo4j.driver.Driver
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import java.time.Instant
import java.util.UUID

@SpringBootTest
@ActiveProfiles("test", "embedded-neo4j")
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
            fun row(id: Long, time: Instant = created, status: CaseResourceStatus = CaseResourceStatus.READY) =
                CaseResourceBinding(metadata = EntityMetadata(id = UUID(0, id), created = time),
                    rootCaseId = UUID.randomUUID(), namespaceId = namespace,
                    integrationConfigId = UUID.randomUUID(), status = status)
            val rows = listOf(row(6), row(2), row(4), row(1), row(3), row(5))
            rows.forEach { bindings.create(it) }
            bindings.create(row(7, status = CaseResourceStatus.FAILED))
            bindings.create(row(8)).also { bindings.delete(it.id) }
            // An earlier timestamp sorts first even with an ID above every other row.
            bindings.create(row(9, created.minusSeconds(1)))
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
                    CREATE (:CaseResourceBinding {
                        id: id, rootCaseId: id, activeRootCaseKey: id,
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
    }
}
