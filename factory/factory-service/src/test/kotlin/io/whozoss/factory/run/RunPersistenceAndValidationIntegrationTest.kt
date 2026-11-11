package io.whozoss.factory.run

import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.factory.Neo4jDomainIntegrationTest
import io.whozoss.factory.run.domain.RunRecord
import io.whozoss.factory.run.persistence.RunNode
import io.whozoss.factory.run.persistence.RunRepository
import io.whozoss.factory.run.service.RunService
import io.whozoss.factory.sdk.spi.FindingSeverity
import io.whozoss.factory.sdk.spi.NewFinding
import io.whozoss.factory.sdk.spi.RunUpdate
import io.whozoss.factory.workflow.persistence.HumanInteractionRepository
import io.whozoss.factory.workflow.persistence.WorkflowEvidenceRepository
import io.whozoss.factory.workflow.persistence.WorkflowRepository
import io.whozoss.factory.workflow.service.SessionDefinitionCatalog
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

class RunPersistenceAndValidationIntegrationTest : Neo4jDomainIntegrationTest() {
    @Autowired lateinit var runRepository: RunRepository
    @Autowired lateinit var workflowRepository: WorkflowRepository
    @Autowired lateinit var evidenceRepository: WorkflowEvidenceRepository
    @Autowired lateinit var interactionRepository: HumanInteractionRepository
    @Autowired lateinit var objectMapper: ObjectMapper
    @Autowired lateinit var sessionDefinitionCatalog: SessionDefinitionCatalog

    private val namespace = "6f8a2c10-9e3b-4c5a-8a11-runmetadata01"

    private fun service() = RunService(
        runRepository = runRepository,
        workflowRepository = workflowRepository,
        evidenceRepository = evidenceRepository,
        interactionRepository = interactionRepository,
        sessionDefinitionCatalog = sessionDefinitionCatalog,
    )

    @Test
    fun `RunNode roundtrip preserves frozen flow metadata and validator applications`() {
        val original = RunRecord(
            namespaceId = namespace,
            runId = "roundtrip",
            runType = "test",
            selectorVersion = "2.1.0",
            title = "roundtrip",
            flowVersions = linkedMapOf("forge.product-spec" to "1.2.3"),
            flowDefinitionHashes = linkedMapOf("forge.product-spec" to "sha256:abc"),
            appliedValidationUpdates = linkedSetOf("validator:attempt-1:SpecValidator"),
        )

        val restored = RunNode.fromDomain(scope, original, objectMapper).toDomain(objectMapper)

        assertThat(restored.flowVersions).isEqualTo(original.flowVersions)
        assertThat(restored.flowDefinitionHashes).isEqualTo(original.flowDefinitionHashes)
        assertThat(restored.appliedValidationUpdates).isEqualTo(original.appliedValidationUpdates)
    }

    @Test
    fun `stale domain replacement is refused without mutation`() {
        runRepository.insert(scope, RunRecord(namespaceId = namespace, runId = "stale", runType = "test", selectorVersion = "1", title = "stale", domainState = mapOf("keep" to true), domainRevision = 4))

        assertThatThrownBy {
            service().applyStepValidationUpdate(scope, namespace, "stale", "fragment-1", RunUpdate(newDomainState = mapOf("new" to true), expectedDomainRevision = 3), "validator:attempt-1:v")
        }.hasMessageContaining("Stale domainState validation update")

        val persisted = runRepository.find(scope, namespace, "stale")!!
        assertThat(persisted.domainState).containsExactlyEntriesOf(mapOf("keep" to true))
        assertThat(persisted.domainRevision).isEqualTo(4)
        assertThat(persisted.appliedValidationUpdates).isEmpty()
    }

    @Test
    fun `fresh domain replacement removes absent keys`() {
        runRepository.insert(scope, RunRecord(namespaceId = namespace, runId = "replace", runType = "test", selectorVersion = "1", title = "replace", domainState = mapOf("remove" to true, "keep" to false), domainRevision = 2))

        service().applyStepValidationUpdate(scope, namespace, "replace", "fragment-1", RunUpdate(newDomainState = mapOf("keep" to true), expectedDomainRevision = 2), "validator:attempt-1:v")

        val persisted = runRepository.find(scope, namespace, "replace")!!
        assertThat(persisted.domainState).containsExactlyEntriesOf(mapOf("keep" to true))
        assertThat(persisted.domainState).doesNotContainKey("remove")
        assertThat(persisted.domainRevision).isEqualTo(3)
    }

    @Test
    fun `findings-only replay is idempotent per attempt and a new attempt is applied`() {
        runRepository.insert(scope, RunRecord(namespaceId = namespace, runId = "findings", runType = "test", selectorVersion = "1", title = "findings"))
        val update = RunUpdate(addedFindings = listOf(NewFinding(code = "SPEC", severity = FindingSeverity.MAJOR, message = "finding", stepId = "spec")))
        val runService = service()

        val first = runService.applyStepValidationUpdate(scope, namespace, "findings", "fragment-1", update, "validator:attempt-1:v")!!
        val replay = runService.applyStepValidationUpdate(scope, namespace, "findings", "fragment-1", update, "validator:attempt-1:v")!!
        val retry = runService.applyStepValidationUpdate(scope, namespace, "findings", "fragment-1", update, "validator:attempt-2:v")!!

        assertThat(replay.revision).isEqualTo(first.revision)
        assertThat(replay.findings).hasSize(1)
        assertThat(retry.revision).isEqualTo(first.revision + 1)
        assertThat(retry.findings).hasSize(2)
        assertThat(retry.domainRevision).isZero()
    }
}
