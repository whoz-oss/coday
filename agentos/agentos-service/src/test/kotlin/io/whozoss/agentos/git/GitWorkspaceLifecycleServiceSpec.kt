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
        setup: SetupState,
    ): CaseResourceBinding =
        bindings.create(CaseResourceBinding(
            rootCaseId = UUID.randomUUID(), namespaceId = UUID.randomUUID(), integrationConfigId = UUID.randomUUID(),
            status = CaseResourceStatus.FAILED, failureReason = "Cannot fetch the case base.",
            setup = setup,
        ))

    fun lifecycle(bindings: CaseResourceBindingService) =
        GitWorkspaceLifecycleService(bindings, mockk(), mockk(), mockk(), mockk(), mockk(), mockk())

    "acknowledging a setup replay is refused when no setup was interrupted" {
        table(
            headers("setup"),
            row(SetupState.NOT_STARTED),
            row(SetupState.COMPLETED),
        ).forAll { setup ->
            val bindings = InMemoryCaseResourceBindingService()
            val binding = failed(bindings, setup)

            shouldThrow<ConflictException> { lifecycle(bindings).acknowledgeSetup(binding.rootCaseId) }.message shouldBe
                "No interrupted setup to acknowledge: retry the preparation instead"
            bindings.findByRootCaseId(binding.rootCaseId) shouldBe binding
        }
    }

    "acknowledging an interrupted setup requests a new preparation that runs it again" {
        val bindings = InMemoryCaseResourceBindingService()
        val binding = failed(bindings, SetupState.STARTED)

        val requested = lifecycle(bindings).acknowledgeSetup(binding.rootCaseId)

        requested.status shouldBe CaseResourceStatus.REQUESTED
        requested.failureReason shouldBe null
        requested.setup shouldBe SetupState.NOT_STARTED
    }
})
