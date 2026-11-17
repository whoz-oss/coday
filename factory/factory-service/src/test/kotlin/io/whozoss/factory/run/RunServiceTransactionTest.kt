package io.whozoss.factory.run

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.whozoss.factory.persistence.TenantScope
import io.whozoss.factory.run.domain.RunRecord
import io.whozoss.factory.run.persistence.RunRepository
import io.whozoss.factory.run.service.RunService
import io.whozoss.factory.sdk.spi.RunUpdate
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.SimpleTransactionStatus

class RunServiceTransactionTest {
    private val scope = TenantScope("org", "ws")
    private val runs = mockk<RunRepository>()
    private val manager = mockk<PlatformTransactionManager>(relaxed = true)
    private val status = SimpleTransactionStatus()

    private fun service(transactional: Boolean = true): RunService {
        every { manager.getTransaction(any()) } returns status
        return RunService(
            runRepository = runs,
            workflowRepository = mockk(),
            evidenceRepository = mockk(),
            interactionRepository = mockk(),
            sessionDefinitionCatalog = mockk(),
            transactionManager = if (transactional) manager else null,
        )
    }

    @Test
    fun `missing run returns null and commits rather than asserting the nullable value`() {
        every { runs.find(scope, "ns", "run") } returns null

        val result = service().applyStepValidationUpdate(
            scope, "ns", "run", "fragment", RunUpdate(newDomainState = mapOf("after" to true)),
        )

        assertThat(result).isNull()
        verify(exactly = 1) {
            manager.getTransaction(match { it.propagationBehavior == TransactionDefinition.PROPAGATION_REQUIRES_NEW })
            manager.commit(status)
        }
        verify(exactly = 0) { manager.rollback(any()) }
    }

    @Test
    fun `non-null run survives the transaction wrapper`() {
        val run = RunRecord("ns", "run", "type", "1", "Run")
        every { runs.find(scope, "ns", "run") } returns run
        every { runs.update(scope, "ns", "run", run.revision, any()) } returns true

        val result = service().applyStepValidationUpdate(
            scope, "ns", "run", "fragment",
            RunUpdate(newDomainState = mapOf("after" to true), expectedDomainRevision = run.domainRevision),
        )

        assertThat(result).isEqualTo(run.copy(
            revision = run.revision + 1,
            domainRevision = run.domainRevision + 1,
            amendmentSeq = run.amendmentSeq + 1,
            domainState = mapOf("after" to true),
        ))
        verify(exactly = 1) { manager.commit(status) }
        verify(exactly = 0) { manager.rollback(any()) }
    }

    @Test
    fun `callback exception propagates and rolls back`() {
        val failure = IllegalStateException("read failed")
        every { runs.find(scope, "ns", "run") } throws failure

        assertThatThrownBy {
            service().applyStepValidationUpdate(
                scope, "ns", "run", "fragment", RunUpdate(newDomainState = emptyMap()),
            )
        }.isSameAs(failure)
        verify(exactly = 1) { manager.rollback(status) }
        verify(exactly = 0) { manager.commit(any()) }
    }

    @Test
    fun `inline fallback also preserves null`() {
        every { runs.find(scope, "ns", "run") } returns null

        assertThat(service(transactional = false).applyStepValidationUpdate(
            scope, "ns", "run", "fragment", RunUpdate(newDomainState = emptyMap()),
        )).isNull()
        verify(exactly = 0) { manager.getTransaction(any()) }
    }
}
