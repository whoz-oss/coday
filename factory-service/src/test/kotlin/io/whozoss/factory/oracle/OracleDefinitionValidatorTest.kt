package io.whozoss.factory.oracle

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.whozoss.factory.oracle.domain.InvalidOracleDefinitionException
import io.whozoss.factory.oracle.domain.OracleDefinitionValidator
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * Unit tests of the strict oracle-definition validation rules — the port of
 * `factory/src/domain/oracle/oracle-definition.ts`. No Spring, no Docker.
 */
class OracleDefinitionValidatorTest {

    private val mapper = jacksonObjectMapper()

    private fun validDefinition(): Map<String, Any?> = linkedMapOf(
        "schemaVersion" to "1",
        "id" to "node-smoke",
        "version" to "1.0.0",
        "domain" to "factory",
        "argv" to listOf("node", "factory/tests/fixtures/oracle-process/pass.mjs"),
        "cwd" to "repo-root",
        "timeoutMs" to 10_000,
        "success" to linkedMapOf("rule" to "exit-code", "requireWork" to true),
        "applicable" to linkedMapOf(
            "workflowTypes" to listOf("oracle-smoke"),
            "stepIds" to listOf("verify-code"),
        ),
    )

    private fun validate(definition: Map<String, Any?>) =
        OracleDefinitionValidator.validate(definition, mapper)

    private fun assertInvalid(definition: Map<String, Any?>) {
        assertThatThrownBy { validate(definition) }
            .isInstanceOf(InvalidOracleDefinitionException::class.java)
            .extracting("errorCode")
            .isEqualTo("INVALID_ORACLE_DEFINITION")
    }

    @Test
    fun `accepts a well-formed definition`() {
        val definition = validate(validDefinition())
        assertThat(definition.id).isEqualTo("node-smoke")
        assertThat(definition.version).isEqualTo("1.0.0")
        assertThat(definition.identity).isEqualTo("node-smoke@1.0.0")
        assertThat(definition.cwd).isEqualTo("repo-root")
        assertThat(definition.timeoutMs).isEqualTo(10_000)
        assertThat(definition.success.rule).isEqualTo("exit-code")
        assertThat(definition.applicable.stepIds).containsExactly("verify-code")
    }

    @Test
    fun `rejects an unknown top-level field`() {
        assertInvalid(validDefinition().plus("extra" to "nope"))
    }

    @Test
    fun `rejects a wrong schema version`() {
        assertInvalid(validDefinition().plus("schemaVersion" to "2"))
    }

    @Test
    fun `rejects an invalid id`() {
        assertInvalid(validDefinition().plus("id" to "-leading-dash"))
    }

    @Test
    fun `rejects an invalid version`() {
        assertInvalid(validDefinition().plus("version" to "1.0"))
    }

    @Test
    fun `rejects an empty argv`() {
        assertInvalid(validDefinition().plus("argv" to emptyList<String>()))
    }

    @Test
    fun `rejects a non-string argument`() {
        assertInvalid(validDefinition().plus("argv" to listOf("node", 42)))
    }

    @Test
    fun `rejects an argument containing a newline`() {
        assertInvalid(validDefinition().plus("argv" to listOf("node", "a\nb")))
    }

    @Test
    fun `rejects a shell interpreter as executable`() {
        assertInvalid(validDefinition().plus("argv" to listOf("bash", "-c", "echo hi")))
        assertInvalid(validDefinition().plus("argv" to listOf("/bin/sh", "script.sh")))
        assertInvalid(validDefinition().plus("argv" to listOf("C:\\Windows\\System32\\cmd.exe", "/c", "dir")))
    }

    @Test
    fun `rejects shell-evaluation flags`() {
        assertInvalid(validDefinition().plus("argv" to listOf("node", "-c", "code")))
        assertInvalid(validDefinition().plus("argv" to listOf("node", "--command")))
        assertInvalid(validDefinition().plus("argv" to listOf("node", "/c")))
    }

    @Test
    fun `rejects a cwd other than repo-root`() {
        assertInvalid(validDefinition().plus("cwd" to "/tmp"))
    }

    @Test
    fun `rejects a timeout outside the accepted range`() {
        assertInvalid(validDefinition().plus("timeoutMs" to 0))
        assertInvalid(validDefinition().plus("timeoutMs" to 3_600_001))
        assertInvalid(validDefinition().plus("timeoutMs" to 1.5))
    }

    @Test
    fun `rejects a success rule other than exit-code`() {
        assertInvalid(validDefinition().plus("success" to linkedMapOf("rule" to "stdout", "requireWork" to true)))
    }

    @Test
    fun `rejects a non-boolean requireWork`() {
        assertInvalid(validDefinition().plus("success" to linkedMapOf("rule" to "exit-code", "requireWork" to "yes")))
    }

    @Test
    fun `rejects an empty applicable list`() {
        assertInvalid(
            validDefinition().plus(
                "applicable" to linkedMapOf("workflowTypes" to emptyList<String>(), "stepIds" to listOf("verify-code")),
            ),
        )
    }

    @Test
    fun `rejects an invalid applicable entry`() {
        assertInvalid(
            validDefinition().plus(
                "applicable" to linkedMapOf("workflowTypes" to listOf("oracle-smoke"), "stepIds" to listOf("a b")),
            ),
        )
    }

    @Test
    fun `derives the identity from a file name`() {
        assertThat(OracleDefinitionValidator.identityFromFileName("node-smoke@1.0.0.json"))
            .isEqualTo("node-smoke@1.0.0")
        assertThat(OracleDefinitionValidator.identityFromFileName("dir/node-smoke@1.0.0.json"))
            .isEqualTo("node-smoke@1.0.0")
    }

    @Test
    fun `hashes are stable and insensitive to key order`() {
        val first = validate(validDefinition())
        val reordered = validate(
            linkedMapOf(
                "timeoutMs" to 10_000,
                "version" to "1.0.0",
                "argv" to listOf("node", "factory/tests/fixtures/oracle-process/pass.mjs"),
                "domain" to "factory",
                "id" to "node-smoke",
                "applicable" to linkedMapOf(
                    "stepIds" to listOf("verify-code"),
                    "workflowTypes" to listOf("oracle-smoke"),
                ),
                "success" to linkedMapOf("requireWork" to true, "rule" to "exit-code"),
                "cwd" to "repo-root",
                "schemaVersion" to "1",
            ),
        )
        val hash = OracleDefinitionValidator.hash(first, mapper)
        assertThat(hash).startsWith("sha256:")
        assertThat(OracleDefinitionValidator.hash(reordered, mapper)).isEqualTo(hash)
    }
}
