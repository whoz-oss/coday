package io.whozoss.factory.agentattempt.domain

import com.fasterxml.jackson.databind.JsonNode

/** Strict validation of the structured business result schema. */
object AgentStepResultValidation {

    private val BUSINESS_FIELDS = setOf("status", "summary", "artifacts", "claims", "findings", "expected_amendment_seq", "expectedAmendmentSeq")
    private val ARTIFACT_FIELDS = setOf("kind", "encoding", "content")
    private val CLAIM_FIELDS = setOf("modifiedFiles", "deliveryAnalysis")
    private val DELIVERY_ANALYSIS_FIELDS = setOf("changeType", "ticket", "title", "baseBranch")
    private val FINDING_FIELDS = setOf("severity", "code", "summary", "file", "line")
    private val STATUSES = setOf("PASS", "FAIL", "NEEDS_RESEARCH")
    private val CHANGE_TYPES = setOf("feature", "bugfix")
    /** GitHub issue shorthand (#123) or a generic Jira-style project key (ABC-123). */
    private val TICKET = Regex("^(?:#[1-9][0-9]*|[A-Z][A-Z0-9]{1,15}-[1-9][0-9]*)$")

    data class Violation(val path: String, val code: String, val message: String)

    /** Safe, bounded diagnostics containing schema locations only, never submitted values or unknown field names. */
    fun violations(value: JsonNode?): List<Violation> {
        val errors = mutableListOf<Violation>()
        fun add(path: String, code: String, message: String) { errors += Violation(path, code, message) }
        fun unknown(node: JsonNode, allowed: Set<String>, path: String) {
            if (node.fieldNames().asSequence().any { it !in allowed }) add(path, "UNKNOWN_FIELD", "contains one or more fields that are not allowed")
        }
        fun requiredText(node: JsonNode, field: String, path: String, max: Int): String? {
            val child = node.get(field)
            when {
                child == null -> add(path, "REQUIRED", "is required")
                !child.isTextual -> add(path, "TYPE_STRING", "must be a string")
                child.asText().isEmpty() -> add(path, "MIN_LENGTH", "must not be empty")
                child.asText().length > max -> add(path, "MAX_LENGTH", "must contain at most $max characters")
            }
            return child?.takeIf { it.isTextual }?.asText()
        }

        if (value == null || !value.isObject) return listOf(Violation("result", "TYPE_OBJECT", "must be a JSON object"))
        unknown(value, BUSINESS_FIELDS, "result")

        val status = value.get("status")
        when {
            status == null -> add("result.status", "REQUIRED", "is required")
            !status.isTextual -> add("result.status", "TYPE_STRING", "must be a string")
            status.asText() !in STATUSES -> add("result.status", "ENUM", "must be PASS, FAIL, or NEEDS_RESEARCH")
        }
        requiredText(value, "summary", "result.summary", AgentStepResultLimits.SUMMARY)

        val claims = value.get("claims")
        when {
            claims == null -> add("result.claims", "REQUIRED", "is required")
            !claims.isObject -> add("result.claims", "TYPE_OBJECT", "must be a JSON object")
            else -> {
                unknown(claims, CLAIM_FIELDS, "result.claims")
                val files = claims.get("modifiedFiles")
                when {
                    files == null -> add("result.claims.modifiedFiles", "REQUIRED", "is required")
                    !files.isArray -> add("result.claims.modifiedFiles", "TYPE_ARRAY", "must be an array")
                    files.size() > AgentStepResultLimits.MODIFIED_FILES -> add("result.claims.modifiedFiles", "MAX_ITEMS", "must contain at most ${AgentStepResultLimits.MODIFIED_FILES} items")
                    else -> files.forEachIndexed { index, file ->
                        when {
                            !file.isTextual -> add("result.claims.modifiedFiles[$index]", "TYPE_STRING", "must be a string")
                            file.asText().isEmpty() -> add("result.claims.modifiedFiles[$index]", "MIN_LENGTH", "must not be empty")
                            file.asText().length > AgentStepResultLimits.MODIFIED_FILE_LENGTH -> add("result.claims.modifiedFiles[$index]", "MAX_LENGTH", "must contain at most ${AgentStepResultLimits.MODIFIED_FILE_LENGTH} characters")
                        }
                    }
                }
                claims.get("deliveryAnalysis")?.let { delivery ->
                    if (!delivery.isObject) add("result.claims.deliveryAnalysis", "TYPE_OBJECT", "must be a JSON object")
                    else {
                        unknown(delivery, DELIVERY_ANALYSIS_FIELDS, "result.claims.deliveryAnalysis")
                        val changeType = delivery.get("changeType")
                        when {
                            changeType == null -> add("result.claims.deliveryAnalysis.changeType", "REQUIRED", "is required")
                            !changeType.isTextual || changeType.asText() !in CHANGE_TYPES -> add("result.claims.deliveryAnalysis.changeType", "ENUM", "must be feature or bugfix")
                        }
                        val ticket = requiredText(delivery, "ticket", "result.claims.deliveryAnalysis.ticket", Int.MAX_VALUE)
                        if (ticket != null && ticket.isNotEmpty() && !TICKET.matches(ticket)) add("result.claims.deliveryAnalysis.ticket", "PATTERN", "must be a GitHub issue such as #123 or a Jira key such as ABC-123")
                        requiredText(delivery, "title", "result.claims.deliveryAnalysis.title", AgentStepResultLimits.DELIVERY_ANALYSIS_TITLE)
                        delivery.get("baseBranch")?.let {
                            when {
                                !it.isTextual -> add("result.claims.deliveryAnalysis.baseBranch", "TYPE_STRING", "must be a string")
                                it.asText().isEmpty() -> add("result.claims.deliveryAnalysis.baseBranch", "MIN_LENGTH", "must not be empty")
                                it.asText().length > AgentStepResultLimits.DELIVERY_ANALYSIS_BASE_BRANCH -> add("result.claims.deliveryAnalysis.baseBranch", "MAX_LENGTH", "must contain at most ${AgentStepResultLimits.DELIVERY_ANALYSIS_BASE_BRANCH} characters")
                            }
                        }
                    }
                }
            }
        }

        value.get("artifacts")?.let { artifacts ->
            when {
                !artifacts.isArray -> add("result.artifacts", "TYPE_ARRAY", "must be an array")
                artifacts.size() > AgentStepResultLimits.ARTIFACTS -> add("result.artifacts", "MAX_ITEMS", "must contain at most ${AgentStepResultLimits.ARTIFACTS} items")
                else -> artifacts.forEachIndexed { index, artifact ->
                    val path = "result.artifacts[$index]"
                    if (!artifact.isObject) add(path, "TYPE_OBJECT", "must be a JSON object")
                    else {
                        unknown(artifact, ARTIFACT_FIELDS, path)
                        requiredText(artifact, "kind", "$path.kind", AgentStepResultLimits.ARTIFACT_KIND)
                        val encoding = artifact.get("encoding")
                        when {
                            encoding == null -> add("$path.encoding", "REQUIRED", "is required")
                            !encoding.isTextual || encoding.asText() != "markdown" -> add("$path.encoding", "ENUM", "must be markdown")
                        }
                        val content = artifact.get("content")
                        when {
                            content == null -> add("$path.content", "REQUIRED", "is required")
                            !content.isTextual -> add("$path.content", "TYPE_STRING", "must be a string")
                            content.asText().isEmpty() -> add("$path.content", "MIN_LENGTH", "must not be empty")
                            content.asText().toByteArray(Charsets.UTF_8).size > AgentStepResultLimits.ARTIFACT_CONTENT_BYTES -> add("$path.content", "MAX_BYTES", "must contain at most ${AgentStepResultLimits.ARTIFACT_CONTENT_BYTES} UTF-8 bytes")
                        }
                    }
                }
            }
        }

        value.get("findings")?.let { findings ->
            when {
                !findings.isArray -> add("result.findings", "TYPE_ARRAY", "must be an array")
                findings.size() > AgentStepResultLimits.FINDINGS -> add("result.findings", "MAX_ITEMS", "must contain at most ${AgentStepResultLimits.FINDINGS} items")
                else -> findings.forEachIndexed { index, finding ->
                    val path = "result.findings[$index]"
                    if (!finding.isObject) add(path, "TYPE_OBJECT", "must be a JSON object")
                    else {
                        unknown(finding, FINDING_FIELDS, path)
                        val severity = finding.get("severity")
                        when {
                            severity == null -> add("$path.severity", "REQUIRED", "is required")
                            !severity.isTextual || AgentStepResultSeverity.fromWire(severity.asText()) == null -> add("$path.severity", "ENUM", "must be info, warning, error, or blocking")
                        }
                        requiredText(finding, "code", "$path.code", AgentStepResultLimits.FINDING_CODE)
                        requiredText(finding, "summary", "$path.summary", AgentStepResultLimits.FINDING_SUMMARY)
                        finding.get("file")?.let {
                            when {
                                !it.isTextual -> add("$path.file", "TYPE_STRING", "must be a string")
                                it.asText().isEmpty() -> add("$path.file", "MIN_LENGTH", "must not be empty")
                                it.asText().length > AgentStepResultLimits.FINDING_FILE -> add("$path.file", "MAX_LENGTH", "must contain at most ${AgentStepResultLimits.FINDING_FILE} characters")
                            }
                        }
                        finding.get("line")?.let {
                            if (!it.isIntegralNumber) add("$path.line", "TYPE_INTEGER", "must be an integer")
                            else if (it.asLong() < 1) add("$path.line", "MINIMUM", "must be at least 1")
                        }
                    }
                }
            }
        }

        val snake = value.get("expected_amendment_seq")
        val camel = value.get("expectedAmendmentSeq")
        listOf("expected_amendment_seq" to snake, "expectedAmendmentSeq" to camel).forEach { (name, seq) ->
            if (seq != null && (!seq.isIntegralNumber || seq.asLong() < 0)) add("result.$name", "NON_NEGATIVE_INTEGER", "must be a non-negative integer")
        }
        if (snake?.isIntegralNumber == true && camel?.isIntegralNumber == true && snake.asLong() != camel.asLong()) add("result", "AMENDMENT_SEQUENCE_CONFLICT", "amendment sequence fields must agree")
        return errors
    }

