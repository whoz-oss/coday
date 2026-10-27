package io.whozoss.agentos.configAgent

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.agentos.agentConfig.AgentConfig
import io.whozoss.agentos.agentConfig.AgentConfigService
import io.whozoss.agentos.authSetting.AuthSetting
import io.whozoss.agentos.authSetting.AuthSettingService
import io.whozoss.agentos.integrationConfig.IntegrationConfig
import io.whozoss.agentos.integrationConfig.IntegrationConfigService
import io.whozoss.agentos.integrationConfig.IntegrationTypeRegistry
import io.whozoss.agentos.namespace.Namespace
import io.whozoss.agentos.namespace.NamespaceService
import io.whozoss.agentos.permissions.Action
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.prompt.Prompt
import io.whozoss.agentos.prompt.PromptService
import io.whozoss.agentos.security.declarative.AgentOsPermissionEvaluator
import io.whozoss.agentos.skill.Skill
import io.whozoss.agentos.skill.SkillAuthorizationService
import io.whozoss.agentos.skill.SkillService
import org.springframework.stereotype.Service
import java.util.UUID
import io.whozoss.agentos.agentConfig.toDto as agentToDto
import io.whozoss.agentos.authSetting.toDto as authSettingToDto
import io.whozoss.agentos.integrationConfig.toDto as integrationToDto
import io.whozoss.agentos.namespace.toDto as namespaceToDto
import io.whozoss.agentos.prompt.toDto as promptToDto
import io.whozoss.agentos.skill.toDto as skillToDto

/**
 * Reads the configuration on behalf of an explicit user, outside any HTTP request (agent tools)
 * or inside one (the card's view), with the same rights as the REST read endpoints: a list follows
 * the list endpoints (platform layer for anyone, namespace layer with Namespace READ, the caller's own
 * overlays), and a single entity follows its `GET /{id}` guard, `hasPermission(id, <Entity>, READ)`
 * with the owner fallback, plus the namespace fallback `SkillController` adds for filesystem skills.
 *
 * Reads are bounded to the platform, the current namespace, the namespaces the caller administers
 * (Namespace WRITE, so every namespace for a super-admin) and the caller's own overlays: an entity
 * owned by another user is never returned, even to a super-admin, because what the config agent
 * reads ends up in the case history, readable by every member of the case. The statuses of a plan's
 * operations in a namespace that plan creates are read with that namespace as the bound.
 *
 * Namespaces are platform-level entities, listed and read with the Namespace READ rule of the REST
 * API. What a namespace's state means for its members is [ConfigNamespaceDiagnosis]'s concern.
 */
