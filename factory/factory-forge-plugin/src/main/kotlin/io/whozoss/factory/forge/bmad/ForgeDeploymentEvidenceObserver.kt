package io.whozoss.factory.forge.bmad

import io.whozoss.factory.sdk.spi.FactoryStepEvidenceObserver
import io.whozoss.factory.sdk.spi.StepEvidenceObservation
import io.whozoss.factory.sdk.spi.StepEvidenceObservationContext
import org.pf4j.Extension

/** Checks hosted integration outside Factory's result-finalization transaction. */
@Extension
class ForgeDeploymentEvidenceObserver : FactoryStepEvidenceObserver {
    override fun supports(flowType: String, stepId: String, agentName: String): Boolean =
        flowType == "forge.delivery" && stepId == "integration-observation" && agentName == "ForgeDeploymentObserver"

    override fun observe(context: StepEvidenceObservationContext): StepEvidenceObservation {
        val workstream = context.workstreamId.takeIf { WORKSTREAM.matches(it) }
            ?: return StepEvidenceObservation.Invalid("INVALID_WORKSTREAM", "The Forge workstream identity is invalid.")
        val workCommit = (context.run.domainState["build_report"] as? Map<*, *>)?.get("work_commit") as? String
        if (workCommit?.matches(COMMIT) != true) {
            return StepEvidenceObservation.Invalid("BUILD_COMMIT_MISSING", "The accepted Build commit is missing.")
        }
        val reader = context.deploymentEvidence
            ?: return StepEvidenceObservation.Unavailable("DEPLOYMENT_READER_UNAVAILABLE", "The trusted GitHub reader is unavailable.")
        val branch = "integration/$workstream"
        val target = "forge-$workstream"
        val evidence = reader.find(workCommit, branch, target)
            ?: return StepEvidenceObservation.Unavailable("DEPLOYMENT_PENDING", "No successful deployment contains this work commit yet.")
        val integrationCommit = evidence["integrationCommit"] as? String
        val tag = evidence["tag"] as? String
        val repository = evidence["repository"] as? String
        val buildRunId = (evidence["buildRunId"] as? Number)?.toLong()
        val deployRunId = (evidence["deployRunId"] as? Number)?.toLong()
        val deployJobId = (evidence["deployJobId"] as? Number)?.toLong()
        if (evidence["workCommit"] != workCommit || evidence["integrationBranch"] != branch ||
            evidence["target"] != target || integrationCommit?.matches(COMMIT) != true ||
            tag?.matches(TAG) != true || repository.isNullOrBlank() ||
            buildRunId == null || buildRunId <= 0 || deployRunId == null || deployRunId <= 0 ||
            deployJobId == null || deployJobId <= 0
        ) return StepEvidenceObservation.Unavailable("DEPLOYMENT_EVIDENCE_INVALID", "The GitHub receipt is incomplete or inconsistent.")
        val source = context.gitObjects?.sourceRepository()
            ?: return StepEvidenceObservation.Unavailable("GIT_SOURCE_UNAVAILABLE", "The Run's source Git repository is unavailable.")
        if (repository.substringAfterLast('/') != source) {
            return StepEvidenceObservation.Unavailable("DEPLOYMENT_REPOSITORY_MISMATCH", "Deployment repository does not match the Run source.")
        }
        return StepEvidenceObservation.Observed(mapOf(
            "repository" to repository,
            "workCommit" to workCommit,
            "integrationBranch" to branch,
            "integrationCommit" to integrationCommit,
            "tag" to tag,
            "target" to target,
            "buildRunId" to buildRunId,
            "deployRunId" to deployRunId,
            "deployJobId" to deployJobId,
            "receipt" to "github-actions:run/$deployRunId/job/$deployJobId",
        ))
    }

    private companion object {
        val WORKSTREAM = Regex("^[a-z0-9]+(?:-[a-z0-9]+)*$")
        val COMMIT = Regex("^[0-9a-f]{40}$")
        val TAG = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")
    }
}
