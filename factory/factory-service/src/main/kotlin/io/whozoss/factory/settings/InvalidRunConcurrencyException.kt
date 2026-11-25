package io.whozoss.factory.settings

import io.whozoss.factory.error.FactoryException

/**
 * 400 - the requested maximum number of parallel workflow runs is missing, not an integer or
 * outside [RunConcurrencySettingsService.MIN]..[RunConcurrencySettingsService.MAX].
 */
class InvalidRunConcurrencyException(
    message: String = "maxParallelRuns must be an integer between ${RunConcurrencySettingsService.MIN} " +
        "and ${RunConcurrencySettingsService.MAX}",
) : FactoryException(
    statusCode = 400,
    errorCode = "INVALID_MAX_PARALLEL_RUNS",
    message = message,
    details = mapOf("min" to RunConcurrencySettingsService.MIN, "max" to RunConcurrencySettingsService.MAX),
)
