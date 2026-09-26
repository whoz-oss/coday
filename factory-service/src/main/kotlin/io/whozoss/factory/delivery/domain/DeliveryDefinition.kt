package io.whozoss.factory.delivery.domain

/**
 * Pure delivery-definition domain: schema vocabulary, the five governed
 * delivery stages, evidence kinds, structural validation and content hashing.
 *
 * Faithful port of `factory/src/domain/delivery/delivery-definition.ts`.
 */
object DeliveryDefinitionSchema {
    const val SCHEMA_VERSION = "1"

    /** The five governed delivery stages, in strict promotion order. */
    val STAGES: List<String> = listOf(
        "implementation-ready",
        "artifact-ready",
        "release-approved",
        "deployed",
        "production-verified",
    )

    /** Evidence kinds a checkpoint may require. */
    val EVIDENCE_KINDS: List<String> = listOf(
        "implementation-result",
        "artifact",
        "oracle-result",
        "human-decision",
        "deployment-result",
        "smoke-result",
        "rollback-result",
    )

    private val SAFE = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")

    fun isSafe(value: String?): Boolean = value != null && SAFE.matches(value)
}

private val SAFE_ID = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")
private val SEMVER = Regex("^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)$")
private val TOP_FIELDS = setOf(
    "schemaVersion", "deliveryType", "version", "title", "checkpoints",
    "artifactPolicy", "promotionPolicy", "deploymentPolicy", "retentionPolicy",
)
private val CHECKPOINT_FIELDS = setOf("stage", "responsibility", "requiredEvidence")
private val RESPONSIBILITY_FIELDS = setOf("kind", "name")
private val EVIDENCE_FIELDS = setOf("kind", "outcome", "oracleId")
private val OUTCOMES = setOf("pass", "fail", "indeterminate", "approved", "rejected")

/** Actor responsible for clearing a checkpoint. */
data class DeliveryResponsibility(
    val kind: String,
    val name: String,
)

/** One evidence requirement attached to a checkpoint. */
data class DeliveryRequiredEvidence(
    val kind: String,
    val outcome: String,
    val oracleId: String? = null,
)

/** A single ordered checkpoint of the delivery definition. */
data class DeliveryCheckpoint(
    val stage: String,
    val responsibility: DeliveryResponsibility,
    val requiredEvidence: List<DeliveryRequiredEvidence>,
)

/** A validated delivery definition in canonical form. */
data class DeliveryDefinition(
    val schemaVersion: String,
    val deliveryType: String,
    val version: String,
    val title: String,
    val checkpoints: List<DeliveryCheckpoint>,
    val artifactPolicy: Map<String, Any?>,
    val promotionPolicy: Map<String, Any?>,
    val deploymentPolicy: Map<String, Any?>,
    val retentionPolicy: Map<String, Any?>,
) {
    /** The plain map form used for canonical content hashing. */
    fun toMap(): Map<String, Any?> = mapOf(
        "schemaVersion" to schemaVersion,
        "deliveryType" to deliveryType,
        "version" to version,
        "title" to title,
        "checkpoints" to checkpoints.map { checkpoint ->
            mapOf(
                "stage" to checkpoint.stage,
                "responsibility" to mapOf(
                    "kind" to checkpoint.responsibility.kind,
                    "name" to checkpoint.responsibility.name,
                ),
                "requiredEvidence" to checkpoint.requiredEvidence.map { evidence ->
                    if (evidence.oracleId != null) {
                        mapOf(
                            "kind" to evidence.kind,
                            "outcome" to evidence.outcome,
                            "oracleId" to evidence.oracleId,
                        )
                    } else {
                        mapOf("kind" to evidence.kind, "outcome" to evidence.outcome)
                    }
                },
            )
        },
        "artifactPolicy" to artifactPolicy,
        "promotionPolicy" to promotionPolicy,
        "deploymentPolicy" to deploymentPolicy,
        "retentionPolicy" to retentionPolicy,
    )
}

/** Result of validating a delivery definition. */
sealed interface DeliveryDefinitionValidation {
    data class Valid(val definition: DeliveryDefinition) : DeliveryDefinitionValidation
    data class Invalid(val path: String, val reason: String = "invalid_value") : DeliveryDefinitionValidation
}

