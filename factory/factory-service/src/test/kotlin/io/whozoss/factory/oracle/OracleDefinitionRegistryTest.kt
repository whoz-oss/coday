package io.whozoss.factory.oracle

import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.factory.oracle.domain.DuplicateOracleIdException
import io.whozoss.factory.oracle.domain.InvalidOracleDefinitionException
import io.whozoss.factory.oracle.domain.OraclePathIdentityMismatchException
import io.whozoss.factory.oracle.registry.OracleDefinitionRegistry
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Registry loading and invariant tests. Filesystem + Jackson only: no Spring and
 * no Docker, so these run everywhere.
 */
class OracleDefinitionRegistryTest {

    private val mapper = ObjectMapper()

    private fun definitionJson(id: String, version: String): String = """
        {
          "schemaVersion": "1",
          "id": "$id",
          "version": "$version",
          "domain": "factory",
          "argv": ["node", "script.mjs"],
          "cwd": "repo-root",
          "timeoutMs": 10000,
          "success": { "rule": "exit-code", "requireWork": true },
          "applicable": { "workflowTypes": ["type-a"], "stepIds": ["step-a"] }
        }
    """.trimIndent()

    private fun registry(root: Path): OracleDefinitionRegistry =
        OracleDefinitionRegistry(root.toAbsolutePath().toString(), mapper).also { it.initialize() }

    @Test
    fun `loads and exposes definitions`(@TempDir root: Path) {
        Files.writeString(root.resolve("node-smoke@1.0.0.json"), definitionJson("node-smoke", "1.0.0"))
        Files.writeString(root.resolve("other@2.1.0.json"), definitionJson("other", "2.1.0"))

        val registry = registry(root)

        assertThat(registry.list()).hasSize(2)
        assertThat(registry.get("node-smoke")?.version).isEqualTo("1.0.0")
        assertThat(registry.get("other")?.version).isEqualTo("2.1.0")
        assertThat(registry.get("missing")).isNull()
    }

    @Test
    fun `rejects a duplicate oracle id`(@TempDir root: Path) {
        Files.writeString(root.resolve("node-smoke@1.0.0.json"), definitionJson("node-smoke", "1.0.0"))
        Files.writeString(root.resolve("node-smoke@2.0.0.json"), definitionJson("node-smoke", "2.0.0"))

        assertThatThrownBy { registry(root) }
            .isInstanceOf(DuplicateOracleIdException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", "DUPLICATE_ORACLE_ID")
    }

    @Test
    fun `rejects a file name that does not match the definition identity`(@TempDir root: Path) {
        Files.writeString(root.resolve("wrong-name.json"), definitionJson("node-smoke", "1.0.0"))

        assertThatThrownBy { registry(root) }
            .isInstanceOf(OraclePathIdentityMismatchException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", "ORACLE_PATH_IDENTITY_MISMATCH")
    }

    @Test
    fun `rejects an invalid definition file`(@TempDir root: Path) {
        Files.writeString(root.resolve("node-smoke@1.0.0.json"), """{ "schemaVersion": "1" }""")

        assertThatThrownBy { registry(root) }
            .isInstanceOf(InvalidOracleDefinitionException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", "INVALID_ORACLE_DEFINITION")
    }

    @Test
    fun `is empty and does not fail when the root is absent`(@TempDir root: Path) {
        val registry = registry(root.resolve("missing"))

        assertThat(registry.list()).isEmpty()
        assertThat(registry.get("node-smoke")).isNull()
    }

    @Test
    fun `ignores non-json files`(@TempDir root: Path) {
        Files.writeString(root.resolve("README.md"), "not an oracle")
        Files.writeString(root.resolve("node-smoke@1.0.0.json"), definitionJson("node-smoke", "1.0.0"))

        assertThat(registry(root).list()).hasSize(1)
    }
}
