package io.whozoss.agentos.plugins.http.auth

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import mu.KLogging

/**
 * The authentication of one run's calls, resolved by the first call that needs it rather than when the
 * tools are built: for an OAuth Auth Setting the service's credential provider may ask the user to
 * authorize and wait for the answer, which must only happen when one of the integration's tools is
 * actually called, never on a run that does not use them.
 *
 * A usable spec is kept for the rest of the run. A [AuthHeaderSpec.Missing] one is not, so the next call
 * asks again. Concurrent calls of the run wait for one resolution instead of starting one each. A
 * resolution that throws is answered as [AuthHeaderSpec.Missing], the detail staying in the logs.
 *
 * @param resolve Builds the spec. The credential provider is not suspending and may block until the user
 *   answers, so it runs on the IO dispatcher.
 */
class RunAuth(
    private val configName: String,
    private val resolve: () -> AuthHeaderSpec,
) {
    private val mutex = Mutex()

    @Volatile
    private var resolved: AuthHeaderSpec? = null

    suspend fun spec(): AuthHeaderSpec = resolved ?: mutex.withLock { resolved ?: resolveOnce() }

    private suspend fun resolveOnce(): AuthHeaderSpec {
        val spec =
            try {
                withContext(Dispatchers.IO) { resolve() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error(e) { "HTTP_API integration '$configName': credential resolution failed: ${e.message}" }
                AuthHeaderSpec.Missing(RESOLUTION_FAILED)
            }
        if (spec !is AuthHeaderSpec.Missing) resolved = spec
        return spec
    }

    companion object : KLogging() {
        const val RESOLUTION_FAILED = "unexpected error while resolving the credential, see the service logs"
    }
}
