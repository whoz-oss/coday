package io.whozoss.agentos.queryUser

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Platform-level policy for the built-in `queryUser` tool.
 *
 * Bound from the `agentos.query-user` prefix in `application.yml`.
 *
 * The `queryUser` tool lets an agent ask the user a question asynchronously. It is a
 * primitive of conversation, not an integration like Jira, but is disabled by default
 * to limit its impact while it is being controlled and tested.
 *
 * An agent can opt in by mapping the key [QueryUserToolPlugin.INTEGRATION_TYPE] in its
 * `integrations` map. For a fully autonomous agent triggered by a webhook where nobody
 * is listening, leaving the tool disabled avoids blocking the case indefinitely when a
 * question remains unanswered.
 *
 * Override with environment variable:
 * - `AGENTOS_QUERY_USER_ENABLED_BY_DEFAULT` (boolean, default `false`)
 */
@ConfigurationProperties(prefix = "agentos.query-user")
data class QueryUserConfigProperties(
    /**
     * When `false` (default), an agent must declare
     * [QueryUserToolPlugin.INTEGRATION_TYPE] explicitly (with a null value or a
     * non-empty list) in its `integrations` map to get the `queryUser` tool.
     *
     * When `true`, every agent receives the tool unless it maps the key to an empty
     * list `[]`, which always opts the agent out.
     */
    val enabledByDefault: Boolean = false,
)
