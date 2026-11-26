package io.whozoss.agentos.caseEvent

import io.whozoss.agentos.membership.resolveMembers
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.sdk.actor.Actor
import io.whozoss.agentos.sdk.api.membership.MemberItem
import io.whozoss.agentos.sdk.caseEvent.MessageContent
import io.whozoss.agentos.user.UserService
import mu.KLogging
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Turns the `@handle` of a message of a shared case into a notification for the person it names.
 *
 * The handle of a member is the one the composer inserts when the author picks them in the `@` list, see [mentionHandleOf].
 * Only members of the case can be mentioned, so a notified person can always open the case. The author is never
 * notified of their own message. Two members with the same handle are both notified.
 *
 * Best effort: a failure to resolve the members is logged and never fails the message that was just posted.
 */
@Component
class CaseMentionService(
    private val permissionService: PermissionService,
    private val userService: UserService,
    private val broadcaster: CaseMentionBroadcaster,
) {
    /**
     * Publishes a [CaseMention] for each member of [caseId] named in [content] by the author [author].
     *
     * [ignoring] is a name that is not a person even if it looks like a handle, the agent the message is addressed to.
     */
    fun notifyMentions(
        caseId: UUID,
        namespaceId: UUID,
        author: Actor,
        content: List<MessageContent>,
        ignoring: String? = null,
    ) {
        val text = content.filterIsInstance<MessageContent.Text>().joinToString("\n") { it.content }
        val handles = mentionedHandles(text) - setOfNotNull(ignoring?.lowercase())
        if (handles.isNotEmpty()) {
            runCatching {
                resolveMembers(caseId, EntityType.CASE, permissionService, userService)
                    .filter { !it.id.toString().equals(author.id, ignoreCase = true) && mentionHandleOf(it).lowercase() in handles }
                    .forEach { member ->
                        broadcaster.publish(
                            CaseMention(
                                userId = member.id,
                                caseId = caseId,
                                namespaceId = namespaceId,
                                byUserId = author.id,
                                byName = author.displayName.ifBlank { author.id },
                                snippet = snippetOf(text),
                            ),
                        )
                    }
            }.onFailure { logger.warn(it) { "Could not resolve the mentions of a message of case $caseId" } }
        }
    }

    companion object : KLogging() {
        const val MAX_SNIPPET_LENGTH = 200
    }
}

private val MENTION = Regex("""(?:^|\s)@([^\s@]+)""")
private val WHITESPACE_OR_AT = Regex("[\\s@]+")
private val TRAILING_PUNCTUATION = charArrayOf('.', ',', ';', ':', '!', '?', ')')

/**
 * The handles named in [text], lowercase: a word that starts with `@` at the start of the text or after whitespace, so
 * that an email address is not a mention, without the punctuation that ends a sentence.
 */
internal fun mentionedHandles(text: String): Set<String> =
    MENTION
        .findAll(text)
        .map { it.groupValues[1].trimEnd(*TRAILING_PUNCTUATION).lowercase() }
        .filter { it.isNotEmpty() }
        .toSet()

/**
 * What the composer inserts after the `@` for [member]: the email before its `@`, else the name, else the email,
 * else the external id, without whitespace or `@`, else the user id. Keep it identical to `memberHandle` of the
 * Angular client (`member-mention.ts`).
 */
internal fun mentionHandleOf(member: MemberItem): String {
    val label =
        listOfNotNull(member.firstname, member.lastname)
            .joinToString(" ")
            .trim()
            .ifBlank { member.email.ifBlank { member.externalId } }
    val raw = member.email.substringBefore('@').ifBlank { label }
    return raw.replace(WHITESPACE_OR_AT, "").ifBlank { member.id.toString() }
}

private fun snippetOf(text: String): String =
    text.trim().replace(Regex("\\s+"), " ").take(CaseMentionService.MAX_SNIPPET_LENGTH)
