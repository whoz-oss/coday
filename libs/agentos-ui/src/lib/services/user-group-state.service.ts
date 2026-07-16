import { inject, Injectable } from '@angular/core'
import { UserGroupControllerService, UserGroupMember, UserGroupSearchResult } from '@whoz-oss/agentos-api-client'
import { Observable } from 'rxjs'

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
  /** External ids of the members who should have the ADMIN role. */
  adminExternalIds: string[]
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

  listByNamespace(namespaceId: string): Observable<UserGroupSearchResult[]> {
    return this.controller.findByNamespaceIdUserGroup(namespaceId)
  }

  getById(userGroupId: string): Observable<UserGroupSearchResult> {
    return this.controller.getByIdUserGroup(userGroupId)
  }

  getMembers(userGroupId: string): Observable<UserGroupMember[]> {
    return this.controller.getMembersUserGroup(userGroupId)
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
      adminExternalIds: toWireSet(input.adminExternalIds),
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
