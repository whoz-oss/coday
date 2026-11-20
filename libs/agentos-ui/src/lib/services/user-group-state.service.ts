import { inject, Injectable } from '@angular/core'
import {
  AgentConfig,
  AgentConfigControllerService,
  UserGroupControllerService,
  UserGroupMembersListResponse,
  UserGroupSearchResult,
} from '@whoz-oss/agentos-api-client'
import { forkJoin, Observable } from 'rxjs'

/** Ergonomic input for creating a user group (arrays instead of the generated Set types). */
export interface CreateUserGroupInput {
  namespaceId: string
  name: string
  agentIds: string[]
  memberExternalIdsToAdd: string[]
  /** External ids of the members who should have the ADMIN role. */
  adminExternalIds: string[]
}

/** Ergonomic input for updating a user group (arrays instead of the generated Set types). */
export interface UpdateUserGroupInput {
  name: string
  agentIds: string[]
  memberExternalIdsToAdd: string[]
  memberExternalIdsToRemove: string[]
  /** Members to promote to ADMIN; the server touches no other admin (delta mode). */
  adminExternalIdsToAdd: string[]
  /** Members to demote to MEMBER; the server touches no other admin (delta mode). */
  adminExternalIdsToRemove: string[]
}

/** Everything the create/edit form needs before rendering: agent choices. */
export interface UserGroupFormData {
  namespaceAgents: AgentConfig[]
  platformAgents: AgentConfig[]
}

/** An existing group plus the first page of its members, for the edit form. */
export interface ExistingUserGroup {
  group: UserGroupSearchResult
  members: UserGroupMembersListResponse
}

/**
 * UserGroupStateService — API-layer facade for UserGroup entities.
 *
 * Follows the two-layer pattern (see PromptStateService): components go through this facade
 * rather than injecting UserGroupControllerService directly.
 *
 * It also bridges one generated-client quirk: the backend request DTOs declare their
 * collections as `Set<...>` (uniqueItems in the spec), which openapi-generator maps to TS
 * `Set<string>`. Angular's JSON serializer turns a real `Set` into `{}`, so callers pass plain
 * arrays and this facade forwards them as arrays cast to the generated type (see [toWireSet]).
 */
@Injectable({ providedIn: 'root' })
export class UserGroupStateService {
  private readonly controller = inject(UserGroupControllerService)
  private readonly agentConfigController = inject(AgentConfigControllerService)

  listByNamespace(namespaceId: string): Observable<UserGroupSearchResult[]> {
    return this.controller.findByNamespaceIdUserGroup(namespaceId)
  }

  /** Loads the create/edit form's reference data in one round-trip. */
  loadFormData(namespaceId: string): Observable<UserGroupFormData> {
    return forkJoin({
      namespaceAgents: this.agentConfigController.listByParentAgentConfig(namespaceId, false),
      platformAgents: this.agentConfigController.listPlatformAgentsAgentConfig(false),
    })
  }

  /** Loads an existing group and the first page of its members for the edit form. */
  loadExistingGroup(userGroupId: string): Observable<ExistingUserGroup> {
    return forkJoin({
      group: this.getById(userGroupId),
      members: this.loadMembersPage(userGroupId),
    })
  }

  getById(userGroupId: string): Observable<UserGroupSearchResult> {
    return this.controller.getByIdUserGroup(userGroupId)
  }

  /** One page of members; `next` is the opaque cursor of the previous page (omit for the first). */
  loadMembersPage(userGroupId: string, next?: string): Observable<UserGroupMembersListResponse> {
    return this.controller.listMembersUserGroup(userGroupId, next ? { next } : {})
  }

  create(input: CreateUserGroupInput): Observable<UserGroupSearchResult> {
    return this.controller.createUserGroup({
      namespaceId: input.namespaceId,
      name: input.name,
      agentIds: toWireSet(input.agentIds),
      userExternalIdsToAdd: toWireSet(input.memberExternalIdsToAdd),
      adminExternalIds: toWireSet(input.adminExternalIds),
    })
  }

  update(userGroupId: string, input: UpdateUserGroupInput): Observable<UserGroupSearchResult> {
    return this.controller.updateUserGroup(userGroupId, {
      name: input.name,
      agentIds: toWireSet(input.agentIds),
      userExternalIdsToAdd: toWireSet(input.memberExternalIdsToAdd),
      userExternalIdsToRemove: toWireSet(input.memberExternalIdsToRemove),
      adminExternalIds: toWireSet([]),
      adminExternalIdsToAdd: toWireSet(input.adminExternalIdsToAdd),
      adminExternalIdsToRemove: toWireSet(input.adminExternalIdsToRemove),
    })
  }

  delete(userGroupId: string): Observable<unknown> {
    return this.controller.deleteUserGroup(userGroupId)
  }
}

/**
 * The generated request DTOs type these fields as `Set<string>`, but a JS `Set` serializes to
 * `{}` via JSON. Send a plain array — valid JSON the backend deserializes into a Set — cast to
 * satisfy the generated type.
 */
function toWireSet(values: string[]): Set<string> {
  return values as unknown as Set<string>
}
