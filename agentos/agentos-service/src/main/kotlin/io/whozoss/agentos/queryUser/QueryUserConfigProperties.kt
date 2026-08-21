package io.whozoss.agentos.queryUser

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Platform-level policy for the built-in `queryUser` tool.
 *
 * Bound from the `agentos.query-user` prefix in `application.yml`.
 *
 * The `queryUser` tool lets an agent pause its run and ask the user a question
 * asynchronously. It is a primitive of conversation, not an integration like Jira:
 * agents should not have to declare it. The platform default is therefore **on**,
 * matching the behaviour of the legacy Express backend.
 *
 * An agent can still opt out by mapping the key [QueryUserToolPlugin.INTEGRATION_TYPE]
 * to an empty list `[]` in its `integrations` map — the same opt-out signal used by
 * the file-exchange tools. This is the only escape hatch: for a fully autonomous agent
 * triggered by a webhook where nobody is listening, leaving the question unanswered
 * would block the case indefinitely.
 *
 * Override with environment variable:
 * - `AGENTOS_QUERY_USER_ENABLED_BY_DEFAULT` (boolean, default `true`)
 */
@ConfigurationProperties(prefix = "agentos.query-user")
data class QueryUserConfigProperties(
    /**
     * When `true` (default), every agent that does not mention
     * [QueryUserToolPlugin.INTEGRATION_TYPE] in its `integrations` map receives the
     * `queryUser` tool automatically.
     *
     * When `false`, an agent must declare the key explicitly (with a null value or a
     * non-empty list) to get the tool.
     *
     * An empty list `[]` always opts the agent out, regardless of this default.
     */
    val enabledByDefault: Boolean = true,
)
