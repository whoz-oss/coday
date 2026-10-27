package io.whozoss.agentos.selfConfig

import com.fasterxml.jackson.databind.JsonNode
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
import io.whozoss.agentos.sdk.api.integrationConfig.IntegrationTypeDescriptor
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.user.UserService
import mu.KLogging
import org.springframework.security.access.AccessDeniedException
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Service backing the `SELF_CONFIG` tool integration: everything an agent may do with the
 * **calling user's own** [IntegrationConfig] overlays.
 *
 * All authorization and business logic lives here; [SelfConfigToolPlugin] and
 * [SelfConfigToolPluginConfiguration] hold none. Same division as
 * [io.whozoss.agentos.userGroup.UserGroupToolService] and its configuration.
 *
 * ## Only the `*ForCaller` surface
 *
 * Every read and write goes through [IntegrationConfigService.findFilteredForCaller],
 * [IntegrationConfigService.getByIdForCaller], [IntegrationConfigService.createForCaller],
 * [IntegrationConfigService.updateForCaller] and [IntegrationConfigService.deleteForCaller].
 * The `unsecured*` methods and the inherited [io.whozoss.agentos.entity.EntityService] methods are
 * unchecked composition: that service's KDoc states in as many words that an agent tool calling
 * one of them is a bug.
 *
 * ## `Caller.Agent`, built from the [ToolContext]
 *
 * [callerOf] builds a [Caller.Agent] from [ToolContext.userId] and [ToolContext.namespaceId],
 * never from [UserService.getCurrentUser]. That method resolves the identity of the current HTTP
 * **request** through a thread-local the agent run does not carry: an agent run dispatches across
 * `Dispatchers.IO` threads, where that thread-local is either absent or belongs to an unrelated
 * request. See the KDoc of [Caller] for the full rationale.
 *
 * ## Fail-closed
 *
 * A run with no resolved user (webhook, scheduled prompt, system call) gets
 * [SelfConfigErrors.NOT_AUTHENTICATED] on every operation, the read ones included. There is no
 * current user to act on behalf of, and guessing one would mean writing configuration under an
 * identity nobody chose.
 *
 * ## User-owned overlays only
 *
 * [update] and [delete] refuse any row whose [IntegrationConfig.userId] is not the caller's --
 * platform rows (`userId == null`) and namespace-shared rows alike -- with
 * [SelfConfigErrors.SCOPE_NOT_ALLOWED], **before** reaching the service.
 *
 * The service alone would be more permissive: a namespace admin holds WRITE on their namespace's
 * shared rows, so [IntegrationConfigService.updateForCaller] would accept the call. That is right
 * for a human clicking in the UI and wrong for an LLM holding that human's identity -- the blast
 * radius of a misread instruction would be every agent of the namespace instead of one user's own
 * runs. The narrower rule is this layer's own, deliberately not pushed down into the service,
 * which must keep serving the UI path unchanged.
 *
 * That same boundary is what makes it safe for [listOwn] to return `parameters` in clear text:
 * every row it can reach belongs to the caller.
 */
