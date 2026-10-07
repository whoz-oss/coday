package io.whozoss.factory.delivery.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Trusted configuration of the delivery aggregate.
 *
 * Only server-trusted configuration may bind a delivery to a target or open a
 * pull request; the HTTP body is never authoritative. Port of the Node
 * `DeliveryTrustedConfiguration` consumed by the delivery controller.
 */
@ConfigurationProperties(prefix = "factory.delivery")
data class DeliveryProperties(
    /** Pull-request provider configuration; absent means PRs are unavailable. */
    val pullRequest: PullRequest? = null,
) {
    data class PullRequest(
        val owner: String,
        val repo: String,
        val baseBranch: String,
    )
}
