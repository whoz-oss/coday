/**
 * Pure helpers of the `web:local` packaged stack (scripts/web-local.ts). Kept free of `import.meta` and of any
 * side effect so they can be unit-tested (scripts specs are compiled as CommonJS).
 */

import * as path from 'path'

export const NPM_PACKAGES = [
  { name: '@whoz-oss/coday-client', dir: 'apps/client/dist' },
  { name: '@whoz-oss/coday-factory-cockpit', dir: 'apps/factory-cockpit/dist' },
  { name: '@whoz-oss/coday-server', dir: 'apps/server/dist' },
  { name: '@whoz-oss/coday-web', dir: 'apps/web' },
] as const

/**
 * Plugin artifact ids among the entries of `<workspace>/<kind>/`: every `<kind>-<name>-plugin` directory, the same
 * naming rule `selectArtifacts` (apps/server/src/lib/jar-set.ts) applies to a release manifest. Nothing is listed by
 * hand: a plugin added to (or removed from) the repository is picked up by `web:local` without touching the script.
 * Shared libs (`agentos-git`) and the services do not match.
 */
export function pluginArtifactIds(kind: 'agentos' | 'factory', entries: string[]): string[] {
  const prefix = `${kind}-`
  const suffix = '-plugin'
  return entries
    .filter(
      (entry) => entry.startsWith(prefix) && entry.endsWith(suffix) && entry.length > prefix.length + suffix.length
    )
    .sort()
}

/** Name of the persistent root directory, created in the user's home. */
export const DEFAULT_LOCAL_DIRNAME = '.coday-local'

export type RootSource = 'CODAY_LOCAL_DIR' | 'CODAY_FIRST_RUN_DIR (deprecated, use CODAY_LOCAL_DIR)' | 'default'

/**
 * Root of the local stack: `CODAY_LOCAL_DIR`, else the deprecated `CODAY_FIRST_RUN_DIR`, else `~/.coday-local`.
 * Persistent by default: the same root is reused by every run.
 */
export function resolveLocalRoot(env: NodeJS.ProcessEnv, home: string): { root: string; source: RootSource } {
  if (env['CODAY_LOCAL_DIR']) return { root: path.resolve(env['CODAY_LOCAL_DIR']), source: 'CODAY_LOCAL_DIR' }
  if (env['CODAY_FIRST_RUN_DIR']) {
    return {
      root: path.resolve(env['CODAY_FIRST_RUN_DIR']),
      source: 'CODAY_FIRST_RUN_DIR (deprecated, use CODAY_LOCAL_DIR)',
    }
  }
  return { root: path.join(home, DEFAULT_LOCAL_DIRNAME), source: 'default' }
}

/** Layout under the root. `config` is the --configDir, `filesFolder` is what the wizard proposes instead of ~/Coday. */
export function localLayout(root: string) {
  return {
    packs: path.join(root, 'packs'),
    install: path.join(root, 'install'),
    config: path.join(root, 'config'),
    files: path.join(root, 'files'),
    filesFolder: path.join(root, 'files', 'Coday'),
  }
}

const isInside = (child: string, parent: string): boolean => {
  const relative = path.relative(parent, child)
  return relative === '' || (!relative.startsWith('..') && !path.isAbsolute(relative))
}

/**
 * Why this root must never be used, or undefined when it is fine. The script deletes things under the root
 * (`packs/`, `install/`, and `config/` + `files/` with --fresh, everything with --clean): it must never be able
 * to reach the real `~/.coday`, `~/Coday`, the home itself, the filesystem root or the repository.
 */
export function unsafeRootReason(root: string, home: string, extraProtected: string[] = []): string | undefined {
  const resolved = path.resolve(root)
  if (path.parse(resolved).root === resolved) return 'it is the filesystem root'
  const protectedPaths = [
    [home, 'your home directory'],
    [path.join(home, '.coday'), '~/.coday'],
    [path.join(home, 'Coday'), '~/Coday'],
    ...extraProtected.map((p): [string, string] => [p, p]),
  ] as const
  for (const [protectedPath, label] of protectedPaths) {
    if (isInside(resolved, protectedPath) && protectedPath !== home) return `it is ${label} or inside it`
    if (isInside(protectedPath, resolved)) return `it is, or contains, ${label}`
  }
  return undefined
}

/** True when the parsed `web-setup.json` says the first-run wizard was completed. */
export const isSetupDone = (webSetup: unknown): boolean =>
  !!webSetup && typeof webSetup === 'object' && (webSetup as { setupDone?: unknown }).setupDone === true

/** `@whoz-oss/coday-client` -> `whoz-oss-coday-client-` (the prefix of the file name `pnpm pack` writes). */
export const tarballPrefix = (packageName: string): string => `${packageName.replace('@', '').replace('/', '-')}-`

/** package.json of the throwaway install: direct file deps + overrides so inner deps resolve to the tarballs too. */
export function installManifest(tarballs: Record<string, string>): Record<string, unknown> {
  const specs = Object.fromEntries(Object.entries(tarballs).map(([name, file]) => [name, `file:${file}`]))
  return { name: 'coday-web-local', version: '0.0.0', private: true, dependencies: specs, overrides: specs }
}

/** Where a built JAR is expected, relative to the workspace root. */
export function builtJarGlob(kind: 'agentos' | 'factory', artifactId: string): { dir: string; startsWith: string } {
  return { dir: path.join(kind, artifactId, 'build', 'libs'), startsWith: artifactId }
}

/** Layout of a hand-placed JAR for the `dev` version sentinel (see apps/server/src/lib/jar-set.ts). */
export function devJarTarget(configDir: string, kind: 'agentos' | 'factory', artifactId: string): string {
  const isService = artifactId === `${kind}-service`
  return isService
    ? path.join(configDir, kind, `${artifactId}-dev.jar`)
    : path.join(configDir, kind, 'plugins', `${artifactId}-dev.jar`)
}