/** Validates a raw delivery definition, returning its canonical form on success. */
fun validateDeliveryDefinition(input: Map<String, Any?>?): DeliveryDefinitionValidation {
    if (input == null || input.keys.any { it !in TOP_FIELDS }) {
        return DeliveryDefinitionValidation.Invalid("$")
    }
    if (input["schemaVersion"] != DeliveryDefinitionSchema.SCHEMA_VERSION) {
        return DeliveryDefinitionValidation.Invalid("schemaVersion")
    }
    val deliveryType = input["deliveryType"] as? String
    val version = input["version"] as? String
    val title = input["title"] as? String
    if (!SAFE_ID.matches(deliveryType ?: "") ||
        version == null || !SEMVER.matches(version) ||
        title == null || title.isBlank() || title.length > 256
    ) {
        return DeliveryDefinitionValidation.Invalid("$")
    }
    val resolvedDeliveryType = deliveryType!!
    val resolvedVersion = version!!
    val resolvedTitle = title!!
    val rawCheckpoints = input["checkpoints"] as? List<*> ?: return DeliveryDefinitionValidation.Invalid("checkpoints")
    if (rawCheckpoints.size != DeliveryDefinitionSchema.STAGES.size) {
        return DeliveryDefinitionValidation.Invalid("checkpoints")
    }
    val checkpoints = ArrayList<DeliveryCheckpoint>(rawCheckpoints.size)
    for (index in rawCheckpoints.indices) {
        val raw = rawCheckpoints[index] as? Map<*, *>
            ?: return DeliveryDefinitionValidation.Invalid("checkpoints[$index]")
        if (raw.keys.any { it !in CHECKPOINT_FIELDS } || raw["stage"] != DeliveryDefinitionSchema.STAGES[index]) {
            return DeliveryDefinitionValidation.Invalid("checkpoints[$index]")
        }
        val responsibility = raw["responsibility"] as? Map<*, *>
            ?: return DeliveryDefinitionValidation.Invalid("checkpoints[$index].responsibility")
        if (responsibility.keys.any { it !in RESPONSIBILITY_FIELDS } ||
            responsibility["kind"] !in setOf("code", "human") ||
            !SAFE_ID.matches((responsibility["name"] as? String) ?: "")
        ) {
            return DeliveryDefinitionValidation.Invalid("checkpoints[$index].responsibility")
        }
        if (raw["stage"] == "release-approved" && responsibility["kind"] != "human") {
            return DeliveryDefinitionValidation.Invalid("checkpoints[$index].responsibility", "release_requires_human")
        }
        if (raw["stage"] != "release-approved" && responsibility["kind"] != "code") {
            return DeliveryDefinitionValidation.Invalid("checkpoints[$index].responsibility", "factory_code_required")
        }
        val rawEvidence = raw["requiredEvidence"] as? List<*>
            ?: return DeliveryDefinitionValidation.Invalid("checkpoints[$index].requiredEvidence")
        if (rawEvidence.isEmpty() || rawEvidence.size > 16) {
            return DeliveryDefinitionValidation.Invalid("checkpoints[$index].requiredEvidence")
        }
        val requiredEvidence = ArrayList<DeliveryRequiredEvidence>(rawEvidence.size)
        for (evidenceIndex in rawEvidence.indices) {
            val item = rawEvidence[evidenceIndex] as? Map<*, *>
                ?: return DeliveryDefinitionValidation.Invalid("checkpoints[$index].requiredEvidence[$evidenceIndex]")
            if (item.keys.any { it !in EVIDENCE_FIELDS } ||
                item["kind"] !in DeliveryDefinitionSchema.EVIDENCE_KINDS ||
                item["outcome"] !in OUTCOMES
            ) {
                return DeliveryDefinitionValidation.Invalid("checkpoints[$index].requiredEvidence[$evidenceIndex]")
            }
            val oracleId = item["oracleId"] as? String
            if (oracleId != null && !SAFE_ID.matches(oracleId)) {
                return DeliveryDefinitionValidation.Invalid(
                    "checkpoints[$index].requiredEvidence[$evidenceIndex].oracleId",
                )
            }
            requiredEvidence.add(
                DeliveryRequiredEvidence(
                    kind = item["kind"] as String,
                    outcome = item["outcome"] as String,
                    oracleId = oracleId,
                ),
            )
        }
        checkpoints.add(
            DeliveryCheckpoint(
                stage = raw["stage"] as String,
                responsibility = DeliveryResponsibility(
                    kind = responsibility["kind"] as String,
                    name = responsibility["name"] as String,
                ),
                requiredEvidence = requiredEvidence,
            ),
        )
    }
    val policies = listOf(
        "artifactPolicy" to 32,
        "promotionPolicy" to 32,
        "deploymentPolicy" to 32,
        "retentionPolicy" to 16,
    )
    for ((field, maximum) in policies) {
        val value = input[field] as? Map<*, *> ?: return DeliveryDefinitionValidation.Invalid(field)
        if (value.isEmpty() || value.size > maximum) {
            return DeliveryDefinitionValidation.Invalid(field)
        }
    }
    return DeliveryDefinitionValidation.Valid(
        DeliveryDefinition(
            schemaVersion = DeliveryDefinitionSchema.SCHEMA_VERSION,
            deliveryType = resolvedDeliveryType,
            version = resolvedVersion,
            title = resolvedTitle,
            checkpoints = checkpoints,
            artifactPolicy = (input["artifactPolicy"] as Map<*, *>).toAnyMap(),
            promotionPolicy = (input["promotionPolicy"] as Map<*, *>).toAnyMap(),
            deploymentPolicy = (input["deploymentPolicy"] as Map<*, *>).toAnyMap(),
            retentionPolicy = (input["retentionPolicy"] as Map<*, *>).toAnyMap(),
        ),
    )
}

