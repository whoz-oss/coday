import * as fs from 'node:fs/promises'
import * as fsSync from 'node:fs'
import * as path from 'node:path'
import * as yaml from 'yaml'
import { execFileSync } from 'node:child_process'
import { Interactor, SkillMetadata, SkillsConfig } from '@coday/model'

const DEFAULT_MAX_SKILLS_IN_PROMPT = 50
const DEFAULT_MAX_SKILL_FILE_BYTES = 102400

/**
 * Check whether all prerequisites declared in `requires` are satisfied.
 * Returns true if the skill should be included, false otherwise.
 */
function checkSkillRequirements(
  skillName: string,
  requires: { bins?: string[]; env?: string[] } | undefined,
  interactor: Interactor
): boolean {
  if (!requires) return true

  if (requires.bins) {
    for (const bin of requires.bins) {
      try {
        execFileSync('which', [bin], { stdio: 'pipe' })
      } catch {
        interactor.warn(`Skill '${skillName}' excluded: binary '${bin}' not found in PATH`)
        return false
      }
    }
  }

  if (requires.env) {
    for (const key of requires.env) {
      if (!process.env[key]) {
        interactor.warn(`Skill '${skillName}' excluded: env var '${key}' not set`)
        return false
      }
    }
  }

  return true
}

/**
 * Parse SKILL.md files and extract frontmatter metadata (L1).
 *
 * Each SKILL.md must start with a YAML frontmatter delimited by `---`.
 * Required frontmatter fields: `name`, `description`.
 * Optional: `entrypoint` (path to the file to load as L2 instead of the body).
 * Optional: `requires` (gating: `{ bins?: string[], env?: string[] }`).
 *
 * Files that are missing or have invalid frontmatter emit a warning via interactor and are skipped.
 * Skills whose prerequisites (`requires`) are not satisfied are excluded with a warning.
 * Skills whose file size exceeds `maxSkillFileBytes` are excluded with a warning.
 * If more skills pass than `maxSkillsInPrompt`, the excess are excluded with a warning.
 */