@Service
class SelfConfigToolService(
    private val integrationConfigService: IntegrationConfigService,
    private val userService: UserService,
    private val integrationTypeRegistry: IntegrationTypeRegistry,
) {
    /**
     * The integration-type catalogue, configuration schemas included -- the schema is the point:
     * it is how the agent learns which parameters a type expects.
     */
    fun listTypes(): List<IntegrationTypeDescriptor> = integrationTypeRegistry.listTypes()

    /**
     * Every overlay the calling user owns, across both user scopes (user-global and
     * user x namespace).
     *
     * `userRequested = true` with no namespace filter is exactly the `?userId=me` listing mode of
     * [io.whozoss.agentos.integrationConfig.IntegrationConfigController.list]: the service answers
     * with the caller's own rows and nothing else, so the user-owned boundary is enforced by the
     * mode itself, not only by the checks in this class.
     */
    fun listOwn(context: ToolContext): SelfConfigOutcome<List<IntegrationConfig>> {
        val caller = callerOf(context) ?: return notAuthenticated()
        return guarded {
            integrationConfigService.findFilteredForCaller(
                caller = caller,
                namespaceId = null,
                namespaceIsNone = false,
                userRequested = true,
            )
        }
    }

    /**
     * Creates an overlay owned by the calling user.
     *
     * [namespaceScoped] picks between the two user scopes: `true` gives a user x namespace row
     * bound to the run's own namespace, `false` a user-global one that follows the user
     * everywhere. The namespace comes from the run, never from the LLM -- an agent has no business
     * configuring a namespace it is not running in.
     *
     * `userId` is forced to the caller's. `authSettingName` and `autoGrant` are forced to their
     * neutral values and are deliberately **not** exposed as inputs: binding a stored credential
     * to a configuration, or handing a tool to every agent in scope, are not self-service
     * operations. Both remain available to a human through the REST API and the UI.
     */
    fun create(
        context: ToolContext,
        name: String,
        integrationType: String,
        description: String?,
        parameters: JsonNode?,
        namespaceScoped: Boolean,
    ): SelfConfigOutcome<IntegrationConfig> {
        val caller = callerOf(context) ?: return notAuthenticated()
        val entity =
            IntegrationConfig(
                metadata = EntityMetadata(id = UUID.randomUUID()),
                namespaceId = if (namespaceScoped) context.namespaceId else null,
                userId = caller.userId,
                name = name,
                integrationType = integrationType,
                description = description,
                parameters = parameters,
                authSettingName = null,
                autoGrant = false,
            )
        return guarded { integrationConfigService.createForCaller(caller, entity) }
    }

    /**
     * Updates an overlay owned by the calling user.
     *
     * ## Why the persisted row is loaded first
     *
     * [IntegrationConfigUpdate] is a **full replacement**, not a patch: `name` is non-nullable and
     * `description`, `parameters`, `authSettingName` and `autoGrant` are written to the entity
     * verbatim by [IntegrationConfigService.updateForCaller]. Building one from the LLM's
     * arguments alone would silently blank every field it did not mention -- including
     * `authSettingName`, the credential binding, whose loss would not surface until the next agent
     * run failed to authenticate, far from the call that caused it.
     *
     * So each field falls back to the persisted value, and the ownership check runs on that same
     * already-loaded row.
     *
     * `parameters` replace wholesale (the deep merge of
     * [io.whozoss.agentos.integrationConfig.IntegrationConfigMergeStrategy] applies between scope
     * *layers*, not between successive versions of one row), which is why the tool description
     * tells the agent to read the current value first and send the complete object.
     */
    fun update(
        context: ToolContext,
        id: UUID,
        name: String?,
        description: String?,
        parameters: JsonNode?,
    ): SelfConfigOutcome<IntegrationConfig> {
        val caller = callerOf(context) ?: return notAuthenticated()
        return when (val existing = loadOwn(caller, id)) {
            is SelfConfigOutcome.Failure -> existing
            is SelfConfigOutcome.Ok -> {
                val row = existing.value
                val changes =
                    IntegrationConfigUpdate(
                        name = name ?: row.name,
                        description = description ?: row.description,
                        parameters = parameters ?: row.parameters,
                        authSettingName = row.authSettingName,
                        autoGrant = row.autoGrant,
                    )
                guarded { integrationConfigService.updateForCaller(caller, id, changes) }
            }
        }
    }

    /** Deletes an overlay owned by the calling user. Returns the deleted id on success. */
    fun delete(
        context: ToolContext,
        id: UUID,
    ): SelfConfigOutcome<UUID> {
        val caller = callerOf(context) ?: return notAuthenticated()
        return when (val existing = loadOwn(caller, id)) {
            is SelfConfigOutcome.Failure -> existing
            is SelfConfigOutcome.Ok ->
                guarded {
                    integrationConfigService.deleteForCaller(caller, id)
                    id
                }
        }
    }

    // -------------------------------------------------------------------------
    // Internals
    // -------------------------------------------------------------------------

    /**
     * Loads [id] through the authorized read surface and asserts the calling user owns it.
     *
     * The two failures are kept distinct on purpose. A row the caller cannot see at all answers
     * [SelfConfigErrors.NOT_FOUND]; a row they can see but do not own answers
     * [SelfConfigErrors.SCOPE_NOT_ALLOWED] with an actionable message -- it is a legitimate
     * configuration the agent simply may not rewrite on the user's behalf, and the LLM should say
     * so rather than retry.
     */
    private fun loadOwn(
        caller: Caller,
        id: UUID,
    ): SelfConfigOutcome<IntegrationConfig> =
        when (val loaded = guarded { integrationConfigService.getByIdForCaller(caller, id, Action.READ) }) {
            is SelfConfigOutcome.Failure -> loaded
            is SelfConfigOutcome.Ok ->
                if (loaded.value.userId == caller.userId) {
                    loaded
                } else {
                    SelfConfigOutcome.Failure(
                        SelfConfigErrors.SCOPE_NOT_ALLOWED,
                        "Integration configuration '${loaded.value.name}' is shared (namespace-level or " +
                            "platform-level), not personal, so it can only be changed by an administrator. " +
                            "You may only create and modify the user's own personal configurations. To change " +
                            "this one for this user alone, create a personal configuration with the same name " +
                            "instead: it overrides the shared one for their runs.",
                    )
                }
        }

    /**
     * The principal for this run, or null when there is none.
     *
     * [io.whozoss.agentos.user.User.isAdmin] is carried rather than looked up again downstream,
     * which is the whole point of [Caller] holding it. A user id that resolves to no record is
     * treated as no user at all: fail-closed beats acting under a half-resolved identity.
     */
    private fun callerOf(context: ToolContext): Caller.Agent? {
        val userId = context.userId ?: return null
        val user =
            runCatching { userService.findById(userId) }
                .onFailure { e -> logger.warn(e) { "[SelfConfig] Could not resolve user $userId" } }
                .getOrNull() ?: return null
        return Caller.Agent(
            userId = userId,
            isAdmin = user.isAdmin,
            namespaceId = context.namespaceId,
            caseId = context.caseId,
        )
    }

    private fun <T> notAuthenticated(): SelfConfigOutcome<T> =
        SelfConfigOutcome.Failure(
            SelfConfigErrors.NOT_AUTHENTICATED,
            "This run has no identified user, so there is no personal configuration to read or change. " +
                "Tell the user that configuration must be done from an interactive session.",
        )

    /**
     * Maps the service's exception vocabulary onto [SelfConfigOutcome.Failure].
     *
     * The messages of [ConflictException], [UnprocessableEntityException] and
     * [BadRequestException] are surfaced verbatim: they are written for a human and name the
     * remedy ("a configuration with that name already exists in this scope", "this type cannot be
     * auto-granted", ...), which makes them the LLM's feedback loop. [AccessDeniedException] is
     * the one that is not echoed -- its message can name scopes and policy settings the agent has
     * no reason to learn about.
     */
    private fun <T> guarded(action: () -> T): SelfConfigOutcome<T> =
        try {
            SelfConfigOutcome.Ok(action())
        } catch (e: ResourceNotFoundException) {
            SelfConfigOutcome.Failure(
                SelfConfigErrors.NOT_FOUND,
                e.message ?: "Integration configuration not found.",
            )
        } catch (e: AccessDeniedException) {
            logger.debug(e) { "[SelfConfig] Access denied" }
            SelfConfigOutcome.Failure(
                SelfConfigErrors.ACCESS_DENIED,
                "The user is not allowed to perform this operation on that integration configuration.",
            )
        } catch (e: ConflictException) {
            SelfConfigOutcome.Failure(
                SelfConfigErrors.CONFLICT,
                e.message ?: "That integration configuration conflicts with an existing one.",
            )
        } catch (e: UnprocessableEntityException) {
            SelfConfigOutcome.Failure(
                SelfConfigErrors.INVALID_INPUT,
                e.message ?: "The supplied configuration was rejected.",
            )
        } catch (e: BadRequestException) {
            SelfConfigOutcome.Failure(
                SelfConfigErrors.INVALID_INPUT,
                e.message ?: "The supplied configuration was rejected.",
            )
        } catch (e: Exception) {
            logger.warn(e) { "[SelfConfig] Unexpected failure while handling an integration configuration" }
            SelfConfigOutcome.Failure(
                SelfConfigErrors.UNEXPECTED,
                "The configuration service failed unexpectedly. Try again later.",
            )
        }

    companion object : KLogging()
}