/** Stable SHA-256 hash of a definition's canonical JSON form. */
fun hashDeliveryDefinition(definition: DeliveryDefinition): String = CanonicalHash.sha256(definition.toMap())

/** The default governed Factory delivery definition. */
fun defaultDeliveryDefinition(): DeliveryDefinition = DeliveryDefinition(
    schemaVersion = DeliveryDefinitionSchema.SCHEMA_VERSION,
    deliveryType = "factory-delivery",
    version = "1.0.0",
    title = "Governed Factory delivery",
    checkpoints = listOf(
        DeliveryCheckpoint(
            stage = "implementation-ready",
            responsibility = DeliveryResponsibility("code", "implementation-policy"),
            requiredEvidence = listOf(DeliveryRequiredEvidence("implementation-result", "pass")),
        ),
        DeliveryCheckpoint(
            stage = "artifact-ready",
            responsibility = DeliveryResponsibility("code", "artifact-oracle"),
            requiredEvidence = listOf(
                DeliveryRequiredEvidence("artifact", "pass"),
                DeliveryRequiredEvidence("oracle-result", "pass"),
            ),
        ),
        DeliveryCheckpoint(
            stage = "release-approved",
            responsibility = DeliveryResponsibility("human", "release-approver"),
            requiredEvidence = listOf(DeliveryRequiredEvidence("human-decision", "approved")),
        ),
        DeliveryCheckpoint(
            stage = "deployed",
            responsibility = DeliveryResponsibility("code", "deployment-control-plane"),
            requiredEvidence = listOf(DeliveryRequiredEvidence("deployment-result", "pass")),
        ),
        DeliveryCheckpoint(
            stage = "production-verified",
            responsibility = DeliveryResponsibility("code", "production-smoke"),
            requiredEvidence = listOf(DeliveryRequiredEvidence("smoke-result", "pass")),
        ),
    ),
    artifactPolicy = mapOf(
        "requireBuildAndTests" to true,
        "extensibleChecks" to "sast sca secrets sbom signature provenance",
    ),
    promotionPolicy = mapOf("ordered" to true, "automaticMerge" to false, "requireFactoryEvidence" to true),
    deploymentPolicy = mapOf("environmentsFromTrustedConfiguration" to true, "requireRollbackCapability" to true),
    retentionPolicy = mapOf("deleteWorktreeBeforeProductionVerified" to false),
)

private fun Map<*, *>.toAnyMap(): Map<String, Any?> = entries.associate { it.key.toString() to it.value }
