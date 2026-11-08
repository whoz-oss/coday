import { AgentConfig, Case } from '@whoz-oss/agentos-api-client'

/**
 * Pure grouping of the case list "by agent" (optional display of the drawer).
 *
 * Kept out of the template and of the component so it can be tested without TestBed: the drawer only maps the
 * result to rows.
 */

/** Key of the catch-all group (cases whose origin agent is missing or no longer known). Never persisted as an agent. */
export const OTHERS_GROUP_KEY = 'others'

/** User-facing label of the catch-all group. The lib's messages are in English (see GUIDELINES.md). */
export const OTHERS_GROUP_LABEL = 'Others'

/** One group of root cases created with the same agent. */
export interface CaseAgentGroup {
  /** Stable key, usable for persistence: `agent:<lower-cased name>` or {@link OTHERS_GROUP_KEY}. */
  key: string
  label: string
  isOthers: boolean
  /** Most recent {@link caseRecencyKey} of the group's cases; '' when none has a date. */
  lastActivity: string
  /** Root cases of the group, favorites first, then most recent first. Never empty. */
  cases: Case[]
}

export interface GroupCasesByAgentOptions {
  /**
   * Names of the agents currently available in the namespace, or null when they are not known (not loaded yet, or
   * the request failed). With null the origin agent of a case is trusted as is: nothing is dumped into "Others"
   * just because the list is missing. With a list, a name matching none of them (agent deleted or renamed) is
   * treated as unknown.
   */
  knownAgentNames: readonly string[] | null
}

/**
 * Recency key of a case: the one the drawer already sorts its tree with (`modified`, falling back to `created`).
 * ISO timestamps, so comparing the strings compares the dates.
 */
export function caseRecencyKey(c: Case): string {
  return c.modified ?? c.created ?? ''
}

/** Most recent first; equal keys compare equal so that a stable sort keeps the input order. */
export function compareCasesByRecencyDesc(a: Case, b: Case): number {
  const recencyA = caseRecencyKey(a)
  const recencyB = caseRecencyKey(b)
  if (recencyA === recencyB) {
    return 0
  }
  return recencyB > recencyA ? 1 : -1
}

/** Agent names are compared case-insensitively (the server matches them that way, so does the @-mention filter). */
export function normalizeAgentName(name: string | null | undefined): string {
  return (name ?? '').trim().toLowerCase()
}

/**
 * Names of the agents the namespace offers, for {@link GroupCasesByAgentOptions.knownAgentNames}.
 * An empty answer is read as "not known" (null): a namespace without any agent is far more likely a list that could
 * not be resolved than a namespace whose agents were all deleted, and trusting it would dump every case into "Others".
 */
export function knownAgentNamesFrom(agents: readonly Pick<AgentConfig, 'name'>[]): string[] | null {
  const names = agents.map((agent) => agent.name).filter((name) => normalizeAgentName(name).length > 0)
  return names.length > 0 ? names : null
}

/** Name shown for a case in the drawer; mirrors the mapping of the list rows. */
function caseDisplayName(c: Case): string {
  return c.title ?? c.id ?? '—'
}

/** Cases whose title or id contains the query (case-insensitive), like the drawer's own search. */
export function filterCasesByQuery(cases: readonly Case[], query: string): Case[] {
  const needle = query.trim().toLowerCase()
  if (!needle) {
    return [...cases]
  }
  return cases.filter(
    (c) => caseDisplayName(c).toLowerCase().includes(needle) || (c.id ?? '').toLowerCase().includes(needle)
  )
}

function favoriteRank(c: Case): number {
  return c.favorite ? 0 : 1
}

/** Order of the list today: favorites first, each part most recent first. */
function compareLikeTheList(a: Case, b: Case): number {
  return favoriteRank(a) - favoriteRank(b) || compareCasesByRecencyDesc(a, b)
}

/**
 * Groups the ROOT cases (those without `parentCaseId`) under the agent each was created with (`originAgentName`).
 *
 * - Only the origin counts: a later @other-agent never moves a case.
 * - A case without origin, or whose origin matches no known agent, goes to the single "Others" group.
 * - Groups are sorted by their most recent activity; "Others" is always last.
 * - Cases inside a group are sorted like the list sorts them (favorites first, then most recent first).
 * - A group with no case does not exist.
 */
export function groupCasesByAgent(cases: readonly Case[], options: GroupCasesByAgentOptions): CaseAgentGroup[] {
  const known = new Map<string, string>()
  for (const name of options.knownAgentNames ?? []) {
    const key = normalizeAgentName(name)
    if (key && !known.has(key)) {
      known.set(key, name.trim())
    }
  }
  const agentsKnown = options.knownAgentNames !== null

  const byKey = new Map<string, { label: string; isOthers: boolean; cases: Case[] }>()
  const add = (key: string, label: string, isOthers: boolean, c: Case): void => {
    const group = byKey.get(key)
    if (group) {
      group.cases.push(c)
    } else {
      byKey.set(key, { label, isOthers, cases: [c] })
    }
  }

  for (const c of cases) {
    if (!c.parentCaseId) {
      const normalized = normalizeAgentName(c.originAgentName)
      if (!normalized || (agentsKnown && !known.has(normalized))) {
        add(OTHERS_GROUP_KEY, OTHERS_GROUP_LABEL, true, c)
      } else {
        add(`agent:${normalized}`, known.get(normalized) ?? (c.originAgentName ?? '').trim(), false, c)
      }
    }
  }

  const groups: CaseAgentGroup[] = [...byKey.entries()].map(([key, group]) => {
    const sorted = [...group.cases].sort(compareLikeTheList)
    return {
      key,
      label: group.label,
      isOthers: group.isOthers,
      lastActivity: sorted.reduce((latest, c) => (caseRecencyKey(c) > latest ? caseRecencyKey(c) : latest), ''),
      cases: sorted,
    }
  })

  return groups.sort((a, b) => {
    if (a.isOthers !== b.isOthers) {
      return a.isOthers ? 1 : -1
    }
    if (a.lastActivity !== b.lastActivity) {
      return b.lastActivity > a.lastActivity ? 1 : -1
    }
    return a.label.localeCompare(b.label)
  })
}
