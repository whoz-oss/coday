package io.whozoss.agentos.messaging

import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Links to pages of the AgentOS UI, sent in a chat. Null when [MessagingProperties.publicUrl] is not set:
 * the server does not know where its UI is served, and a guessed link would be wrong.
 */
@Component
class MessagingLinks(
    properties: MessagingProperties,
) {
    private val base: String? = properties.publicUrl.trim().trimEnd('/').takeIf { it.isNotEmpty() }

    fun case(
        namespaceId: UUID,
        caseId: UUID,
    ): String? = base?.let { "$it/home?ns=$namespaceId&case=$caseId" }

    fun profile(): String? = base?.let { "$it/me" }
}
