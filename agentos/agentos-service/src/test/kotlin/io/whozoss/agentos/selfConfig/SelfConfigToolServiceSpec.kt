package io.whozoss.agentos.selfConfig

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.whozoss.agentos.exception.BadRequestException
import io.whozoss.agentos.exception.ConflictException
import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.exception.UnprocessableEntityException
import io.whozoss.agentos.integrationConfig.IntegrationConfig
import io.whozoss.agentos.integrationConfig.IntegrationConfigService
import io.whozoss.agentos.integrationConfig.IntegrationConfigUpdate
import io.whozoss.agentos.integrationConfig.IntegrationTypeRegistry
import io.whozoss.agentos.permissions.Action
import io.whozoss.agentos.permissions.Caller
import io.whozoss.agentos.sdk.caseEvent.ThinkingEvent
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.user.User
import io.whozoss.agentos.user.UserService
import org.springframework.security.access.AccessDeniedException
import java.util.UUID

/**
 * Unit tests for [SelfConfigToolService] -- the one place that decides, for the `SELF_CONFIG`
 * integration, *who* is acting and *what* they may touch.
 *
 * Two properties are asserted repeatedly because they are the ones that protect data rather than
 * merely shape it:
 * - the [Caller] handed to [IntegrationConfigService] is a [Caller.Agent] built from the run's
 *   own [ToolContext], never from an ambient request identity;
 * - no write ever reaches a configuration the calling user does not own.
 *
 * [IntegrationConfigService] is deliberately NOT a relaxed mock. Beyond the usual "state what the
 * collaborator does" discipline, its own KDoc documents a trap: `findByTriple`'s erased signature
 * makes a relaxed auto-stub hand back a child mock of `Entity`, which fails later with a
 * `ClassCastException` naming neither the mock nor the erasure.
 */
