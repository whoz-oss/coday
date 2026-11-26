package io.whozoss.agentos.caseEvent

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.permissions.PermissionRelation
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.sdk.actor.Actor
import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.api.membership.MemberItem
import io.whozoss.agentos.sdk.caseEvent.MessageContent
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.user.User
import io.whozoss.agentos.user.UserService
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.util.UUID

class CaseMentionServiceUnitSpec :
    StringSpec({
        fun member(
            email: String,
            firstname: String? = null,
            lastname: String? = null,
        ) = MemberItem(
            id = UUID.randomUUID(),
            externalId = email,
            email = email,
            firstname = firstname,
            lastname = lastname,
            role = "MEMBER",
        )

        // ----- mentionedHandles ------------------------------------------------------------------------------

        "a handle is a word starting with @, at the start of the text or after a space" {
            mentionedHandles("@alice can you look, and @Bob too") shouldBe setOf("alice", "bob")
        }

        "an email address is not a mention" {
            mentionedHandles("write to alice@example.com") shouldBe emptySet()
        }

        "the punctuation that ends a sentence is not part of the handle" {
            mentionedHandles("thanks @alice. ok @bob, and @carol?") shouldBe setOf("alice", "bob", "carol")
        }

        // ----- mentionHandleOf: the same rule as memberHandle of the Angular client --------------------------

        "the handle is the email before its @" {
            mentionHandleOf(member("alice.smith@example.com")) shouldBe "alice.smith"
        }

        "the handle falls back to the name without spaces, then to the user id" {
            mentionHandleOf(member("", firstname = "Alice", lastname = "Smith")) shouldBe "AliceSmith"
            val nameless = member("")
            mentionHandleOf(nameless.copy(externalId = "")) shouldBe nameless.id.toString()
        }

        // ----- notifyMentions ---------------------------------------------------------------------------------

        val caseId = UUID.randomUUID()
        val namespaceId = UUID.randomUUID()
        val alice = member("alice@example.com")
        val bob = member("bob@example.com")
        val author = Actor(id = alice.id.toString(), displayName = "Alice", role = ActorRole.USER)

        fun service(
            members: List<MemberItem>,
            broadcaster: CaseMentionBroadcaster,
        ): CaseMentionService {
            val permissions =
                mockk<PermissionService> {
                    every { listUsersWithPermission(EntityType.CASE, caseId.toString(), PermissionRelation.ADMIN) } returns
                        emptyList()
                    every { listUsersWithPermission(EntityType.CASE, caseId.toString(), PermissionRelation.MEMBER) } returns
                        members.map { it.id.toString() }
                }
            val users =
                mockk<UserService> {
                    every { findByIds(any(), any()) } returns
                        members.map {
                            User(
                                metadata = EntityMetadata(id = it.id),
                                externalId = it.externalId,
                                email = it.email,
                                firstname = it.firstname,
                                lastname = it.lastname,
                            )
                        }
                }
            return CaseMentionService(permissions, users, broadcaster)
        }

        /** The mentions published while [block] runs. */
        suspend fun published(
            broadcaster: CaseMentionBroadcaster,
            block: () -> Unit,
        ): List<CaseMention> =
            coroutineScope {
                val received = mutableListOf<CaseMention>()
                val job =
                    launch(start = CoroutineStart.UNDISPATCHED) {
                        broadcaster.events().collect { received += it }
                    }
                block()
                // The broadcaster buffers: give the collector time to drain it before it is cancelled.
                kotlinx.coroutines.delay(200)
                job.cancel()
                received
            }

        "notifies the member a team message names, with who wrote it and the start of the message" {
            val broadcaster = CaseMentionBroadcaster()
            val sent =
                published(broadcaster) {
                    service(listOf(alice, bob), broadcaster)
                        .notifyMentions(caseId, namespaceId, author, listOf(MessageContent.Text("hello @bob, the report is ready")))
                }

            sent.map { it.userId } shouldBe listOf(bob.id)
            sent.single().byName shouldBe "Alice"
            sent.single().caseId shouldBe caseId
            sent.single().snippet shouldBe "hello @bob, the report is ready"
        }

        "never notifies the author of their own message" {
            val broadcaster = CaseMentionBroadcaster()
            val sent =
                published(broadcaster) {
                    service(listOf(alice, bob), broadcaster)
                        .notifyMentions(caseId, namespaceId, author, listOf(MessageContent.Text("note to self @alice")))
                }

            sent shouldBe emptyList()
        }

        "does not notify anybody for a name that is not a member of the case" {
            val broadcaster = CaseMentionBroadcaster()
            val sent =
                published(broadcaster) {
                    service(listOf(alice, bob), broadcaster)
                        .notifyMentions(caseId, namespaceId, author, listOf(MessageContent.Text("@carol are you there")))
                }

            sent shouldBe emptyList()
        }

        "leaves out the agent a message is addressed to, even when a member has the same handle" {
            val broadcaster = CaseMentionBroadcaster()
            val sent =
                published(broadcaster) {
                    service(listOf(alice, bob), broadcaster)
                        .notifyMentions(
                            caseId,
                            namespaceId,
                            author,
                            listOf(MessageContent.Text("@bob please review")),
                            ignoring = "Bob",
                        )
                }

            sent shouldBe emptyList()
        }

        "a failure to resolve the members never fails the message" {
            val broadcaster = CaseMentionBroadcaster()
            val permissions =
                mockk<PermissionService> {
                    every { listUsersWithPermission(any(), any(), any()) } throws IllegalStateException("store down")
                }
            val users = mockk<UserService>()

            CaseMentionService(permissions, users, broadcaster)
                .notifyMentions(caseId, namespaceId, author, listOf(MessageContent.Text("@bob")))
        }
    })
