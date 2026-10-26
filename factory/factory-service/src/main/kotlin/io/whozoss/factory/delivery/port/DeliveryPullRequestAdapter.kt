package io.whozoss.factory.delivery.port

import io.whozoss.factory.delivery.domain.DeliveryErrorCodes
import org.springframework.stereotype.Component

/**
 * Pull-request adapter port for delivery operations.
 *
 * Port of `factory/src/adapters/delivery/delivery-pr-adapter.ts`. Creation always
 * inspects first to stay idempotent across crash/retry, and an unconfigured
 * provider reports a stable `PULL_REQUEST_NOT_CONFIGURED` error rather than
 * throwing.
 */

/** The trusted context used to create a pull request. */
data class DeliveryPullRequestContext(
    val owner: String,
    val repo: String,
    val baseBranch: String,
    val headBranch: String,
    val title: String?,
    val body: String?,
    val idempotencyKey: String?,
)

/** A trusted pull-request projection. */
data class DeliveryPullRequest(
    val id: String,
    val url: String,
    val draft: Boolean,
    val state: String,
)

/** Result of a pull-request operation. */
sealed interface DeliveryPullRequestResult {
    data class Ok(val pullRequest: DeliveryPullRequest, val reused: Boolean) : DeliveryPullRequestResult
    data class Blocked(val errorCode: String) : DeliveryPullRequestResult
}

/** Pull-request operations the delivery controller depends on. */
interface DeliveryPullRequestAdapter {
    /** Creates a draft pull request, reusing an existing one when present. */
    fun createDraft(context: DeliveryPullRequestContext): DeliveryPullRequestResult
}

/**
 * Stub [DeliveryPullRequestAdapter] used when no provider is configured.
 *
 * Mirrors the Node adapter's default: every creation is blocked with
 * `PULL_REQUEST_NOT_CONFIGURED`, which the controller surfaces as a 422.
 */
@Component
class StubDeliveryPullRequestAdapter : DeliveryPullRequestAdapter {
    override fun createDraft(context: DeliveryPullRequestContext): DeliveryPullRequestResult =
        DeliveryPullRequestResult.Blocked(DeliveryErrorCodes.PULL_REQUEST_NOT_CONFIGURED)
}
