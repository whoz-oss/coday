package io.whozoss.factory.workflow.domain

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GitayAgentDefinitionTest {
    @Test
    fun `Gitay exposes only status branch creation and structured Factory result`() {
        val root = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()
        val file = generateSequence(root) { it.parent }
            .map { it.resolve("agents/Gitay.yaml") }
            .first { java.nio.file.Files.isRegularFile(it) }
        val raw = ObjectMapper(YAMLFactory()).readValue(file.toFile(), Map::class.java)
        @Suppress("UNCHECKED_CAST")
        val integrations = raw["integrations"] as Map<String, List<String>>

        assertEquals(setOf("GIT", "FACTORY_WORKER"), integrations.keys)
        assertEquals(listOf("git_status", "git_create_branch"), integrations["GIT"])
        assertEquals(listOf("submit_step_result"), integrations["FACTORY_WORKER"])
        assertFalse(integrations.values.flatten().any { it.contains("commit") || it.contains("push") || it.contains("fetch") })
        val instructions = raw["instructions"] as String
        assertTrue(instructions.contains("initially detached HEAD is the normal AgentOS workspace state"))
        assertTrue(instructions.contains("final detached HEAD"))
    }
}
