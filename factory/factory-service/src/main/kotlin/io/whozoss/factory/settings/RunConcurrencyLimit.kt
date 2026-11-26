package io.whozoss.factory.settings

/**
 * Read-only view of the maximum number of workflow continuations that may run in parallel.
 *
 * The drain worker asks for the value before each dispatch, so a change made by an administrator
 * applies at the next tick without a restart. [RunConcurrencySettingsService] is the production
 * implementation; unit tests pass a lambda.
 */
fun interface RunConcurrencyLimit {
    fun current(): Int
}
