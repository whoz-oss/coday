package io.whozoss.factory.forge.bmad

import io.whozoss.factory.sdk.spi.FactoryStepEvidenceObserver
import io.whozoss.factory.sdk.spi.StepEvidenceObservation
import io.whozoss.factory.sdk.spi.StepEvidenceObservationContext
import org.pf4j.Extension

/** Independently binds the reviewer's PR to the accepted Build head. */
@Extension
class ForgePullRequestEvidenceObserver : FactoryStepEvidenceObserver {
    override fun supports(flowType: String, stepId: String, agentName: String): Boolean =
        flowType == "forge.delivery" && stepId == "implementation-review" && agentName == "ForgeReviewer"

    override fun observe(context: StepEvidenceObservationContext): StepEvidenceObservation {
        val workCommit = (context.run.domainState["build_report"] as? Map<*, *>)?.get("work_commit") as? String
        if (workCommit?.matches(COMMIT) != true) {
            return StepEvidenceObservation.Invalid("BUILD_COMMIT_MISSING", "The accepted Build commit is missing.")
        }
        val reader = context.pullRequests
            ?: return StepEvidenceObservation.Unavailable("PR_READER_UNAVAILABLE", "The trusted pull request reader is unavailable.")
        val observed = reader.exactHead(workCommit)
            ?: return StepEvidenceObservation.Unavailable("PR_HEAD_PENDING", "No open PR has the accepted Build commit as its exact head.")
        val source = context.gitObjects?.sourceRepository()
            ?: return StepEvidenceObservation.Unavailable("GIT_SOURCE_UNAVAILABLE", "The Run's source Git repository is unavailable.")
        val repository = observed["repository"] as? String
        val branch = observed["branch"] as? String
        val number = (observed["number"] as? Number)?.toInt()
        val url = observed["url"] as? String
        if (observed["headCommit"] != workCommit || repository?.substringAfterLast('/') != source ||
            branch.isNullOrBlank() || number == null || number <= 0 || url.isNullOrBlank()
        ) return StepEvidenceObservation.Unavailable("PR_EVIDENCE_INVALID", "The observed PR does not match the Run's Build head.")
        return StepEvidenceObservation.Observed(mapOf(
            "repository" to repository,
            "branch" to branch,
            "prHeadCommit" to workCommit,
            "prNumber" to number,
            "prUrl" to url,
        ))
    }

    private companion object {
        val COMMIT = Regex("^[0-9a-f]{40}$")
    }
}
