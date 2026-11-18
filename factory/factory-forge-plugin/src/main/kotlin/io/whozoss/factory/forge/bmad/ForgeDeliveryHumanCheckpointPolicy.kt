package io.whozoss.factory.forge.bmad

import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.factory.sdk.spi.FactoryHumanCheckpointPolicy
import io.whozoss.factory.sdk.spi.HumanCheckpointContext
import io.whozoss.factory.sdk.spi.HumanCheckpointVerdict
import org.pf4j.Extension
import java.security.MessageDigest

/** Binds all Forge approvals to the packet and the work candidate shown. */
@Extension
class ForgeDeliveryHumanCheckpointPolicy : FactoryHumanCheckpointPolicy {
    override fun supports(flowType: String, stepId: String): Boolean =
        flowType == "forge.delivery" && stepId in setOf("packet-approval", "technical-validation", "functional-validation")

    override fun present(context: HumanCheckpointContext): HumanCheckpointVerdict =
        when (context.stepId) {
            "packet-approval" -> packet(context)?.let { HumanCheckpointVerdict.Allowed(it, packetPrompt(context, it)) }
                ?: HumanCheckpointVerdict.Denied("PACKET_IDENTITY_MISSING")
            "technical-validation" -> technical(context)?.let { HumanCheckpointVerdict.Allowed(it,
                "Valider techniquement la PR #${it["prNumber"]} au commit ${it["workCommit"]}. " +
                    "Consulter la revue et le paquet approuvé avant de répondre.") }
                ?: HumanCheckpointVerdict.Denied("TECHNICAL_CANDIDATE_MISSING")
            "functional-validation" -> functional(context)?.let { HumanCheckpointVerdict.Allowed(it,
                "Valider fonctionnellement le travail ${it["workCommit"]} déployé sur ${it["target"]} " +
                    "avec le tag ${it["deployedTag"]}. Ce tag est une référence de déploiement, " +
                    "pas une garantie de la version exacte testée.") }
                ?: HumanCheckpointVerdict.Denied("DEPLOYED_CANDIDATE_MISSING")
            else -> HumanCheckpointVerdict.Denied("INVALID_FORGE_GATE")
        }

    override fun decide(
        context: HumanCheckpointContext,
        presented: Map<String, Any?>,
        actionId: String,
    ): HumanCheckpointVerdict {
        if (actionId !in setOf("approve", "reject")) return HumanCheckpointVerdict.Denied("INVALID_DECISION")
        val current = present(context)
        if (current !is HumanCheckpointVerdict.Allowed) return current
        if (current.facts != presented) return HumanCheckpointVerdict.Denied("FORGE_DECISION_STALE")
        return HumanCheckpointVerdict.Allowed(current.facts + ("gate" to context.stepId))
    }

    private fun packet(context: HumanCheckpointContext): Map<String, Any?>? {
        val raw = context.run.domainState["approvedPacketCandidate"] as? Map<*, *> ?: return null
        val repository = raw["repository"] as? String
        val commit = raw["commit"] as? String
        val digest = raw["manifestSha256"] as? String
        if (repository.isNullOrBlank() || commit?.matches(COMMIT) != true || digest?.matches(SHA256) != true) return null
        return mapOf("repository" to repository, "commit" to commit, "manifestSha256" to digest)
    }

    private fun packetPrompt(context: HumanCheckpointContext, identity: Map<String, Any?>): String {
        val canonical = (context.run.domainState["approvedPacketCandidate"] as? Map<*, *>)
            ?.get("canonicalManifest") as? String
        val paths = runCatching { ObjectMapper().readTree(canonical).path("entries") }
            .getOrNull()?.takeIf { it.isArray }?.mapNotNull { entry ->
                entry.path("path").asText().takeIf(String::isNotBlank)?.let { path ->
                    val locator = entry.path("locator").asText().ifBlank { path }
                    "${entry.path("kind").asText()}: $locator"
                }
            }.orEmpty()
        return "Approuver le paquet de conception du dépôt ${identity["repository"]} au commit ${identity["commit"]} " +
            "(empreinte ${identity["manifestSha256"]}) ? Documents : ${paths.joinToString(" ; ").ifBlank { "voir le manifeste du run" }}. " +
            "Consulter ces documents avant de répondre."
    }

    /** Same key before and after integration, so technical review may proceed while CI waits. */
    private fun candidate(context: HumanCheckpointContext): Map<String, Any?>? {
        val packet = packet(context) ?: return null
        val build = context.run.domainState["build_report"] as? Map<*, *> ?: return null
        val workCommit = build["work_commit"] as? String ?: return null
        if (!COMMIT.matches(workCommit) || build["manifest_sha256"] != packet["manifestSha256"]) return null
        val source = "${context.run.runId}:${packet["repository"]}:${packet["commit"]}:${packet["manifestSha256"]}:$workCommit"
        val id = "candidate-" + MessageDigest.getInstance("SHA-256").digest(source.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(32)
        return packet + mapOf("candidateId" to id, "workCommit" to workCommit)
    }

    private fun technical(context: HumanCheckpointContext): Map<String, Any?>? {
        val candidate = candidate(context) ?: return null
        val workCommit = candidate["workCommit"]
        val review = context.run.domainState["review_report"] as? Map<*, *> ?: return null
        val verification = context.run.domainState["verification_report"] as? Map<*, *> ?: return null
        val observed = review["observed_pr"] as? Map<*, *> ?: return null
        val prNumber = (observed["prNumber"] as? Number)?.toInt() ?: return null
        if (verification["work_commit"] != workCommit || review["work_commit"] != workCommit ||
            observed["prHeadCommit"] != workCommit || review["pr_head_commit"] != workCommit ||
            (review["pr_number"] as? Number)?.toInt() != prNumber || prNumber <= 0) return null
        return candidate + mapOf("prNumber" to prNumber, "prHeadCommit" to workCommit)
    }

    private fun functional(context: HumanCheckpointContext): Map<String, Any?>? {
        val candidate = candidate(context) ?: return null
        val deployment = context.run.domainState["deployment_evidence"] as? Map<*, *> ?: return null
        val tag = deployment["tag"] as? String ?: return null
        val target = deployment["target"] as? String ?: return null
        val receipt = deployment["receipt"] as? String ?: return null
        if (deployment["workCommit"] != candidate["workCommit"] || tag.isBlank() || target.isBlank() || receipt.isBlank()) return null
        return candidate + mapOf("deployedTag" to tag, "target" to target, "deploymentReceipt" to receipt)
    }

    private companion object {
        val COMMIT = Regex("^[a-f0-9]{40}$")
        val SHA256 = Regex("^[a-f0-9]{64}$")
    }
}
