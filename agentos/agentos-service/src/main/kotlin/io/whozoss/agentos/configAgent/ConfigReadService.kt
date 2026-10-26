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
import io.whozoss.agentos.integrationConfig.IntegrationsProperties
import io.whozoss.agentos.namespace.NamespaceService
import io.whozoss.agentos.permissions.Action
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.prompt.Prompt
import io.whozoss.agentos.prompt.PromptService
import io.whozoss.agentos.sdk.api.integrationConfig.IntegrationConfigDto
import io.whozoss.agentos.security.declarative.AgentOsPermissionEvaluator
import io.whozoss.agentos.skill.Skill
import io.whozoss.agentos.skill.SkillService
import io.whozoss.agentos.user.UserService
import org.springframework.stereotype.Service
import java.util.UUID
import io.whozoss.agentos.agentConfig.toDto as agentToDto
import io.whozoss.agentos.authSetting.toDto as authSettingToDto
import io.whozoss.agentos.prompt.toDto as promptToDto
import io.whozoss.agentos.skill.toDto as skillToDto

/**
 * A configuration entity as the config agent sees it: identity, scope, and its REST DTO as JSON.
 * [dto] is unmasked; callers mask it with [ConfigSecretMasker] before it leaves the server.
 */
data class ConfigEntity(
    val kind: ConfigKind,
    val id: UUID,
    val name: String,
    val namespaceId: UUID?,
    val userId: UUID?,
    val dto: JsonNode,
    val enabled: Boolean? = null,
    val integrationType: String? = null,
    val fromFilesystem: Boolean = false,
)

/**
 * Reads the configuration on behalf of an explicit user, outside any HTTP request (agent tools)
 * or inside one (the card's view), with the same rights as the REST read endpoints.
 *
 * Reads are bounded to the platform, the current namespace and the caller's own overlays: an
 * entity owned by another user is never returned, even to a super-admin, because what the config
 * agent reads ends up in the case history, readable by every member of the case.
 */
@Service
class ConfigReadService(
    private val agentConfigService: AgentConfigService,
    private val skillService: SkillService,
    private val promptService: PromptService,
    private val integrationConfigService: IntegrationConfigService,
    private val authSettingService: AuthSettingService,
    private val namespaceService: NamespaceService,
    private val userService: UserService,
    private val integrationTypeRegistry: IntegrationTypeRegistry,
    private val integrationsProperties: IntegrationsProperties,
    private val permissionEvaluator: AgentOsPermissionEvaluator,
    private val objectMapper: ObjectMapper,
) {
    /** The entity [id] of [kind] if [callerId] may read it within [namespaceId]'s bounds, else null. */
    fun find(
        kind: ConfigKind,
        id: UUID,
        callerId: UUID,
        namespaceId: UUID,
    ): ConfigEntity? = load(kind, id)?.takeIf { canRead(it, callerId, namespaceId) }

    /** Entities of [kind] the caller may read, in the requested [scope]. */
    fun list(
        kind: ConfigKind,
        scope: ReadScope,
        callerId: UUID,
        namespaceId: UUID,
    ): List<ConfigEntity> {
        val namespaceReadable = canReadNamespace(callerId, namespaceId)
        val candidates =
            buildList {
                if (scope.includesPlatform) addAll(loadScope(kind, namespaceId = null, userId = null))
                if (scope.includesNamespace && namespaceReadable) addAll(loadScope(kind, namespaceId = namespaceId, userId = null))
                if (scope.includesMine) addAll(loadOwned(kind, callerId, namespaceId))
            }
        return candidates.distinctBy { it.id }.filter { canRead(it, callerId, namespaceId) }.sortedBy { it.name.lowercase() }
    }

    /**
     * The entity of [kind] named [name] at exactly ([scopeNamespaceId], [scopeUserId]), if readable.
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
            .firstOrNull { canRead(it, callerId, namespaceId) }
    }

    fun masked(entity: ConfigEntity): JsonNode = ConfigSecretMasker.mask(entity.kind, entity.dto)

    fun deployedAgentIds(namespaceId: UUID): Set<UUID> = namespaceService.findDeployedAgentIds(namespaceId)

    fun integrationTypes() = integrationTypeRegistry.listTypes()

    fun integrationType(type: String) = integrationTypeRegistry.findByType(type)

    fun capabilities(
        callerId: UUID,
        namespaceId: UUID,
    ): Map<String, Any?> {
        val superAdmin = userService.findById(callerId)?.isAdmin == true
        val namespaceRole =
            when {
                permissionEvaluator.hasPermission(callerId.toString(), EntityType.NAMESPACE, namespaceId.toString(), Action.WRITE) -> "ADMIN"
                permissionEvaluator.hasPermission(callerId.toString(), EntityType.NAMESPACE, namespaceId.toString(), Action.READ) -> "MEMBER"
                else -> "NONE"
            }
        return mapOf(
            "userId" to callerId,
            "namespaceId" to namespaceId,
            "superAdmin" to superAdmin,
            "namespaceRole" to namespaceRole,
            "userScopeDeniedIntegrationTypes" to integrationsProperties.userScopeDeniedTypes,
        )
    }

    private fun canReadNamespace(
        callerId: UUID,
        namespaceId: UUID,
    ): Boolean = permissionEvaluator.hasPermission(callerId.toString(), EntityType.NAMESPACE, namespaceId.toString(), Action.READ)

    private fun canRead(
        entity: ConfigEntity,
        callerId: UUID,
        namespaceId: UUID,
    ): Boolean {
        val inBounds =
            (entity.namespaceId == null || entity.namespaceId == namespaceId) &&
                (entity.userId == null || entity.userId == callerId)
        return when {
            !inBounds -> false
            entity.userId == callerId -> true
            entity.namespaceId == null -> true
            else -> canReadNamespace(callerId, namespaceId)
        }
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
            dto =
                objectMapper.valueToTree(
                    IntegrationConfigDto(
                        id = config.metadata.id,
                        namespaceId = config.namespaceId,
                        userId = config.userId,
                        name = config.name,
                        integrationType = config.integrationType,
                        description = config.description,
                        parameters = config.parameters,
                        authSettingName = config.authSettingName,
                        autoGrant = config.autoGrant,
                    ),
                ),
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
}

/** Which layers a `read` list covers. */
enum class ReadScope(
    val includesPlatform: Boolean,
    val includesNamespace: Boolean,
    val includesMine: Boolean,
) {
    ALL(true, true, true),
    PLATFORM(true, false, false),
    NAMESPACE(false, true, false),
    MINE(false, false, true),
}
