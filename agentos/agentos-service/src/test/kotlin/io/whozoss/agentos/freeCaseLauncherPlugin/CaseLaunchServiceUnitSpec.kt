package io.whozoss.agentos.freeCaseLauncherPlugin

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.whozoss.agentos.agentConfig.AgentConfig
import io.whozoss.agentos.agentConfig.AgentConfigService
import io.whozoss.agentos.caseFlow.Case
import io.whozoss.agentos.caseFlow.CaseService
import io.whozoss.agentos.caseFlow.SessionContextKeys
import io.whozoss.agentos.context.UserSessionContextResolver
import io.whozoss.agentos.permissions.Action
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.permissions.PermissionRelation
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.sdk.actor.Actor
import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.caseEvent.MessageContent
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.sdk.scheduledPrompt.UserContextProvider
import io.whozoss.agentos.sdk.scheduledPrompt.UserContextResult
import io.whozoss.agentos.user.User
import io.whozoss.agentos.user.UserService
import java.util.UUID

/**
 * Unit tests for [CaseLaunchService], all driven through its only entry point, [CaseLaunchService.launch]. The
 * service has five collaborators, so each test runs against a whole small world, and changes only what it is about.
 *
 * **The world of a test** (`Fixture`): a `caller` and an `other` user, both members of the namespace, both with
 * the agent `Reporter` deployed, and no `UserContextProvider`. A test changes a permission (`namespacePermission`),
 * a deployment (`deployAgent`), a user lookup (`userService.findById`), or the provider (`resolverAnswering`,
 * `provider`). `request(...)` builds the launch request, from the caller's case, for the caller by default.
 *
 * **What a test observes**: the fake `CaseService` of the fixture records what the service did (`created`, `sent`,
 * `deleted`), and can make each step fail (`createFailure`, `grantFailure`, `sendFailure`, `deleteFailure`).
 * `refusalOf { ... }` runs a launch and returns the [CaseLaunchRefusedException] it throws.
 *
 * **Sections**, in the order the service applies its rules:
 * 1. the case it creates: origin, first message, ADMIN grant;
 * 2. its title;
 * 3. its session context: the caller's, under the one resolved for the target user;
 * 4. agent access and the calling user;
 * 5. another user: allowed by the `allowOtherUsers` switch, no administration right required;
 * 6. failures: thrown, never turned into refusals.
 *
 * A refusal never creates a case.
 */
