package io.whozoss.factory.oracle.domain

/**
 * Fixed executable contract of an oracle.
 *
 * Port of `factory/src/domain/oracle/oracle-definition.ts`. A definition
 * identifies a fixed executable contract — never a second command language:
 * `argv` is executed without a shell, so shell interpreters and shell-evaluation
 * flags are rejected by [OracleDefinitionValidator].
 */

/** A fixed executable contract with a single success rule: the exit code. */
data class OracleSuccessCondition(
    val rule: String = RULE,
    val requireWork: Boolean = true,
) {
    companion object {
        const val RULE = "exit-code"
    }
}

/** Where the oracle applies: workflow types and step ids. */
data class OracleApplicableCondition(
    val workflowTypes: List<String> = emptyList(),
    val stepIds: List<String> = emptyList(),
)

/** A validated oracle definition. */
data class OracleDefinition(
    val schemaVersion: String = SCHEMA_VERSION,
    val id: String,
    val version: String,
    val domain: String,
    val argv: List<String>,
    val cwd: String = REPO_ROOT,
    val timeoutMs: Long,
    val success: OracleSuccessCondition = OracleSuccessCondition(),
    val applicable: OracleApplicableCondition = OracleApplicableCondition(),
) {
    /** The `<id>@<version>` identity encoded in a definition file name. */
    val identity: String
        get() = "$id@$version"

    companion object {
        const val SCHEMA_VERSION = "1"
        const val REPO_ROOT = "repo-root"
    }
}
