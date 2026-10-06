package me.rerere.common.http

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import okhttp3.internal.closeQuietly
import okio.IOException
import kotlin.coroutines.resumeWithException

suspend fun Call.await(): Response {
    return suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) {
                    continuation.resumeWithException(e)
                }
            }

            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response) { cause, _, _ ->
                    response.closeQuietly()
                }
            }
        })
    }
}

/** Owns both the headers and blocking body reads until [block] finishes or is cancelled. */
suspend fun <T> Call.withCancellableResponse(block: suspend (Response) -> T): T =
    coroutineScope {
        // Runs cancellation cleanup immediately even while the owner is blocked in a body read.
        val cancellation = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                this@withCancellableResponse.cancel()
            }
        }
        try {
            await().use { response -> block(response) }
        } catch (error: IOException) {
            // Call.cancel() unblocks body reads by closing the socket; preserve the owning cancellation.
            currentCoroutineContext().ensureActive()
            throw error
        } finally {
            cancellation.cancel()
        }
    }
