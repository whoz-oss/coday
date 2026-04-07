import { log } from './logger'

/**
 * Find node/npx executable using 'which' via a login shell, with fallback to
 * keg-only Homebrew versioned installs (e.g. node@24, node@22...).
 * Returns the path string or null if not found.
 */
export function findExecutable(executable: 'node' | 'npx'): string | null {
  const { execSync } = require('child_process') as typeof import('child_process')
  const { existsSync, readdirSync } = require('fs') as typeof import('fs')

  // Try login shells (-l) so ~/.zprofile, ~/.bash_profile etc. are sourced
  for (const cmd of [`/bin/zsh -lc 'which ${executable}'`, `/bin/bash -lc 'which ${executable}'`]) {
    try {
      const result = execSync(cmd, { encoding: 'utf8' }).trim()
      if (result && existsSync(result)) {
        log('INFO', `${executable} found: ${result}`)
        return result
      }
    } catch {
      // try next
    }
  }

  // Fallback: scan Homebrew opt for keg-only versioned node installs (e.g. node@24)
  for (const brewPrefix of ['/opt/homebrew/opt', '/usr/local/opt']) {
    if (!existsSync(brewPrefix)) continue
    try {
      const entries = readdirSync(brewPrefix)
        .filter((e) => /^node(@\d+)?$/.test(e))
        .sort()
        .reverse() // prefer highest version
      for (const entry of entries) {
        const candidate = `${brewPrefix}/${entry}/bin/${executable}`
        if (existsSync(candidate)) {
          log('INFO', `${executable} found via Homebrew keg-only: ${candidate}`)
          return candidate
        }
      }
    } catch {
      // continue
    }
  }

  return null
}