export async function parseSkillFiles(
  paths: string[],
  basePath: string,
  interactor: Interactor,
  skillsConfig?: SkillsConfig
): Promise<SkillMetadata[]> {
  const maxSkillFileBytes = skillsConfig?.maxSkillFileBytes ?? DEFAULT_MAX_SKILL_FILE_BYTES
  const maxSkillsInPrompt = skillsConfig?.maxSkillsInPrompt ?? DEFAULT_MAX_SKILLS_IN_PROMPT

  const results: SkillMetadata[] = []
  const seenNames = new Set<string>()

  for (const skillPath of paths) {
    // Resolve skill path: support full paths (./skills/foo/SKILL.md) and short names (foo)
    let resolvedPath = path.resolve(basePath, skillPath)

    // If the path doesn't exist, try common patterns:
    // 1. skills/{name}/SKILL.md  (short name like "popcorn")
    // 2. ./skills/{name}/SKILL.md
    if (!fsSync.existsSync(resolvedPath)) {
      const shortNamePath = path.resolve(basePath, 'skills', skillPath, 'SKILL.md')

      // Security: ensure shortNamePath stays within project directory
      const normalizedBaseShort = path.resolve(basePath) + path.sep
      if (!shortNamePath.startsWith(normalizedBaseShort) && shortNamePath !== path.resolve(basePath)) {
        interactor.warn(`Skill '${skillPath}' excluded: path traversal detected (resolved to ${shortNamePath})`)
        continue
      }

      if (fsSync.existsSync(shortNamePath)) {
        resolvedPath = shortNamePath
        interactor.debug(`Skill '${skillPath}' resolved to ${resolvedPath}`)
      }
    }

    // Security: ensure resolved path stays within project directory
    const normalizedBase = path.resolve(basePath) + path.sep
    if (!resolvedPath.startsWith(normalizedBase) && resolvedPath !== path.resolve(basePath)) {
      interactor.warn(`Skill '${skillPath}' excluded: path traversal detected (resolved to ${resolvedPath})`)
      continue
    }

    // Check file size BEFORE reading content
    let fileSize: number
    try {
      fileSize = fsSync.statSync(resolvedPath).size
    } catch {
      interactor.warn(`Skill file not found: ${resolvedPath} (tried also skills/${skillPath}/SKILL.md)`)
      continue
    }

    if (fileSize > maxSkillFileBytes) {
      interactor.warn(
        `Skill '${resolvedPath}' excluded: file size (${fileSize}) exceeds maxSkillFileBytes (${maxSkillFileBytes})`
      )
      continue
    }

    let content: string
    try {
      content = await fs.readFile(resolvedPath, 'utf-8')
    } catch {
      interactor.warn(`Skill file not found: ${resolvedPath}`)
      continue
    }

    // Normalize CRLF to LF before any frontmatter parsing
    content = content.replaceAll('\r\n', '\n')

    // Frontmatter must start at line 1 with `---`
    if (!content.startsWith('---\n')) {
      interactor.warn(`Skill file has no frontmatter (missing opening ---): ${resolvedPath}`)
      continue
    }

    // Find the closing `---` delimiter (second occurrence)
    const secondDelimiterIndex = content.indexOf('\n---', 3)
    if (secondDelimiterIndex === -1) {
      interactor.warn(`Skill file has no closing frontmatter delimiter (---): ${resolvedPath}`)
      continue
    }

    const frontmatterRaw = content.slice(4, secondDelimiterIndex) // skip initial `---\n`

    let frontmatter: unknown
    try {
      frontmatter = yaml.parse(frontmatterRaw)
    } catch {
      interactor.warn(`Skill file has invalid YAML frontmatter: ${resolvedPath}`)
      continue
    }

    if (
      !frontmatter ||
      typeof frontmatter !== 'object' ||
      !('name' in frontmatter) ||
      !('description' in frontmatter)
    ) {
      interactor.warn(`Skill file frontmatter missing required fields (name, description): ${resolvedPath}`)
      continue
    }

    const fm = frontmatter as Record<string, unknown>
    const name = fm['name']
    const description = fm['description']
    const entrypoint = fm['entrypoint']
    const requires = fm['requires'] as { bins?: string[]; env?: string[] } | undefined

    if (typeof name !== 'string' || typeof description !== 'string') {
      interactor.warn(`Skill file frontmatter name/description must be strings: ${resolvedPath}`)
      continue
    }

    if (seenNames.has(name)) {
      interactor.warn(`Skill '${name}' excluded: duplicate name (already loaded from another path)`)
      continue
    }
    seenNames.add(name)

    // Check entrypoint file size if defined
    if (typeof entrypoint === 'string' && entrypoint.length > 0) {
      const entrypointPath = path.resolve(path.dirname(resolvedPath), entrypoint)
      const normalizedBaseEntrypoint = path.resolve(basePath) + path.sep
      if (!entrypointPath.startsWith(normalizedBaseEntrypoint) && entrypointPath !== path.resolve(basePath)) {
        interactor.warn(`Skill '${name}' excluded: entrypoint path traversal detected (resolved to ${entrypointPath})`)
        continue
      }
      try {
        const entrypointSize = fsSync.statSync(entrypointPath).size
        if (entrypointSize > maxSkillFileBytes) {
          interactor.warn(
            `Skill '${name}' excluded: entrypoint file size (${entrypointSize}) exceeds maxSkillFileBytes (${maxSkillFileBytes})`
          )
          continue
        }
      } catch {
        // Entrypoint file not found — will be caught later at load time, don't exclude here
      }
    }

    // Gating: check requires prerequisites
    if (!checkSkillRequirements(name, requires, interactor)) {
      continue
    }

    const metadata: SkillMetadata = {
      name,
      description,
      path: resolvedPath,
    }

    if (typeof entrypoint === 'string' && entrypoint.length > 0) {
      metadata.entrypoint = entrypoint
    }

    if (requires) {
      metadata.requires = requires
    }

    results.push(metadata)
  }

  // Apply maxSkillsInPrompt limit
  if (results.length > maxSkillsInPrompt) {
    const excluded = results.slice(maxSkillsInPrompt)
    for (const skill of excluded) {
      interactor.warn(`Skill '${skill.name}' excluded: maxSkillsInPrompt (${maxSkillsInPrompt}) reached`)
    }
    return results.slice(0, maxSkillsInPrompt)
  }

  return results
}
