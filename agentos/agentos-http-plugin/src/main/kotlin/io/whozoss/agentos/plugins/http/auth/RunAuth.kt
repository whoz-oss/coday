package io.whozoss.agentos.plugins.http.auth

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import mu.KLogging

/**
 * The authentication of one run's calls, resolved by the first call that needs it rather than when the
 * tools are built: for an OAuth Auth Setting the service's credential provider may ask the user to
 * authorize and wait for the answer, which must only happen when one of the integration's tools is
 * actually called, never on a run that does not use them.
 *
 * The spec is resolved once and kept for the rest of the run, [AuthHeaderSpec.Missing] included: an
 * authorization declined or timed out is not asked again by every later call of the run, the next run asks
 * again. Concurrent calls of the run wait for that single resolution. A resolution that throws is answered
 * as [AuthHeaderSpec.Missing], the detail staying in the logs.
 *
 * A run is one agent turn. Every user message starts a new one, which resolves the tools again and so
 * builds a new [RunAuth] (`CaseRuntime` -> `AgentServiceImpl.findAgentByName` ->
 * `ToolResolverService.resolveToolsForRun` -> `provideTools`).
 *
 * @param resolve Builds the spec. The credential provider is not suspending and may block until the user
 *   answers, so it runs on the IO dispatcher, interrupted when the run is cancelled.
 */
class RunAuth(
    private val configName: String,
    private val resolve: () -> AuthHeaderSpec,
) {
    private val mutex = Mutex()

    @Volatile
    private var resolved: AuthHeaderSpec? = null

    suspend fun spec(): AuthHeaderSpec = resolved ?: mutex.withLock { resolved ?: resolveOnce().also { resolved = it } }

    private suspend fun resolveOnce(): AuthHeaderSpec {
        val spec =
            try {
                runInterruptible(Dispatchers.IO) { resolve() }
            } catch (e: Exception) {
                // A cancellation is the run's only when the run is cancelled; any other is a failed resolution.
                if (e is CancellationException) currentCoroutineContext().ensureActive()
                logger.error(e) { "HTTP_API integration '$configName': credential resolution failed: ${e.message}" }
                AuthHeaderSpec.Missing(RESOLUTION_FAILED)
            }
        val line = "HTTP_API integration '$configName': auth=$spec for the rest of the run"
        if (spec is AuthHeaderSpec.Missing) logger.warn { line } else logger.info { line }
        return spec
    }

    companion object : KLogging() {
        const val RESOLUTION_FAILED = "unexpected error while resolving the credential, see the service logs"
    }
}
