/**
 * Configuration for skill limits, read from `skillsConfig` in coday.yaml.
 *
 * Defaults:
 * - maxSkillsInPrompt: 50
 * - maxSkillFileBytes: 102400 (100 KB)
 */
export interface SkillsConfig {
  /** Maximum number of skills injected into the prompt (L1). Excess skills are excluded with a warning. */
  maxSkillsInPrompt?: number
  /** Maximum file size (in bytes) for a SKILL.md (or entrypoint). Files exceeding this are excluded at parsing time. */
  maxSkillFileBytes?: number
}
