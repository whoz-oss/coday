/**
 * Pure helpers of the `web:first-run` sandbox (scripts/first-run.ts). Kept free of `import.meta` and of any
 * side effect so they can be unit-tested (scripts specs are compiled as CommonJS).
 */

import * as path from 'path'

export const NPM_PACKAGES = [
  { name: '@whoz-oss/coday-client', dir: 'apps/client/dist' },
  { name: '@whoz-oss/coday-factory-cockpit', dir: 'apps/factory-cockpit/dist' },
  { name: '@whoz-oss/coday-server', dir: 'apps/server/dist' },
  { name: '@whoz-oss/coday-web', dir: 'apps/web' },
] as const

export const AGENTOS_PLUGINS = ['bash', 'file', 'mcp', 'http', 'tmux', 'git'].map((n) => `agentos-${n}-plugin`)
export const FACTORY_PLUGINS = ['factory-forge-plugin']

/** `@whoz-oss/coday-client` -> `whoz-oss-coday-client-` (the prefix of the file name `pnpm pack` writes). */
export const tarballPrefix = (packageName: string): string => `${packageName.replace('@', '').replace('/', '-')}-`

/** package.json of the throwaway install: direct file deps + overrides so inner deps resolve to the tarballs too. */
export function installManifest(tarballs: Record<string, string>): Record<string, unknown> {
  const specs = Object.fromEntries(Object.entries(tarballs).map(([name, file]) => [name, `file:${file}`]))
  return { name: 'coday-first-run-sandbox', version: '0.0.0', private: true, dependencies: specs, overrides: specs }
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