class CaseLaunchServiceUnitSpec :
    StringSpec({

        val namespaceId: UUID = UUID.randomUUID()
        val callerCaseId: UUID = UUID.randomUUID()

        val caller = User(metadata = EntityMetadata(id = UUID.randomUUID()), externalId = "caller", email = "caller@example.com")
        val other = User(metadata = EntityMetadata(id = UUID.randomUUID()), externalId = "other", email = "other@example.com")

        /** A message sent to a case, as `CaseService.addMessage` received it. */
        data class Sent(
            val caseId: UUID,
            val actor: Actor,
            val content: List<MessageContent>,
            val sessionContext: Map<String, Any?>?,
        )

        class Fixture(
            userSessionContextResolver: UserSessionContextResolver = UserSessionContextResolver(),
        ) {
            val created = mutableListOf<Case>()
            val sent = mutableListOf<Sent>()
            val deleted = mutableListOf<UUID>()
            var createFailure: Throwable? = null
            var grantFailure: Throwable? = null
            var sendFailure: Throwable? = null
            var deleteFailure: Throwable? = null

            val userService = mockk<UserService>()
            val agentConfigService = mockk<AgentConfigService>()
            val permissionService = mockk<PermissionService>()
            val caseService =
                mockk<CaseService> {
                    every { create(any()) } answers {
                        createFailure?.let { throw it }
                        firstArg<Case>().also { created += it }
                    }
                    every { addMessage(any(), any(), any(), any(), any()) } answers {
                        sendFailure?.let { throw it }
                        sent += Sent(arg(0), arg(1), arg(2), arg(4))
                    }
                    every { delete(any()) } answers {
                        deleteFailure?.let { throw it }
                        deleted += firstArg<UUID>()
                        true
                    }
                }
            val service =
                CaseLaunchService(
                    caseService = caseService,
                    userService = userService,
                    agentConfigService = agentConfigService,
                    permissionService = permissionService,
                    userSessionContextResolver = userSessionContextResolver,
                )

            init {
                every { userService.findById(caller.id) } returns caller
                every { userService.findById(other.id) } returns other
                every { permissionService.grantPermission(any(), any(), any(), any()) } answers {
                    grantFailure?.let { throw it }
                    Unit
                }
                // Default world: both users read the namespace, both have 'Reporter'.
                namespacePermission(caller, Action.READ, true)
                namespacePermission(other, Action.READ, true)
                deployAgent(caller, "Reporter")
                deployAgent(other, "Reporter")
            }

            fun namespacePermission(
                user: User,
                action: Action,
                allowed: Boolean,
            ) {
                every {
                    permissionService.hasPermission(user.id.toString(), EntityType.NAMESPACE, namespaceId.toString(), action)
                } returns allowed
            }

            fun deployAgent(
                user: User,
                vararg names: String,
            ) {
                every {
                    agentConfigService.findDeployedByNamespaceIdAndUserIdAndName(namespaceId, user.id, any())
                } returns names.map { AgentConfig(namespaceId = namespaceId, name = it) }
            }
        }

        fun request(
            agentName: String = "Reporter",
            prompt: String = "Summarise the week",
            targetUserId: UUID? = null,
            title: String? = null,
            sessionContext: Map<String, Any?>? = null,
            allowOtherUsers: Boolean = false,
        ) = CaseLaunchRequest(
            callerUserId = caller.id,
            callerNamespaceId = namespaceId,
            callerCaseId = callerCaseId,
            agentName = agentName,
            prompt = prompt,
            targetUserId = targetUserId,
            title = title,
            sessionContext = sessionContext,
            allowOtherUsers = allowOtherUsers,
        )

        /** Runs [launch] and returns the refusal it throws; fails if it does not refuse. */
        fun refusalOf(launch: () -> LaunchedCase): CaseLaunchRefusedException = shouldThrow<CaseLaunchRefusedException> { launch() }

        /** A provider answering [answer] for any user; the calls it received are recorded in [asked]. */
        fun provider(
            asked: MutableList<Pair<String, UUID>> = mutableListOf(),
            answer: (String) -> UserContextResult,
        ): UserContextProvider =
            mockk {
                every { provideUserContext(any(), any()) } answers {
                    asked += firstArg<String>() to secondArg<UUID>()
                    answer(firstArg())
                }
            }

        /** A resolver whose provider answers [result] for any user. */
        fun resolverAnswering(result: UserContextResult) = UserSessionContextResolver(provider { result })

        // -------------------------------------------------------------------------
        // The case it creates
        // -------------------------------------------------------------------------

        "creates the case in the calling case's namespace, with the calling case as its origin, and returns its id" {
            val f = Fixture()

            val launched = f.service.launch(request())

            val case = f.created.single()
            launched.caseId shouldBe case.id
            launched.userId shouldBe caller.id
            case.originCaseId shouldBe callerCaseId
            case.namespaceId shouldBe namespaceId
        }

        "the new case is a top-level case, not a sub-case of the calling one" {
            val f = Fixture()

            f.service.launch(request())

            f.created.single().parentCaseId shouldBe null
        }

        "the first message is the prompt addressed to the agent, sent as the user the case runs for" {
            val f = Fixture()

            f.service.launch(request(prompt = "Summarise the week"))

            f.sent.single().also {
                it.caseId shouldBe f.created.single().id
                it.actor.id shouldBe caller.id.toString()
                it.actor.role shouldBe ActorRole.USER
                it.content shouldBe listOf(MessageContent.Text("@Reporter Summarise the week"))
            }
        }

        "the user the case runs for is given ADMIN on it" {
            val f = Fixture()

            f.service.launch(request())

            verify(exactly = 1) {
                f.permissionService.grantPermission(
                    caller.id.toString(),
                    EntityType.CASE,
                    f.created.single().id.toString(),
                    PermissionRelation.ADMIN,
                )
            }
        }

        "the agent is addressed by its canonical name, whatever the case it was requested in" {
            val f = Fixture()

            val launched = f.service.launch(request(agentName = "reporter"))

            f.sent.single().content shouldBe listOf(MessageContent.Text("@Reporter Summarise the week"))
            launched.agentName shouldBe "Reporter"
        }

        // -------------------------------------------------------------------------
        // Title
        // -------------------------------------------------------------------------

        "the title is the given one" {
            val f = Fixture()

            f.service.launch(request(title = "Weekly review"))

            f.created.single().title shouldBe "Weekly review"
        }

        "without a title, or with a blank one, it is derived from the prompt" {
            val f = Fixture()

            f.service.launch(request(prompt = "Summarise the week"))
            f.service.launch(request(prompt = "Summarise the week", title = "   "))

            f.created.map { it.title } shouldBe listOf("Summarise the week", "Summarise the week")
        }

        "the title is capped at 80 characters, given or derived" {
            val f = Fixture()

            f.service.launch(request(title = "t".repeat(200)))
            f.service.launch(request(prompt = "x".repeat(200)))

            f.created.map { it.title } shouldBe listOf("t".repeat(80), "x".repeat(80))
        }

        // -------------------------------------------------------------------------
        // Session context: the caller's, under the one resolved for the target user
        // -------------------------------------------------------------------------

        "without a context the first message carries no session context" {
            val f = Fixture()

            f.service.launch(request())

            f.sent.single().sessionContext shouldBe null
        }

        "an empty context counts as none" {
            val f = Fixture()

            f.service.launch(request(sessionContext = emptyMap()))

            f.sent.single().sessionContext shouldBe null
        }

        "when nothing is resolved, the context handed over is the session context of the first message, as given" {
            val f = Fixture()
            val callerContext =
                mapOf("activeContext" to listOf(mapOf("type" to "TALENT", "data" to mapOf("id" to "t42"))))

            f.service.launch(request(sessionContext = callerContext))

            f.sent.single().sessionContext shouldBe callerContext
        }

        "the target user's context is resolved from the provider, in the namespace of the calling case" {
            val asked = mutableListOf<Pair<String, UUID>>()
            val providerContext = mapOf("userContext" to mapOf("talentId" to "t1"), "description" to "d")
            val f = Fixture(UserSessionContextResolver(provider(asked) { UserContextResult.Success(providerContext) }))

            f.service.launch(request())

            asked shouldBe listOf(caller.externalId to namespaceId)
            f.sent.single().sessionContext shouldBe providerContext
        }

        "for another user it is the target's context that is resolved, not the caller's" {
            val asked = mutableListOf<Pair<String, UUID>>()
            val f =
                Fixture(
                    UserSessionContextResolver(
                        provider(asked) { externalId -> UserContextResult.Success(mapOf("who" to externalId)) },
                    ),
                )

            f.service.launch(request(targetUserId = other.id, allowOtherUsers = true))

            asked shouldBe listOf(other.externalId to namespaceId)
            f.sent.single().sessionContext shouldBe mapOf("who" to other.externalId)
        }

        "the preferred language of the user the case runs for is added to the context, without any provider" {
            val f = Fixture()
            every { f.userService.findById(other.id) } returns other.copy(preferredLanguage = "fr")

            f.service.launch(request(targetUserId = other.id, allowOtherUsers = true))

            f.sent.single().sessionContext shouldBe mapOf(SessionContextKeys.PREFERRED_LANGUAGE to "fr")
        }

        "the caller's context is kept next to the resolved one" {
            val f = Fixture(resolverAnswering(UserContextResult.Success(mapOf("userContext" to "u"))))
            val entity = mapOf("activeContext" to listOf(mapOf("type" to "TALENT", "data" to mapOf("id" to "t42"))))

            f.service.launch(request(sessionContext = entity))

            f.sent.single().sessionContext shouldBe entity + mapOf("userContext" to "u")
        }

        "the resolved context wins over a key the caller also gives, and the caller's other keys are kept" {
            val f = Fixture(resolverAnswering(UserContextResult.Success(mapOf("userContext" to "real"))))
            every { f.userService.findById(caller.id) } returns caller.copy(preferredLanguage = "fr")
            val callerContext =
                mapOf("userContext" to "from the caller", SessionContextKeys.PREFERRED_LANGUAGE to "de", "kept" to 1)

            f.service.launch(request(sessionContext = callerContext))

            f.sent.single().sessionContext shouldBe
                    mapOf("userContext" to "real", SessionContextKeys.PREFERRED_LANGUAGE to "fr", "kept" to 1)
        }

        "a key the resolution does not produce is passed on as the caller gave it" {
            // No provider answer and no preferred language: nothing overrides the caller's keys.
            val f = Fixture(resolverAnswering(UserContextResult.Success(null)))
            val callerContext =
                mapOf("userContext" to mapOf("firstName" to "Alice"), SessionContextKeys.PREFERRED_LANGUAGE to "de")

            f.service.launch(request(sessionContext = callerContext))

            f.sent.single().sessionContext shouldBe callerContext
        }

        "the service does not look into the caller's context: any key and any shape is passed on as given" {
            val f = Fixture()
            val callerContext =
                mapOf(
                    "activeContext" to listOf("just a string", mapOf("type" to 42), null),
                    "anything" to mapOf("nested" to listOf(1, 2)),
                )

            f.service.launch(request(sessionContext = callerContext))

            f.sent.single().sessionContext shouldBe callerContext
        }

        "a provider with nothing to say and no preferred language leaves no session context" {
            val f = Fixture(resolverAnswering(UserContextResult.Success(null)))

            f.service.launch(request())

            f.sent.single().sessionContext shouldBe null
        }

        "the provider is never called for a launch refused before it: it may be an external request" {
            val asked = mutableListOf<Pair<String, UUID>>()
            val f = Fixture(UserSessionContextResolver(provider(asked) { UserContextResult.Success(null) }))
            f.deployAgent(caller, "SomethingElse")

            refusalOf { f.service.launch(request()) }.reason shouldBe CaseLaunchRefusal.AGENT_NOT_AVAILABLE
            refusalOf { f.service.launch(request(targetUserId = other.id)) }.reason shouldBe
                    CaseLaunchRefusal.OTHER_USERS_NOT_ALLOWED

            asked shouldBe emptyList()
        }

        // -------------------------------------------------------------------------
        // Agent access and calling user
        // -------------------------------------------------------------------------

        "refuses an agent that is not deployed to the user, including a prefix-only match" {
            val f = Fixture()
            f.deployAgent(caller, "Reporter-v2")

            refusalOf { f.service.launch(request()) }.reason shouldBe CaseLaunchRefusal.AGENT_NOT_AVAILABLE
            f.created shouldBe emptyList()
        }

        "a calling user that no longer exists is a bug, not a refusal: it is thrown" {
            val f = Fixture()
            every { f.userService.findById(caller.id) } returns null

            shouldThrow<IllegalStateException> { f.service.launch(request()) }
            f.created shouldBe emptyList()
        }

        // -------------------------------------------------------------------------
        // Another user: allowed by the switch, no administration right required
        // -------------------------------------------------------------------------

        "a refusal carries its reason and a message for the LLM, and no stack trace: it is control flow" {
            val f = Fixture()
            f.deployAgent(caller, "SomethingElse")

            val refusal = refusalOf { f.service.launch(request(agentName = "Reporter")) }

            refusal.reason shouldBe CaseLaunchRefusal.AGENT_NOT_AVAILABLE
            refusal.message shouldContain "Reporter"
            refusal.stackTrace.size shouldBe 0
        }

        "another user is refused unless the request allows other users, before the target is even looked up" {
            val f = Fixture()

            refusalOf { f.service.launch(request(targetUserId = other.id)) }.reason shouldBe
                    CaseLaunchRefusal.OTHER_USERS_NOT_ALLOWED
            f.created shouldBe emptyList()
            verify(exactly = 0) { f.userService.findById(other.id) }
        }

        "a plain member can start a case for another user, who is the one given ADMIN on it" {
            val f = Fixture()

            val launched = f.service.launch(request(targetUserId = other.id, allowOtherUsers = true))

            launched.userId shouldBe other.id
            f.sent.single().actor.id shouldBe other.id.toString()
            // The origin is still the calling case, whoever the new case runs for.
            f.created.single().originCaseId shouldBe callerCaseId
            verify(exactly = 1) {
                f.permissionService.grantPermission(
                    other.id.toString(),
                    EntityType.CASE,
                    f.created.single().id.toString(),
                    PermissionRelation.ADMIN,
                )
            }
            verify(exactly = 0) {
                f.permissionService.grantPermission(caller.id.toString(), any(), any(), any())
            }
        }

        "the target user must still read the namespace" {
            val f = Fixture()
            f.namespacePermission(other, Action.READ, false)

            refusalOf { f.service.launch(request(targetUserId = other.id, allowOtherUsers = true)) }.reason shouldBe
                    CaseLaunchRefusal.TARGET_NO_ACCESS
            f.created shouldBe emptyList()
        }

        "the target user must still have the agent deployed to them" {
            val f = Fixture()
            f.deployAgent(other, "SomethingElse")

            refusalOf { f.service.launch(request(targetUserId = other.id, allowOtherUsers = true)) }.reason shouldBe
                    CaseLaunchRefusal.AGENT_NOT_AVAILABLE
            f.created shouldBe emptyList()
        }

        "an unknown target user is refused" {
            val f = Fixture()
            val ghost = UUID.randomUUID()
            every { f.userService.findById(ghost) } returns null

            refusalOf { f.service.launch(request(targetUserId = ghost, allowOtherUsers = true)) }.reason shouldBe
                    CaseLaunchRefusal.TARGET_USER_NOT_FOUND
            f.created shouldBe emptyList()
        }

        "naming the caller itself is not 'another user': it needs neither the switch nor a namespace check" {
            val f = Fixture()

            f.service.launch(request(targetUserId = caller.id))

            f.sent.single().actor.id shouldBe caller.id.toString()
            verify(exactly = 0) {
                f.permissionService.hasPermission(any(), EntityType.NAMESPACE, any(), any())
            }
        }

        // -------------------------------------------------------------------------
        // Failures: thrown as they are, never turned into a refusal
        // -------------------------------------------------------------------------

        "a failure to look a user up is thrown as is, before anything is created" {
            val f = Fixture()
            every { f.userService.findById(caller.id) } throws IllegalStateException("Neo4j unavailable")

            shouldThrow<IllegalStateException> { f.service.launch(request()) }.message shouldBe "Neo4j unavailable"
            f.created shouldBe emptyList()
        }

        "a failure to create the case is thrown as is, and nothing else happens" {
            val f = Fixture()
            f.createFailure = IllegalStateException("boom")

            shouldThrow<IllegalStateException> { f.service.launch(request()) }.message shouldBe "boom"
            f.sent shouldBe emptyList()
            verify(exactly = 0) { f.permissionService.grantPermission(any(), any(), any(), any()) }
        }

        "when the ADMIN grant fails the case is deleted, no message is sent, and the failure is thrown" {
            val f = Fixture()
            f.grantFailure = RuntimeException("Neo4j unavailable")

            shouldThrow<RuntimeException> { f.service.launch(request()) }.message shouldBe "Neo4j unavailable"
            f.deleted shouldBe listOf(f.created.single().id)
            f.sent shouldBe emptyList()
        }

        "when the first message cannot be sent the case is deleted and the failure is thrown" {
            val f = Fixture()
            f.sendFailure = IllegalStateException("boom")

            shouldThrow<IllegalStateException> { f.service.launch(request()) }.message shouldBe "boom"
            f.deleted shouldBe listOf(f.created.single().id)
        }

        "the original failure is thrown even when deleting the half-started case fails too" {
            val f = Fixture()
            f.grantFailure = RuntimeException("grant failed")
            f.deleteFailure = RuntimeException("delete also failed")

            shouldThrow<RuntimeException> { f.service.launch(request()) }.message shouldBe "grant failed"
        }

        "a permanent failure to resolve the target user's context is thrown, before anything is created" {
            val f = Fixture(resolverAnswering(UserContextResult.PermanentFailure("404 secret upstream body")))

            val failure = shouldThrow<UserContextUnavailableException> { f.service.launch(request()) }

            // the upstream reason is for the logs, not for the LLM, which sees the exception's message
            failure.message shouldNotContain "secret"
            f.created shouldBe emptyList()
            f.sent shouldBe emptyList()
            verify(exactly = 0) { f.permissionService.grantPermission(any(), any(), any(), any()) }
        }

        "a transient failure to resolve it is thrown too, and says it may succeed later" {
            val f = Fixture(resolverAnswering(UserContextResult.TransientFailure("503")))

            shouldThrow<UserContextUnavailableException> { f.service.launch(request()) }.message shouldContain
                    "may succeed later"
            f.created shouldBe emptyList()
        }

        "a provider that throws fails the launch the same way: the resolver turns it into a transient failure" {
            val throwing =
                mockk<UserContextProvider> {
                    every { provideUserContext(any(), any()) } throws RuntimeException("connection reset")
                }
            val f = Fixture(UserSessionContextResolver(throwing))

            shouldThrow<UserContextUnavailableException> { f.service.launch(request()) }
            f.created shouldBe emptyList()
        }
    })
