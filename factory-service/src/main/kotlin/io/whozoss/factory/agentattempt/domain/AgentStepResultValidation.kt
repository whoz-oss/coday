package io.whozoss.factory.agentattempt.domain

import com.fasterxml.jackson.databind.JsonNode

/**
 * Pure validation of the structured business result schema.
 *
 * Faithful port of `validateAgentStepResultBusiness` in
 * `factory/src/domain/agent-attempt/agent-step-result.ts`. The function is
 * deliberately strict: any unknown field at any level, a missing required
 * field or a value exceeding one of [AgentStepResultLimits] rejects the result.
 *
 * The validator works on the raw Jackson tree so the canonical hash of the
 * accepted payload is byte-for-byte the payload the worker submitted.
 */
object AgentStepResultValidation {

    private val BUSINESS_FIELDS = setOf("status", "summary", "artifacts", "claims", "findings")
    private val ARTIFACT_FIELDS = setOf("kind", "encoding", "content")
    private val FINDING_FIELDS = setOf("severity", "code", "summary", "file", "line")
    private val STATUSES = setOf("PASS", "FAIL")

    /** True when [value] is a structurally valid business result. */
    fun validateBusiness(value: JsonNode?): Boolean {
        if (value == null || !value.isObject) return false
        if (value.fieldNames().asSequence().any { it !in BUSINESS_FIELDS }) return false

        val status = value.get("status")?.takeIf { it.isTextual }?.asText()
        if (status !in STATUSES) return false

        val summary = value.get("summary")?.takeIf { it.isTextual }?.asText() ?: return false
        if (summary.isEmpty() || summary.length > AgentStepResultLimits.SUMMARY) return false

        val claims = value.get("claims") ?: return false
        if (!claims.isObject) return false
        if (claims.fieldNames().asSequence().any { it != "modifiedFiles" }) return false
        val modifiedFiles = claims.get("modifiedFiles")
        if (modifiedFiles == null || !modifiedFiles.isArray) return false
        if (modifiedFiles.size() > AgentStepResultLimits.MODIFIED_FILES) return false
        if (modifiedFiles.any { file ->
                !file.isTextual ||
                    file.asText().isEmpty() ||
                    file.asText().length > AgentStepResultLimits.MODIFIED_FILE_LENGTH
            }
        ) {
            return false
        }

        val artifacts = value.get("artifacts")
        if (artifacts != null && !isValidArtifacts(artifacts)) return false

        val findings = value.get("findings")
        if (findings != null && !isValidFindings(findings)) return false

        return true
    }

    private fun isValidArtifacts(artifacts: JsonNode): Boolean {
        if (!artifacts.isArray || artifacts.size() > AgentStepResultLimits.ARTIFACTS) return false
        return artifacts.all { artifact ->
            artifact.isObject &&
                artifact.fieldNames().asSequence().none { it !in ARTIFACT_FIELDS } &&
                (artifact.get("kind")?.takeIf { it.isTextual }?.asText()?.let {
                    it.isNotEmpty() && it.length <= AgentStepResultLimits.ARTIFACT_KIND
                } ?: false) &&
                artifact.get("encoding")?.asText() == "markdown" &&
                (artifact.get("content")?.takeIf { it.isTextual }?.asText()?.let {
                    it.isNotEmpty() &&
                        it.toByteArray(Charsets.UTF_8).size <= AgentStepResultLimits.ARTIFACT_CONTENT_BYTES
                } ?: false)
        }
    }

    private fun isValidFindings(findings: JsonNode): Boolean {
        if (!findings.isArray || findings.size() > AgentStepResultLimits.FINDINGS) return false
        return findings.all { finding ->
            if (!finding.isObject) return@all false
            if (finding.fieldNames().asSequence().any { it !in FINDING_FIELDS }) return@all false
            if (AgentStepResultSeverity.fromWire(finding.get("severity")?.asText()) == null) return@all false
            val code = finding.get("code")?.takeIf { it.isTextual }?.asText() ?: return@all false
            if (code.isEmpty() || code.length > AgentStepResultLimits.FINDING_CODE) return@all false
            val summary = finding.get("summary")?.takeIf { it.isTextual }?.asText() ?: return@all false
            if (summary.isEmpty() || summary.length > AgentStepResultLimits.FINDING_SUMMARY) return@all false
            val file = finding.get("file")
            if (file != null && (!file.isTextual || file.asText().isEmpty() ||
                    file.asText().length > AgentStepResultLimits.FINDING_FILE)
            ) {
                return@all false
            }
            val line = finding.get("line")
            if (line != null && (!line.isIntegralNumber || line.asLong() < 1)) return@all false
            true
        }
    }

    /**
     * Maps an already validated business node onto typed models.
     *
     * The caller must have checked [validateBusiness] first; malformed nodes are
     * rejected with an [IllegalArgumentException].
     */
    fun parseBusiness(value: JsonNode): AgentStepResultBusiness {
        require(validateBusiness(value)) { "The structured business result is invalid" }
        val artifacts = value.get("artifacts")
            ?.map { artifact ->
                AgentStepResultArtifact(
                    kind = artifact.get("kind").asText(),
                    encoding = artifact.get("encoding").asText(),
                    content = artifact.get("content").asText(),
                )
            }
            ?: emptyList()
        val findings = value.get("findings")
            ?.map { finding ->
                AgentStepResultFinding(
                    severity = finding.get("severity").asText(),
                    code = finding.get("code").asText(),
                    summary = finding.get("summary").asText(),
                    file = finding.get("file")?.asText(),
                    line = finding.get("line")?.asInt(),
                )
            }
            ?: emptyList()
        return AgentStepResultBusiness(
            status = AgentStepResultStatus.fromWire(value.get("status").asText())!!,
            summary = value.get("summary").asText(),
            claims = AgentStepResultClaim(
                modifiedFiles = value.get("claims").get("modifiedFiles").map { it.asText() },
            ),
            artifacts = artifacts,
            findings = findings,
        )
    }
}
