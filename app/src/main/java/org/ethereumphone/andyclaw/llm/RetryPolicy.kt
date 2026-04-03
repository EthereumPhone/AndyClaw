package org.ethereumphone.andyclaw.llm

import android.util.Log
import kotlinx.coroutines.delay
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

/**
 * Retry configuration for LLM API calls.
 * Ported from Claude Code's `withRetry.ts`.
 */
data class RetryPolicy(
    /** Maximum total retry attempts. */
    val maxRetries: Int = 5,
    /** Base delay before first retry (doubles each attempt). */
    val baseDelayMs: Long = 500L,
    /** Maximum delay between retries. */
    val maxDelayMs: Long = 32_000L,
    /** Random jitter added as fraction of computed delay (0.0–1.0). */
    val jitterFraction: Float = 0.25f,
    /** Maximum retries specifically for 529 (overloaded) errors. */
    val max529Retries: Int = 3,
)

/**
 * Thrown when all retry attempts are exhausted or the error is non-retryable.
 */
class CannotRetryException(
    val originalError: Throwable,
    val statusCode: Int? = null,
) : Exception(originalError.message, originalError)

private const val TAG = "RetryPolicy"

/**
 * Executes [operation] with automatic retries on transient failures.
 *
 * Error classification (matches Claude Code):
 * - **Retryable**: 408, 409, 429, 500+, 529 (max [RetryPolicy.max529Retries])
 * - **Non-retryable**: 400, 403, 422, and any non-API error
 *
 * Backoff: exponential with jitter, respecting `retry-after` header when present.
 */
suspend fun <T> withRetry(
    policy: RetryPolicy = RetryPolicy(),
    operation: suspend (attempt: Int) -> T,
): T {
    var consecutive529 = 0

    for (attempt in 0..policy.maxRetries) {
        try {
            return operation(attempt)
        } catch (e: AnthropicApiException) {
            val retryable = isRetryable(e.statusCode)
            if (!retryable || attempt >= policy.maxRetries) {
                throw CannotRetryException(e, e.statusCode)
            }

            // 529 has its own retry cap
            if (e.statusCode == 529) {
                consecutive529++
                if (consecutive529 > policy.max529Retries) {
                    Log.w(TAG, "529 retry cap reached ($consecutive529 consecutive)")
                    throw CannotRetryException(e, e.statusCode)
                }
            } else {
                consecutive529 = 0
            }

            val delayMs = getRetryDelay(attempt, e.retryAfterSeconds, policy)
            Log.w(TAG, "Retrying after ${e.statusCode} (attempt ${attempt + 1}/${policy.maxRetries}, delay=${delayMs}ms)")
            delay(delayMs)
        } catch (e: java.io.IOException) {
            // Network/connection errors are retryable
            if (attempt >= policy.maxRetries) {
                throw CannotRetryException(e)
            }
            val delayMs = getRetryDelay(attempt, retryAfterSeconds = null, policy)
            Log.w(TAG, "Retrying after IO error: ${e.message} (attempt ${attempt + 1}/${policy.maxRetries}, delay=${delayMs}ms)")
            delay(delayMs)
        }
    }
    // Should not reach here, but just in case
    throw IllegalStateException("Retry loop exited unexpectedly")
}

/**
 * Whether an HTTP status code is retryable.
 */
private fun isRetryable(statusCode: Int): Boolean = when (statusCode) {
    408, 409, 429, 529 -> true
    in 500..599 -> true
    else -> false
}

/**
 * Computes the delay before the next retry attempt.
 * Uses exponential backoff with jitter, honoring `retry-after` header if present.
 */
private fun getRetryDelay(
    attempt: Int,
    retryAfterSeconds: Int?,
    policy: RetryPolicy,
): Long {
    // Honor retry-after header if present
    if (retryAfterSeconds != null && retryAfterSeconds > 0) {
        return retryAfterSeconds * 1000L
    }

    // Exponential backoff: baseDelay * 2^attempt, capped at maxDelay
    val baseDelay = min(
        policy.baseDelayMs * 2.0.pow(attempt).toLong(),
        policy.maxDelayMs,
    )
    // Add random jitter (0 to jitterFraction * baseDelay)
    val jitter = (Random.nextFloat() * policy.jitterFraction * baseDelay).toLong()
    return baseDelay + jitter
}
