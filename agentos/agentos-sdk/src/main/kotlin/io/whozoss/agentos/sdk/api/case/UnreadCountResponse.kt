package io.whozoss.agentos.sdk.api.case

import io.swagger.v3.oas.annotations.media.Schema

/**
 * Response body for [CaseApi.countUnread].
 *
 * @property unreadCount Number of unread cases in the queried namespace for the current user.
 *   A case is unread when no [CaseDto.readAt] relation exists for the user, or when the most
 *   recent event's timestamp is after the user's [CaseDto.readAt].
 */
@Schema(name = "UnreadCountResponse")
data class UnreadCountResponse(
    val unreadCount: Long,
)
