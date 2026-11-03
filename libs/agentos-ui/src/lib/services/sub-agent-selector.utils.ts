import { AgentConfig } from '@whoz-oss/agentos-api-client'

/**
 * Reading of `AgentConfig.subAgents`, kept beside [skill-selector.utils] because both answer the
 * same question for a different field: which entries of a selector list a given name satisfies.
 *
 * The reference is `AgentServiceImpl.buildDelegationTools` with `globToRegex`: an empty list grants
 * no delegation tool at all, and every other entry is a glob matched against the **name** of each
 * agent the invoking user can reach — anchored, case-insensitive, `*` standing for any sequence of
 * characters. Nothing is matched against an agent id, which is why an id silently disables
 * delegation and why the server now refuses one on write.
 *
 * Unlike skill selectors, `*` is not short-circuited server-side: every pattern is tried and any
 * match wins. So `['*', 'Reviewer']` resolves exactly like `['*']` — the name is redundant, not
 * ignored.
 */

/** The selector that grants every reachable agent, now and later. */
export const ALL_SUB_AGENTS_SELECTOR = '*'

/** True when the selectors reach every agent through `*`. */
export function grantsAllSubAgents(selectors: AgentConfig['subAgents']): boolean {
  return (selectors ?? []).some((s) => s.trim() === ALL_SUB_AGENTS_SELECTOR)
}

/**
 * The named selectors, lower-cased so they can be compared with agent names.
 *
 * Lower-cased rather than compared verbatim because the server match is case-insensitive: a
 * selector stored as `reviewer` reaches the agent named `Reviewer`, and showing that row unticked
 * would misreport what the agent can actually do.
 *
 * `*` is excluded: it is not a name, and treating it as one would show it as an agent nobody can
 * find. Other globs are kept — they are not names either, but the form has no catalogue entry to
 * put them on and must surface them somewhere to avoid dropping them.
 */
export function namedSubAgentKeys(selectors: AgentConfig['subAgents']): Set<string> {
  const keys = (selectors ?? [])
    .map((s) => s.trim())
    .filter((s) => s.length > 0 && s !== ALL_SUB_AGENTS_SELECTOR)
    .map((s) => s.toLowerCase())
  return new Set(keys)
}

/**
 * True when the selector is a glob rather than a plain name.
 *
 * Used only to label a row: a pattern resolves against names the form cannot enumerate in advance,
 * so "no matching agent" would be the wrong thing to say about it.
 */
export function isSubAgentPattern(selector: string): boolean {
  return selector.includes(ALL_SUB_AGENTS_SELECTOR)
}