class SelfConfigToolServiceSpec : StringSpec({

    val mapper = jacksonObjectMapper()

    val userId = UUID.randomUUID()
    val otherUserId = UUID.randomUUID()
    val namespaceId = UUID.randomUUID()
    val caseId = UUID.randomUUID()

    fun user(isAdmin: Boolean = false) =
        User(metadata = EntityMetadata(id = userId), externalId = "alice@example.com", isAdmin = isAdmin)

    /** A context of a live run: [ToolContext.caseId] is derived from the first case event. */
    fun context(withUser: Boolean = true) =
        ToolContext(
            namespaceId = namespaceId,
            userId = if (withUser) userId else null,
            userExternalId = if (withUser) "alice@example.com" else null,
            caseEvents = listOf(ThinkingEvent(namespaceId = namespaceId, caseId = caseId)),
            agentName = "Configurator",
        )

    fun config(
        id: UUID = UUID.randomUUID(),
        owner: UUID? = userId,
        nsId: UUID? = namespaceId,
        name: String = "MY_JIRA",
        authSettingName: String? = "jira-token",
        autoGrant: Boolean = false,
    ) = IntegrationConfig(
        metadata = EntityMetadata(id = id),
        namespaceId = nsId,
        userId = owner,
        name = name,
        integrationType = "HTTP_API",
        description = "my jira",
        parameters = mapper.readTree("""{"baseUrl":"https://jira.example.com","project":"ABC"}"""),
        authSettingName = authSettingName,
        autoGrant = autoGrant,
    )

    fun service(
        configs: IntegrationConfigService,
        users: UserService = mockk<UserService>().also { every { it.findById(userId, any()) } returns user() },
        registry: IntegrationTypeRegistry = mockk(),
    ) = SelfConfigToolService(configs, users, registry)

    // -------------------------------------------------------------------------
    // Fail-closed: a run with no identified user
    // -------------------------------------------------------------------------

    "every operation is refused, and the config service never touched, without an identified user" {
        val configs = mockk<IntegrationConfigService>()
        val users = mockk<UserService>()
        val subject = service(configs, users)
        val ctx = context(withUser = false)

        val outcomes =
            listOf(
                subject.listOwn(ctx),
                subject.create(ctx, "X", "DATETIME", null, null, namespaceScoped = true),
                subject.update(ctx, UUID.randomUUID(), "X", null, null),
                subject.delete(ctx, UUID.randomUUID()),
            )

        outcomes.forEach { outcome ->
            (outcome as SelfConfigOutcome.Failure).errorType shouldBe SelfConfigErrors.NOT_AUTHENTICATED
        }
        verify(exactly = 0) { configs.findFilteredForCaller(any(), any(), any(), any()) }
        verify(exactly = 0) { configs.createForCaller(any(), any()) }
        verify(exactly = 0) { configs.getByIdForCaller(any(), any(), any(), any()) }
        verify(exactly = 0) { configs.updateForCaller(any(), any(), any()) }
        verify(exactly = 0) { configs.deleteForCaller(any(), any()) }
        verify(exactly = 0) { users.getCurrentUser() }
    }

    "a user id that resolves to no record is treated as no user at all" {
        val configs = mockk<IntegrationConfigService>()
        val users = mockk<UserService>()
        every { users.findById(userId, any()) } returns null

        val outcome = service(configs, users).listOwn(context())

        (outcome as SelfConfigOutcome.Failure).errorType shouldBe SelfConfigErrors.NOT_AUTHENTICATED
        verify(exactly = 0) { configs.findFilteredForCaller(any(), any(), any(), any()) }
    }

    // -------------------------------------------------------------------------
    // The principal handed to the service
    // -------------------------------------------------------------------------

    "the caller is an Agent carrying the run's identity, namespace and case" {
        val configs = mockk<IntegrationConfigService>()
        val users = mockk<UserService>()
        every { users.findById(userId, any()) } returns user(isAdmin = true)
        val caller = slot<Caller>()
        every { configs.findFilteredForCaller(capture(caller), any(), any(), any()) } returns emptyList()

        service(configs, users).listOwn(context())

        val captured = caller.captured
        captured.shouldBeAgent(userId = userId, namespaceId = namespaceId, caseId = caseId, isAdmin = true)
        verify(exactly = 0) { users.getCurrentUser() }
    }

    "listOwn asks for the caller's own rows across every scope" {
        val configs = mockk<IntegrationConfigService>()
        val own = listOf(config(name = "A"), config(nsId = null, name = "B"))
        every { configs.findFilteredForCaller(any(), null, false, true) } returns own

        val outcome = service(configs).listOwn(context())

        (outcome as SelfConfigOutcome.Ok).value shouldContainExactly own
    }

    // -------------------------------------------------------------------------
    // create
    // -------------------------------------------------------------------------

    "create forces the caller as owner, with no credential binding and no auto-grant" {
        val configs = mockk<IntegrationConfigService>()
        val entity = slot<IntegrationConfig>()
        every { configs.createForCaller(any(), capture(entity)) } answers { entity.captured }

        val parameters = mapper.readTree("""{"defaultTimezone":"Europe/Paris"}""")
        val outcome =
            service(configs).create(
                context = context(),
                name = "MY_CLOCK",
                integrationType = "DATETIME",
                description = "paris time",
                parameters = parameters,
                namespaceScoped = true,
            )

        outcome.shouldBeOk()
        entity.captured.userId shouldBe userId
        entity.captured.namespaceId shouldBe namespaceId
        entity.captured.name shouldBe "MY_CLOCK"
        entity.captured.integrationType shouldBe "DATETIME"
        entity.captured.parameters shouldBe parameters
        // Not exposed to the LLM, and forced here so a future input field cannot smuggle them in.
        entity.captured.authSettingName shouldBe null
        entity.captured.autoGrant shouldBe false
    }

    "a global scope detaches the configuration from the run's namespace" {
        val configs = mockk<IntegrationConfigService>()
        val entity = slot<IntegrationConfig>()
        every { configs.createForCaller(any(), capture(entity)) } answers { entity.captured }

        service(configs).create(context(), "MY_CLOCK", "DATETIME", null, null, namespaceScoped = false)

        entity.captured.namespaceId shouldBe null
        entity.captured.userId shouldBe userId
    }

    // -------------------------------------------------------------------------
    // update -- the regression that matters
    // -------------------------------------------------------------------------

    "update preserves every field the agent did not supply" {
        val configs = mockk<IntegrationConfigService>()
        val existing = config(authSettingName = "jira-token", autoGrant = true)
        every { configs.getByIdForCaller(any(), existing.id, Action.READ, any()) } returns existing
        val changes = slot<IntegrationConfigUpdate>()
        every { configs.updateForCaller(any(), existing.id, capture(changes)) } returns existing

        val newParameters = mapper.readTree("""{"baseUrl":"https://jira.example.com","project":"XYZ"}""")
        service(configs).update(context(), existing.id, name = null, description = null, parameters = newParameters)

        // IntegrationConfigUpdate is a full replacement: an omitted field must be refilled from the
        // persisted row, or the credential binding silently disappears.
        changes.captured.name shouldBe existing.name
        changes.captured.description shouldBe existing.description
        changes.captured.authSettingName shouldBe "jira-token"
        changes.captured.autoGrant shouldBe true
        changes.captured.parameters shouldBe newParameters
    }

    "update applies the fields the agent did supply" {
        val configs = mockk<IntegrationConfigService>()
        val existing = config()
        every { configs.getByIdForCaller(any(), existing.id, Action.READ, any()) } returns existing
        val changes = slot<IntegrationConfigUpdate>()
        every { configs.updateForCaller(any(), existing.id, capture(changes)) } returns existing

        service(configs).update(context(), existing.id, name = "RENAMED", description = "new", parameters = null)

        changes.captured.name shouldBe "RENAMED"
        changes.captured.description shouldBe "new"
        changes.captured.parameters shouldBe existing.parameters
    }

    // -------------------------------------------------------------------------
    // Ownership boundary
    // -------------------------------------------------------------------------

    listOf(
        "a platform configuration" to config(owner = null, nsId = null),
        "a namespace-shared configuration" to config(owner = null),
        "another user's configuration" to config(owner = otherUserId),
    ).forEach { (label, shared) ->
        "update refuses $label without reaching the service" {
            val configs = mockk<IntegrationConfigService>()
            every { configs.getByIdForCaller(any(), shared.id, Action.READ, any()) } returns shared

            val outcome = service(configs).update(context(), shared.id, "X", null, null)

            (outcome as SelfConfigOutcome.Failure).errorType shouldBe SelfConfigErrors.SCOPE_NOT_ALLOWED
            verify(exactly = 0) { configs.updateForCaller(any(), any(), any()) }
        }

        "delete refuses $label without reaching the service" {
            val configs = mockk<IntegrationConfigService>()
            every { configs.getByIdForCaller(any(), shared.id, Action.READ, any()) } returns shared

            val outcome = service(configs).delete(context(), shared.id)

            (outcome as SelfConfigOutcome.Failure).errorType shouldBe SelfConfigErrors.SCOPE_NOT_ALLOWED
            verify(exactly = 0) { configs.deleteForCaller(any(), any()) }
        }
    }

    "an admin gets no extra reach: ownership is checked on the row, not on the privilege" {
        val configs = mockk<IntegrationConfigService>()
        val users = mockk<UserService>()
        every { users.findById(userId, any()) } returns user(isAdmin = true)
        val shared = config(owner = null)
        every { configs.getByIdForCaller(any(), shared.id, Action.READ, any()) } returns shared

        val outcome = service(configs, users).update(context(), shared.id, "X", null, null)

        (outcome as SelfConfigOutcome.Failure).errorType shouldBe SelfConfigErrors.SCOPE_NOT_ALLOWED
        verify(exactly = 0) { configs.updateForCaller(any(), any(), any()) }
    }

    "delete returns the deleted id for a configuration the user owns" {
        val configs = mockk<IntegrationConfigService>()
        val own = config()
        every { configs.getByIdForCaller(any(), own.id, Action.READ, any()) } returns own
        every { configs.deleteForCaller(any(), own.id) } returns Unit

        val outcome = service(configs).delete(context(), own.id)

        (outcome as SelfConfigOutcome.Ok).value shouldBe own.id
        verify(exactly = 1) { configs.deleteForCaller(any(), own.id) }
    }

    // -------------------------------------------------------------------------
    // Exception mapping -- nothing escapes into the agent run
    // -------------------------------------------------------------------------

    listOf(
        ResourceNotFoundException("gone") to SelfConfigErrors.NOT_FOUND,
        AccessDeniedException("nope") to SelfConfigErrors.ACCESS_DENIED,
        ConflictException("already exists in this scope") to SelfConfigErrors.CONFLICT,
        UnprocessableEntityException("cannot be auto-granted") to SelfConfigErrors.INVALID_INPUT,
        BadRequestException("must provide namespaceId") to SelfConfigErrors.INVALID_INPUT,
        IllegalStateException("boom") to SelfConfigErrors.UNEXPECTED,
    ).forEach { (thrown, expectedErrorType) ->
        "${thrown::class.simpleName} is reported as $expectedErrorType rather than propagated" {
            val configs = mockk<IntegrationConfigService>()
            every { configs.createForCaller(any(), any()) } throws thrown

            val outcome = service(configs).create(context(), "X", "DATETIME", null, null, true)

            (outcome as SelfConfigOutcome.Failure).errorType shouldBe expectedErrorType
        }
    }

    "a conflict message reaches the agent verbatim: it is the feedback it corrects from" {
        val configs = mockk<IntegrationConfigService>()
        every {
            configs.createForCaller(any(), any())
        } throws ConflictException("An integration config named 'MY_JIRA' already exists for this scope")

        val outcome = service(configs).create(context(), "MY_JIRA", "HTTP_API", null, null, true)

        (outcome as SelfConfigOutcome.Failure).message shouldBe
            "An integration config named 'MY_JIRA' already exists for this scope"
    }

    "an access-denied message is NOT echoed: it names scopes and policies the agent has no use for" {
        val configs = mockk<IntegrationConfigService>()
        every {
            configs.createForCaller(any(), any())
        } throws AccessDeniedException("listed in agentos.integrations.user-scope-denied-types")

        val outcome = service(configs).create(context(), "MY_JIRA", "HTTP_API", null, null, true)

        (outcome as SelfConfigOutcome.Failure).message shouldBe
            "The user is not allowed to perform this operation on that integration configuration."
    }
})

private fun Caller.shouldBeAgent(
    userId: UUID,
    namespaceId: UUID,
    caseId: UUID,
    isAdmin: Boolean,
) {
    val agent = this as Caller.Agent
    agent.userId shouldBe userId
    agent.namespaceId shouldBe namespaceId
    agent.caseId shouldBe caseId
    agent.isAdmin shouldBe isAdmin
    agent.isAgent shouldBe true
}

private fun SelfConfigOutcome<*>.shouldBeOk() {
    (this is SelfConfigOutcome.Ok<*>) shouldBe true
}
