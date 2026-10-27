import { AgentConfig } from '@whoz-oss/agentos-api-client'

/**
 * Reading of `AgentConfig.skillSelectors`, kept in one place because three screens depend
 * on it agreeing with the server: the agent form (what is checked), the skill inspect view
 * (which agents use this skill) and the skill list (what a deletion would break).
 *
 * The reference is `SkillServiceImpl.findSkills`: an empty selector list resolves no skill,
 * a list containing `*` resolves the whole catalogue, and any other entry is a skill name
 * matched after the normalisation below.
 */

/** The selector that grants whatever the catalogue resolves to. */
export const ALL_SKILLS_SELECTOR = '*'

/**
 * Canonical form of a selector, mirroring the normalisation `findSkills` applies before
 * matching names. Keeping the two in step is what makes a YAML-authored `my-skill/SKILL.md`
 * read as the catalogue entry it resolves to.
 */
export function normalizeSkillSelector(selector: string): string {
  return selector
    .trim()
    .replace(/\/SKILL\.md$/, '')
    .replace(/\/$/, '')
    .replace(/^\/+/, '')
}

/** True when the selectors grant the whole catalogue through `*`. */
export function grantsAllSkills(selectors: AgentConfig['skillSelectors']): boolean {
  return (selectors ?? []).some((s) => s.trim() === ALL_SKILLS_SELECTOR)
}

/**
 * The named selectors, canonical and lower-cased so they can be compared with skill names.
 * `*` is excluded: it is not a name, and treating it as one would make it look like a
 * skill nobody can find.
 */
export function namedSelectorKeys(selectors: AgentConfig['skillSelectors']): Set<string> {
  const keys = (selectors ?? [])
    .map((s) => s.trim())
    .filter((s) => s.length > 0 && s !== ALL_SKILLS_SELECTOR)
    .map((s) => normalizeSkillSelector(s).toLowerCase())
  return new Set(keys)
}

/** How an agent's selectors reach a skill, if they do. */
export type SkillGrant = 'named' | 'all' | 'none'

/**
 * Whether these selectors grant [skillName], and how.
 *
 * The distinction matters to a reader: a `named` grant disappears with the skill, while an
 * `all` grant simply resolves to one skill less, so only the first is a reference to fix.
 */
export function skillGrantOf(selectors: AgentConfig['skillSelectors'], skillName: string): SkillGrant {
  if (namedSelectorKeys(selectors).has(normalizeSkillSelector(skillName).toLowerCase())) return 'named'
  return grantsAllSkills(selectors) ? 'all' : 'none'
}
