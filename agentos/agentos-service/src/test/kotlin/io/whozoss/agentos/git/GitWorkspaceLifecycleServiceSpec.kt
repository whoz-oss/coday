package io.whozoss.agentos.git

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.data.forAll
import io.kotest.data.headers
import io.kotest.data.row
import io.kotest.data.table
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import io.whozoss.agentos.exception.ConflictException
import java.util.UUID

class GitWorkspaceLifecycleServiceSpec : StringSpec({
    fun failed(
        bindings: InMemoryCaseResourceBindingService,
        setupStarted: Boolean,
        setupCompleted: Boolean,
    ): CaseResourceBinding =
        bindings.create(CaseResourceBinding(
            rootCaseId = UUID.randomUUID(), namespaceId = UUID.randomUUID(), integrationConfigId = UUID.randomUUID(),
            status = CaseResourceStatus.FAILED, failureReason = "Cannot fetch the case base.",
            setupStarted = setupStarted, setupCompleted = setupCompleted,
        ))

    fun lifecycle(bindings: CaseResourceBindingService) =
        GitWorkspaceLifecycleService(bindings, mockk(), mockk(), mockk(), mockk(), mockk(), mockk())

    "acknowledging a setup replay is refused when no setup was interrupted" {
        table(
            headers("setupStarted", "setupCompleted"),
            row(false, false),
            row(true, true),
        ).forAll { started, completed ->
            val bindings = InMemoryCaseResourceBindingService()
            val binding = failed(bindings, setupStarted = started, setupCompleted = completed)

            shouldThrow<ConflictException> { lifecycle(bindings).acknowledgeSetup(binding.rootCaseId) }.message shouldBe
                "No interrupted setup to acknowledge: retry the preparation instead"
            bindings.findByRootCaseId(binding.rootCaseId) shouldBe binding
        }
    }

    "acknowledging an interrupted setup requests a new preparation that runs it again" {
        val bindings = InMemoryCaseResourceBindingService()
        val binding = failed(bindings, setupStarted = true, setupCompleted = false)

        val requested = lifecycle(bindings).acknowledgeSetup(binding.rootCaseId)

        requested.status shouldBe CaseResourceStatus.REQUESTED
        requested.failureReason shouldBe null
        requested.setupStarted shouldBe false
        requested.setupCompleted shouldBe false
    }
})