    fun validateBusiness(value: JsonNode?): Boolean = violations(value).isEmpty()

    fun parseBusiness(value: JsonNode): AgentStepResultBusiness {
        require(validateBusiness(value)) { "The structured business result is invalid" }
        val artifacts = value.get("artifacts")?.map { AgentStepResultArtifact(it.get("kind").asText(), it.get("encoding").asText(), it.get("content").asText()) } ?: emptyList()
        val findings = value.get("findings")?.map { AgentStepResultFinding(it.get("severity").asText(), it.get("code").asText(), it.get("summary").asText(), it.get("file")?.asText(), it.get("line")?.asInt()) } ?: emptyList()
        return AgentStepResultBusiness(
            status = AgentStepResultStatus.fromWire(value.get("status").asText())!!,
            summary = value.get("summary").asText(),
            claims = AgentStepResultClaim(
                modifiedFiles = value.get("claims").get("modifiedFiles").map { it.asText() },
                deliveryAnalysis = value.get("claims").get("deliveryAnalysis")?.let { AgentStepResultDeliveryAnalysis(it.get("changeType").asText(), it.get("ticket").asText(), it.get("title").asText(), it.get("baseBranch")?.asText()) },
            ),
            artifacts = artifacts,
            findings = findings,
            expectedAmendmentSeq = value.get("expected_amendment_seq")?.asLong() ?: value.get("expectedAmendmentSeq")?.asLong(),
        )
    }
}
