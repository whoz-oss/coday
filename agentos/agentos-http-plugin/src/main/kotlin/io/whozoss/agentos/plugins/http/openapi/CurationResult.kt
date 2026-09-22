package io.whozoss.agentos.plugins.http.openapi

/** Outcome of [OperationCurator.curate]; [warnings] lists operations dropped or degraded, with the reason. */
sealed interface CurationResult {
    val warnings: List<CurationWarning>

    data class Selected(
        val operations: List<OperationDescriptor>,
        override val warnings: List<CurationWarning>,
    ) : CurationResult

    /** More than [max] operations remain after filtering: the administrator must narrow the selection. */
    data class TooManyOperations(
        val count: Int,
        val max: Int,
        override val warnings: List<CurationWarning>,
    ) : CurationResult
}

/** @property operationKey `<METHOD> <path>` of the affected operation. */
data class CurationWarning(val operationKey: String, val reason: String)
