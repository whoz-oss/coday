/**
 * Build gate of the workstream npm publish.
 *
 * Pure module (no nx import) so it can be unit-tested without loading the nx release API.
 *
 * WHY THIS EXISTS: `releasePublish` runs each package's `nx-release-publish` as soon as that
 * package's OWN `build` (its `dependsOn`) is done, and `web` depends on nothing. A failing build
 * of one package therefore left the others already published on npm (partial sets
 * 0.0.0-workstream-forge.310/.311, `coday-client` missing). Building everything first, in a
 * single all-or-nothing step, guarantees that nothing is published unless every build succeeded.
 */

/**
 * Builds every publishable npm package. Selected by tag so a new `platform:npm` package is built
 * automatically. `desktop` is excluded: its `nx-release-publish` is a no-op. Projects without a
 * `build` target (e.g. `web`) are skipped by run-many.
 */
export const NPM_BUILD_COMMAND =
  'pnpm nx run-many -t build --projects=tag:platform:npm --exclude=tag:desktop-bundle --outputStyle=static'

/** Runs a shell command. MUST throw when the command exits with a non-zero code. */
export type Exec = (command: string) => void

/** Builds every publishable npm package. Throws if ANY build fails, so the caller publishes nothing. */
export function buildNpmPackages(exec: Exec): void {
  console.log(`Building npm packages before any publish: ${NPM_BUILD_COMMAND}`)
  exec(NPM_BUILD_COMMAND)
}
