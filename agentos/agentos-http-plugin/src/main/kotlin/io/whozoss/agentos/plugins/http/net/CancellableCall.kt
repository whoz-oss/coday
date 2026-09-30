package io.whozoss.agentos.plugins.http.net

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Runs this call and hands its response to [handle] on an OkHttp dispatcher thread, suspending the caller
 * without blocking a thread. Cancelling the coroutine cancels the call, which aborts the connection or a
 * body read in progress and releases the connection at once instead of after the call timeout. The
 * response is always closed; an [IOException] of the transport or of the body read is rethrown to the
 * caller, like `execute()` would.
 */
suspend fun <T> Call.executeCancellable(handle: (Response) -> T): T =
    suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    runCatching { response.use(handle) }
                        .onSuccess { continuation.resume(it) }
                        .onFailure { continuation.resumeWithException(it) }
                }
            },
        )
    }
