package io.whozoss.factory.workflow.domain

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertFalse
import kotlin.test.assertNull

class AccessibilityTicketDefinitionTest {
    @Test
    fun `bundled 1_3 delegates branch preparation to Gitay before frontend editing`() {
        val stream = requireNotNull(javaClass.getResourceAsStream("/sessions/accessibility-ticket-1.3.0.json"))
        val raw = jacksonObjectMapper().readValue(stream, Map::class.java)
        val valid = assertIs<WorkflowDefinitionValidation.Valid>(WorkflowDefinitionValidator.validate(raw))
        val architect = valid.steps.single { it.id == "architect-analyse" }
        val gitay = valid.steps.single { it.id == "gitay-prepare-branch" }
        val frontend = valid.steps.single { it.id == "frontend-implement" }
        val reviewer = valid.steps.single { it.id == "a11y-audit" }

        assertEquals("1.3.0", valid.definition["version"])
        assertEquals(listOf(architect.id), gitay.dependsOn)
        assertEquals("agent", gitay.responsibility.kind.wire)
        assertEquals("Gitay", gitay.responsibility.name)
        assertEquals(listOf(gitay.id), frontend.dependsOn)
        assertEquals("A11yReviewer", reviewer.responsibility.name)
        assertEquals(listOf("frontend-build"), reviewer.dependsOn)
        val build = valid.steps.single { it.id == "frontend-build" }
        assertEquals(listOf(frontend.id), build.dependsOn)
        assertFalse(valid.steps.any { it.id == "frontend-tests" || it.responsibility.name == "frontend-tests" })
        assertFalse(valid.steps.any { it.responsibility.name == "prepare-work-branch" })
    }

    @Test
    fun `bundled 1_2 prepares the existing worktree branch before frontend editing`() {
        val stream = requireNotNull(javaClass.getResourceAsStream("/sessions/accessibility-ticket-1.2.0.json"))
        val raw = jacksonObjectMapper().readValue(stream, Map::class.java)
        val valid = assertIs<WorkflowDefinitionValidation.Valid>(WorkflowDefinitionValidator.validate(raw))
        val architect = valid.steps.single { it.id == "architect-analyse" }
        val preparation = valid.steps.single { it.id == "prepare-work-branch" }
        val frontend = valid.steps.single { it.id == "frontend-implement" }
        assertEquals(listOf(architect.id), preparation.dependsOn)
        assertEquals("code", preparation.responsibility.kind.wire)
        assertEquals("prepare-work-branch", preparation.responsibility.name)
        assertEquals(listOf(preparation.id), frontend.dependsOn)
    }

    @Test
    fun `bundled 1_1 workflow builds before tests`() {
        val stream = requireNotNull(javaClass.getResourceAsStream("/sessions/accessibility-ticket-1.1.0.json"))
        val raw = jacksonObjectMapper().readValue(stream, Map::class.java)
        val valid = assertIs<WorkflowDefinitionValidation.Valid>(WorkflowDefinitionValidator.validate(raw))
        assertFalse(valid.definition.containsKey("execution"))
        assertNull(executionPolicyOf(valid.definition))
        val build = valid.steps.single { it.id == "frontend-build" }
        val tests = valid.steps.single { it.id == "frontend-tests" }

        assertEquals(listOf("frontend-implement"), build.dependsOn)
        assertEquals(listOf("frontend-build"), tests.dependsOn)
        assertEquals("frontend-build", build.responsibility.name)
        assertEquals("frontend-tests", tests.responsibility.name)
    }
}