@Service
class ConfigReadService(
    private val agentConfigService: AgentConfigService,
    private val skillService: SkillService,
    private val promptService: PromptService,
    private val integrationConfigService: IntegrationConfigService,
    private val authSettingService: AuthSettingService,
    private val namespaceService: NamespaceService,
    private val integrationTypeRegistry: IntegrationTypeRegistry,
    private val permissionEvaluator: AgentOsPermissionEvaluator,
    private val skillAuthorizationService: SkillAuthorizationService,
    private val objectMapper: ObjectMapper,
) {
    /** The entity [id] of [kind] if [callerId] may read it by id within [namespaceId]'s bounds, else null. */
    fun find(
        kind: ConfigKind,
        id: UUID,
        callerId: UUID,
        namespaceId: UUID,
    ): ConfigEntity? = load(kind, id)?.takeIf { inBounds(it, callerId, namespaceId) && canReadById(it, callerId) }

    /** Entities of [kind] the caller may read, in the requested [scope]. */
    fun list(
        kind: ConfigKind,
        scope: ReadScope,
        callerId: UUID,
        namespaceId: UUID,
    ): List<ConfigEntity> {
        val namespaceReadable = hasNamespaceRight(callerId, namespaceId, Action.READ)
        val candidates =
            buildList {
                if (scope.includesPlatform) addAll(loadScope(kind, namespaceId = null, userId = null))
                if (scope.includesNamespace && namespaceReadable) addAll(loadScope(kind, namespaceId = namespaceId, userId = null))
                if (scope.includesMine) addAll(loadOwned(kind, callerId, namespaceId))
            }
        return candidates.distinctBy { it.id }.filter { inBounds(it, callerId, namespaceId) && canReadListed(it, callerId, namespaceId) }.sortedBy { it.name.lowercase() }
    }

    /**
     * The entity of [kind] named [name] at exactly ([scopeNamespaceId], [scopeUserId]), if readable by id.
     * Name matching follows the entity's own uniqueness rule: case-insensitive for agents and
     * skills, case-sensitive for prompts, integrations and auth settings.
     */
    fun findByName(
        kind: ConfigKind,
        name: String,
        scopeNamespaceId: UUID?,
        scopeUserId: UUID?,
        callerId: UUID,
        namespaceId: UUID,
    ): ConfigEntity? {
        val caseInsensitive = kind == ConfigKind.AGENT || kind == ConfigKind.SKILL
        return loadScope(kind, scopeNamespaceId, scopeUserId)
            .filter { it.name.equals(name, ignoreCase = caseInsensitive) }
            .firstOrNull { inBounds(it, callerId, namespaceId) && canReadById(it, callerId) }
    }

    fun masked(entity: ConfigEntity): JsonNode = ConfigSecretMasker.mask(entity.kind, entity.dto)

    fun deployedAgentIds(namespaceId: UUID): Set<UUID> = namespaceService.findDeployedAgentIds(namespaceId)

    fun integrationTypes() = integrationTypeRegistry.listTypes()

    fun integrationType(type: String) = integrationTypeRegistry.findByType(type)

    /** Whether [namespaceId] exists and [callerId] administers it (Namespace WRITE), which brings it within the bounds. */
    fun canAdminister(
        callerId: UUID,
        namespaceId: UUID,
    ): Boolean = hasNamespaceRight(callerId, namespaceId, Action.WRITE) && namespaceService.findById(namespaceId) != null

    /** Namespace READ (member) or WRITE (admin), with the same rule as the REST namespace endpoints. */
    fun hasNamespaceRight(
        callerId: UUID,
        namespaceId: UUID,
        action: Action,
    ): Boolean = permissionEvaluator.hasPermission(callerId.toString(), EntityType.NAMESPACE, namespaceId.toString(), action)

    /** Platform, current namespace, administered namespaces and the caller's own overlays only. */
    private fun inBounds(
        entity: ConfigEntity,
        callerId: UUID,
        namespaceId: UUID,
    ): Boolean =
        (entity.namespaceId == null || entity.namespaceId == namespaceId || canAdminister(callerId, entity.namespaceId)) &&
            (entity.userId == null || entity.userId == callerId)

    /** The list endpoints' rule; a namespace is listed to whoever may read it, as `GET /api/namespaces` does. */
    private fun canReadListed(
        entity: ConfigEntity,
        callerId: UUID,
        namespaceId: UUID,
    ): Boolean =
        when {
            entity.kind == ConfigKind.NAMESPACE -> canReadById(entity, callerId)
            else -> entity.userId == callerId || entity.namespaceId == null || hasNamespaceRight(callerId, namespaceId, Action.READ)
        }

    /** The `GET /{id}` rule of the entity's controller. */
    private fun canReadById(
        entity: ConfigEntity,
        callerId: UUID,
    ): Boolean =
        permissionEvaluator.hasPermission(callerId.toString(), entityTypeOf(entity.kind), entity.id.toString(), Action.READ) ||
            (entity.kind == ConfigKind.SKILL && skillAuthorizationService.canRead(callerId.toString(), entity.id))

    private fun entityTypeOf(kind: ConfigKind): EntityType =
        when (kind) {
            ConfigKind.AGENT -> EntityType.AGENT_CONFIG
            ConfigKind.SKILL -> EntityType.SKILL
            ConfigKind.PROMPT -> EntityType.PROMPT
            ConfigKind.INTEGRATION -> EntityType.INTEGRATION_CONFIG
            ConfigKind.AUTH_SETTING -> EntityType.AUTH_SETTING
            ConfigKind.NAMESPACE -> EntityType.NAMESPACE
        }

    private fun load(
        kind: ConfigKind,
        id: UUID,
    ): ConfigEntity? =
        when (kind) {
            ConfigKind.AGENT -> agentConfigService.findById(id)?.let(::fromAgent)
            ConfigKind.SKILL -> skillService.findById(id)?.let(::fromSkill)
            ConfigKind.PROMPT -> promptService.findById(id)?.let(::fromPrompt)
            ConfigKind.INTEGRATION -> integrationConfigService.findById(id)?.let(::fromIntegration)
            ConfigKind.AUTH_SETTING -> authSettingService.findById(id)?.let(::fromAuthSetting)
            ConfigKind.NAMESPACE -> namespaceService.findById(id)?.let(::fromNamespace)
        }

    private fun loadScope(
        kind: ConfigKind,
        namespaceId: UUID?,
        userId: UUID?,
    ): List<ConfigEntity> =
        when (kind) {
            ConfigKind.AGENT -> if (userId == null) agentConfigService.findByNamespace(namespaceId).map(::fromAgent) else emptyList()
            ConfigKind.SKILL ->
                when {
                    userId != null -> emptyList()
                    namespaceId == null -> skillService.findPlatform().map(::fromSkill)
                    else -> skillService.findByParent(namespaceId).map(::fromSkill)
                }
            ConfigKind.PROMPT -> promptService.findByScope(namespaceId, userId, null).map(::fromPrompt)
            ConfigKind.INTEGRATION ->
                when {
                    namespaceId == null && userId == null -> integrationConfigService.findPlatform()
                    userId == null -> integrationConfigService.findByNamespaceShared(namespaceId!!)
                    else -> integrationConfigService.findByUserId(userId).filter { it.namespaceId == namespaceId }
                }.map(::fromIntegration)
            ConfigKind.AUTH_SETTING ->
                when {
                    namespaceId == null && userId == null -> authSettingService.findPlatformLevel()
                    userId == null -> authSettingService.findByNamespaceId(namespaceId!!).filter { it.userId == null }
                    else -> authSettingService.findByUserId(userId).filter { it.namespaceId == namespaceId }
                }.map(::fromAuthSetting)
            ConfigKind.NAMESPACE -> if (namespaceId == null && userId == null) namespaceService.findAll().map(::fromNamespace) else emptyList()
        }

    /** The caller's own overlays of [kind]: user-global and user x current namespace. */
    private fun loadOwned(
        kind: ConfigKind,
        callerId: UUID,
        namespaceId: UUID,
    ): List<ConfigEntity> = loadScope(kind, null, callerId) + loadScope(kind, namespaceId, callerId)

    private fun fromAgent(agent: AgentConfig) =
        ConfigEntity(
            kind = ConfigKind.AGENT,
            id = agent.metadata.id,
            name = agent.name,
            namespaceId = agent.namespaceId,
            userId = null,
            dto = objectMapper.valueToTree(agentToDto(agent)),
            enabled = agent.enabled,
            fromFilesystem = agent.isFilesystemOnly,
        )

    private fun fromSkill(skill: Skill) =
        ConfigEntity(
            kind = ConfigKind.SKILL,
            id = skill.metadata.id,
            name = skill.name,
            namespaceId = skill.namespaceId,
            userId = null,
            dto = objectMapper.valueToTree(skillToDto(skill)),
        )

    private fun fromPrompt(prompt: Prompt) =
        ConfigEntity(
            kind = ConfigKind.PROMPT,
            id = prompt.metadata.id,
            name = prompt.name,
            namespaceId = prompt.namespaceId,
            userId = prompt.userId,
            dto = objectMapper.valueToTree(promptToDto(prompt)),
        )

    private fun fromIntegration(config: IntegrationConfig) =
        ConfigEntity(
            kind = ConfigKind.INTEGRATION,
            id = config.metadata.id,
            name = config.name,
            namespaceId = config.namespaceId,
            userId = config.userId,
            dto = objectMapper.valueToTree(integrationToDto(config)),
            integrationType = config.integrationType,
        )

    private fun fromAuthSetting(setting: AuthSetting) =
        ConfigEntity(
            kind = ConfigKind.AUTH_SETTING,
            id = setting.metadata.id,
            name = setting.name,
            namespaceId = setting.namespaceId,
            userId = setting.userId,
            dto = objectMapper.valueToTree(authSettingToDto(setting)),
        )

    private fun fromNamespace(namespace: Namespace) =
        ConfigEntity(
            kind = ConfigKind.NAMESPACE,
            id = namespace.metadata.id,
            name = namespace.name,
            namespaceId = null,
            userId = null,
            dto = objectMapper.valueToTree(namespace.namespaceToDto()),
        )
}
