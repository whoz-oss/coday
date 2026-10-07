package io.whozoss.factory.workflow.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Unit tests of the strict, recursively key-sorted canonical hashing.
 *
 * The expected digests are the output of the Node implementation
 * (`JSON.stringify(canonicalize(value))` then
 * `createHash('sha256').update(json, 'utf8').digest('hex')`), so a Kotlin change
 * that reorders keys, drops `null`s or adds whitespace breaks the cross-language
 * contract these vectors lock in.
 */
class CanonicalHashTest {

    private val definitionHash = "a".repeat(64)

    @Test
    fun `canonicalize sorts object keys recursively and keeps array order`() {
        val value = linkedMapOf<String, Any?>(
            "zeta" to 1,
            "alpha" to linkedMapOf<String, Any?>("y" to 2, "b" to listOf(3, 1, 2)),
            "beta" to null,
        )
        assertThat(CanonicalHash.canonicalizeJson(value))
            .isEqualTo("""{"alpha":{"b":[3,1,2],"y":2},"beta":null,"zeta":1}""")
    }

    @Test
    fun `key order never affects the canonical digest`() {
        val ordered = linkedMapOf<String, Any?>("a" to 1, "b" to linkedMapOf<String, Any?>("c" to 2, "d" to 3))
        val shuffled = linkedMapOf<String, Any?>("b" to linkedMapOf<String, Any?>("d" to 3, "c" to 2), "a" to 1)
        assertThat(CanonicalHash.canonicalHash(ordered)).isEqualTo(CanonicalHash.canonicalHash(shuffled))
    }

    @Test
    fun `workflowStartCommandHash matches the Node vector`() {
        val command = WorkflowStartCommand(
            workflowId = "wf-canon-1",
            workflowType = "bmad-story",
            title = "Canonical demo",
        )
        val definition = WorkflowDefinitionInput(
            workflowType = "bmad-story",
            version = "1.0.0",
            definitionHash = definitionHash,
            steps = emptyList(),
        )
        assertThat(CanonicalHash.workflowStartCommandHash(command, definition))
            .isEqualTo("9070d92aa682a093ed923f7a573120f82cd5060e672da8f3bfa1491521f35c09")
    }

    @Test
    fun `workflow start identity includes initial request text but ignores observation metadata`() {
        val definition = WorkflowDefinitionInput("demo", "1", definitionHash, emptyList())
        val first = WorkflowStartCommand(
            "wf", "demo", "Title",
            controllerRequest = ControllerRequestInput("Analyse only", "ns", "2026-01-01T00:00:00Z", "alice", "create-run"),
        )
        val retry = first.copy(
            controllerRequest = ControllerRequestInput("Analyse only", "ns", "2026-01-02T00:00:00Z", "alice", "create-run"),
        )
        val changed = first.copy(
            controllerRequest = ControllerRequestInput("Implement it", "ns", "2026-01-02T00:00:00Z", "alice", "create-run"),
        )

        assertThat(CanonicalHash.workflowStartCommandHash(first, definition))
            .isEqualTo(CanonicalHash.workflowStartCommandHash(retry, definition))
            .isNotEqualTo(CanonicalHash.workflowStartCommandHash(changed, definition))
    }

    @Test
    fun `workflowProjectionHash matches the Node vector`() {
        val projection = linkedMapOf<String, Any?>(
            "schemaVersion" to "2",
            "workflowId" to "wf-canon-1",
            "workflowType" to "bmad-story",
            "title" to "Canonical demo",
            "status" to "ready",
            "steps" to listOf(
                linkedMapOf(
                    "id" to "a",
                    "name" to "A",
                    "status" to "ready",
                    "dependsOn" to emptyList<String>(),
                    "responsibility" to linkedMapOf("kind" to "agent", "name" to "dev"),
                ),
                linkedMapOf(
                    "id" to "b",
                    "name" to "B",
                    "status" to "pending",
                    "dependsOn" to listOf("a"),
                    "responsibility" to linkedMapOf("kind" to "human", "name" to "review"),
                ),
            ),
        )
        assertThat(CanonicalHash.workflowProjectionHash(projection))
            .isEqualTo("53e1ebed55f0e816505c4cd5ea1bdde1a4d83cc9481ba11ca91843e16e367e60")
    }

    @Test
    fun `workflowDefinition validation normalizes and hashes deterministically`() {
        val input = linkedMapOf<String, Any?>(
            "schemaVersion" to "1",
            "workflowType" to "bmad-story",
            "version" to "1.0.0",
            "title" to "Story",
            "steps" to listOf(
                linkedMapOf(
                    "id" to "a",
                    "name" to "A",
                    "responsibility" to linkedMapOf("kind" to "agent", "name" to "dev"),
                    "dependsOn" to emptyList<String>(),
                ),
            ),
        )
        val first = WorkflowDefinitionValidator.validate(input) as WorkflowDefinitionValidation.Valid
        val second = WorkflowDefinitionValidator.validate(input) as WorkflowDefinitionValidation.Valid
        assertThat(hashWorkflowDefinition(first.definition)).isEqualTo(hashWorkflowDefinition(second.definition))
        assertThat(canonicalizeWorkflowDefinition(first.definition)).contains("\"workflowType\":\"bmad-story\"")
    }
}
