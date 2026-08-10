/**
 * Pure helpers for NamespaceMembersComponent — role-aware diffing against
 * `UserMembershipRole[]` delta semantics (see NamespacePermissionEndpoints.kt):
 *   - entries with a role (toUpsert here) = users to add OR whose role changed
 *   - entries without a role (toRemove here) = users to fully revoke
 *   - a user absent from the list is left untouched server-side
 *
 * Unlike UserGroup (roles are additive: an admin is also a roster member),
 * namespace roles are EXCLUSIVE — a user is ADMIN, MEMBER, or absent. So the diff must
 * also catch role changes on members who stay in the list, not just adds/removes.
 */

/** Minimal shape shared by the loaded state and the edited state. */
export interface MemberRoleEntry {
  userId: string
  role: string
}

export interface MemberDiffResult {
  /** Users to add, or whose role changed since the original snapshot. */
  toUpsert: MemberRoleEntry[]
  /** Users present in the original snapshot but no longer in the edited list. */
  toRemove: string[]
}

/**
 * Diffs the original (loaded) member/role set against the edited one.
 *
 * A user is included in `toUpsert` when they are new OR their role differs from the
 * original snapshot. A user is included in `toRemove` when they were present originally
 * but are absent from the edited list.
 */
export function computeMemberDiff(original: MemberRoleEntry[], edited: MemberRoleEntry[]): MemberDiffResult {
  const originalRoleByUserId = new Map(original.map((m) => [m.userId, m.role]))
  const editedUserIds = new Set(edited.map((m) => m.userId))

  const toUpsert = edited.filter((m) => originalRoleByUserId.get(m.userId) !== m.role)
  const toRemove = original.filter((m) => !editedUserIds.has(m.userId)).map((m) => m.userId)

  return { toUpsert, toRemove }
}

/** Anti-lockout guard: true when at least one ADMIN remains in the edited set. */
export function hasAtLeastOneAdmin(members: MemberRoleEntry[]): boolean {
  return members.some((m) => m.role === 'ADMIN')
}

/** Best display label for a user: "First Last" when available, else email, else external id. */
export function memberLabel(user: {
  firstname?: string
  lastname?: string
  email?: string
  externalId?: string
}): string {
  const name = [user.firstname, user.lastname].filter(Boolean).join(' ').trim()
  return name || user.email || user.externalId || ''
}
