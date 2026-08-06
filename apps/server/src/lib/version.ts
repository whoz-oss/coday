/**
 * version.ts
 *
 * Single source of truth for the Coday server version at runtime.
 *
 * Resolution: reads `version` from the nearest `package.json` at one of two
 * deterministic paths that correspond to the two real execution contexts:
 *
 *   PROD (esbuild bundle):
 *     esbuild inlines all modules into `dist/server.js`.
 *     `import.meta.url` resolves to `file://.../dist/server.js`.
 *     `package.json` is copied to `dist/package.json` by the `build` target
 *     (`cp apps/server/package.json apps/server/dist/package.json`).
 *     Candidate: `<moduleDir>/package.json`  (same directory as the bundle)
 *
 *   DEV (tsx watch):
 *     `tsx watch apps/server/src/server.ts` runs from the monorepo root.
 *     `import.meta.url` for this file resolves to
 *     `file://.../apps/server/src/lib/version.ts`.
 *     `package.json` lives at `apps/server/package.json`.
 *     Candidate: `<moduleDir>/../../package.json`  (two levels up from src/lib/)
 *
 * If neither candidate yields a valid version string, returns the sentinel 'dev'
 * rather than throwing or returning an empty string.
 *
 * NO SIDE EFFECTS AT IMPORT TIME — all resolution happens inside the function.
 * The result is memoized after first call to avoid repeated filesystem reads.
 */

import * as fs from 'fs'
import * as path from 'path'
import { fileURLToPath } from 'url'
import { debugLog } from './log'

let _resolved: string | null = null

/**
 * Return the Coday server version string.
 *
 * Memoized: filesystem access happens at most once per process.
 * Never throws.
 */
export function getCodayVersion(): string {
  if (_resolved !== null) return _resolved

  const moduleDir = path.dirname(fileURLToPath(import.meta.url))

  // Ordered list of candidate package.json paths, one per execution context.
  // See module-level JSDoc for the rationale behind each path.
  const candidates: Array<{ filePath: string; context: string }> = [
    {
      // PROD: bundle lives in dist/, package.json is copied there by the build target
      filePath: path.join(moduleDir, 'package.json'),
      context: 'prod bundle (dist/)',
    },
    {
      // DEV: source file is at src/lib/version.ts, package.json is at apps/server/
      filePath: path.join(moduleDir, '..', '..', 'package.json'),
      context: 'dev tsx (apps/server/)',
    },
  ]

  for (const { filePath, context } of candidates) {
    try {
      const raw = fs.readFileSync(filePath, 'utf8')
      const pkg: unknown = JSON.parse(raw)
      if (
        pkg !== null &&
        typeof pkg === 'object' &&
        'version' in pkg &&
        typeof (pkg as Record<string, unknown>).version === 'string'
      ) {
        const v = (pkg as Record<string, string>).version
        if (v) {
          _resolved = v
          debugLog('VERSION', `Resolved from ${filePath} (${context}): ${v}`)
          return v
        }
      }
    } catch {
      // File absent or unreadable in this context — try next candidate
    }
  }

  // Sentinel: never return an empty string, never throw
  _resolved = 'dev'
  debugLog('VERSION', `Could not resolve version from any candidate — using sentinel 'dev'`)
  return _resolved
}
