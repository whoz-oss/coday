import { inject, Injectable } from '@angular/core'
import {
  NamespaceMemberEntry,
  NamespacePermissionEndpointsService,
  NamespaceUserListItem,
  UserControllerService,
} from '@whoz-oss/agentos-api-client'
import { Observable } from 'rxjs'

/** Ergonomic input for updateMembers (arrays instead of the generated Set types). */
export interface UpdateNamespaceMembersInput {
  /** Users to add, or whose role changed. */
  members: NamespaceMemberEntry[]
  /** Users to fully revoke (both ADMIN and MEMBER). */
  userIdsToRemove: string[]
}

/**
 * NamespaceMemberStateService — API-layer facade for namespace ADMIN/MEMBER management.
 *
 * Follows the two-layer pattern (see UserGroupStateService): components go through this
 * facade rather than injecting NamespacePermissionEndpointsService or UserControllerService
 * directly.
 *
 * Bridges the same generated-client quirk as UserGroupStateService: the backend request DTO
 * declares `userIdsToRemove` as a `Set<UUID>` (uniqueItems in the spec), which openapi-generator
 * maps to TS `Set<string>`. Angular's JSON serializer turns a real `Set` into `{}`, so callers
 * pass a plain array and this facade forwards it cast to the generated type (see [toWireSet]).
 *
 * `listAllUsers()` wraps `UserControllerService.listAllUser()`, which is SUPER_ADMIN-only on
 * the backend — callers (the members component) must only invoke it when the current user is
 * a super-admin, otherwise the request 403s. This service does not enforce that itself; it is
 * a UI-layer concern (see NamespaceMembersComponent).
 */
@Injectable({ providedIn: 'root' })
export class NamespaceMemberStateService {
  private readonly permissions = inject(NamespacePermissionEndpointsService)
  private readonly userController = inject(UserControllerService)

  /** Current members (and their role) of a namespace. */
  listMembers(namespaceId: string): Observable<NamespaceUserListItem[]> {
    return this.permissions.listNamespaceUsers(namespaceId)
  }

  /**
   * All platform users — SUPER_ADMIN only on the backend. Only call this when the current
   * user is confirmed to be a super-admin (see NamespaceMembersComponent.isSuperAdmin).
   */
  listAllUsers() {
    return this.userController.listAllUser()
  }

  /** Batch add / role-change / remove in a single round-trip. Returns the refreshed member list. */
  updateMembers(namespaceId: string, input: UpdateNamespaceMembersInput): Observable<NamespaceUserListItem[]> {
    return this.permissions.updateMembers(namespaceId, {
      members: input.members,
      userIdsToRemove: toWireSet(input.userIdsToRemove),
    })
  }
}

/**
 * The generated request DTO types `userIdsToRemove` as `Set<string>`, but a JS `Set`
 * serializes to `{}` via JSON. Send a plain array — valid JSON the backend deserializes
 * into a Set — cast to satisfy the generated type.
 */
function toWireSet(values: string[]): Set<string> {
  return values as unknown as Set<string>
}
