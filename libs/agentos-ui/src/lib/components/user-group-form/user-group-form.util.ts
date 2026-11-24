import { UserGroupMember, UserGroupMemberRoleEnum } from '@whoz-oss/agentos-api-client'

/** A user currently selected as a member, with a display label and their role in the group. */
export interface SelectedMember {
  externalId: string
  label: string
  email?: string
  role: UserGroupMemberRoleEnum
  /** True once the user changed the role locally; a later server page then never overrides it. */
  roleEdited?: boolean
}

/** Server-side role per external id, filled by every loaded page (the reference all diffs are computed from). */
export type MemberBaseline = ReadonlyMap<string, UserGroupMemberRoleEnum>

/**
 * Diffs the server-loaded member ids against the selected ones, producing the add/remove lists an
 * update request expects.
 */
export function computeMemberDiff(original: string[], selected: string[]): { toAdd: string[]; toRemove: string[] } {
  const originalSet = new Set(original)
  const selectedSet = new Set(selected)
  return {
    toAdd: selected.filter((id) => !originalSet.has(id)),
    toRemove: original.filter((id) => !selectedSet.has(id)),
  }
}

/**
 * Merges a server page into the displayed rows and the baseline. Rows already displayed (a locally
 * added member that arrives later) are not duplicated and take the server role unless edited
 * locally. A member loaded by an earlier page and removed locally does not come back (keyset pages
 * never repeat rows); one added locally from a not-yet-loaded page, then removed, reappears when
 * that page loads. Server membership is unaffected either way.
 */
export function mergeMemberPage(
  rows: SelectedMember[],
  baseline: MemberBaseline,
  page: UserGroupMember[]
): { rows: SelectedMember[]; baseline: MemberBaseline } {
  const serverRoles = new Map(page.map((member) => [member.externalId, member.role]))
  const displayed = new Set(rows.map((row) => row.externalId))
  const updated = rows.map((row) => {
    const serverRole = serverRoles.get(row.externalId)
    return serverRole && !row.roleEdited ? { ...row, role: serverRole } : row
  })
  const appended = page
    .filter((member) => !displayed.has(member.externalId))
    .map((member) => ({
      externalId: member.externalId,
      label: memberLabel(member),
      email: member.email,
      role: member.role,
    }))
  return { rows: [...updated, ...appended], baseline: new Map([...baseline, ...serverRoles]) }
}

/**
 * Admin changes versus the server roles, for the selected rows only: promote rows now ADMIN that
 * the server does not hold as ADMIN, demote rows now MEMBER that it does (this also covers a
 * removed admin re-added as MEMBER). A row absent from the baseline (typed in, member of a page not
 * loaded yet) is demoted when MEMBER: the server may hold it as ADMIN, and demoting a non-admin is
 * a no-op there, so the chosen role wins. Loaded members that did not change are never touched.
 */
export function computeAdminDelta(
  baseline: MemberBaseline,
  selected: SelectedMember[]
): { toAdd: string[]; toRemove: string[] } {
  const isServerAdmin = (row: SelectedMember) => baseline.get(row.externalId) === UserGroupMemberRoleEnum.ADMIN
  const mayBeServerAdmin = (row: SelectedMember) => isServerAdmin(row) || !baseline.has(row.externalId)
  const ids = (rows: SelectedMember[]) => rows.map((row) => row.externalId)
  return {
    toAdd: ids(selected.filter((row) => row.role === UserGroupMemberRoleEnum.ADMIN && !isServerAdmin(row))),
    toRemove: ids(selected.filter((row) => row.role === UserGroupMemberRoleEnum.MEMBER && mayBeServerAdmin(row))),
  }
}

/** Best display label for a user: "First Last" when available, else the external id. */
export function memberLabel(user: { firstname?: string; lastname?: string; externalId: string }): string {
  const name = [user.firstname, user.lastname].filter(Boolean).join(' ').trim()
  return name || user.externalId
}
