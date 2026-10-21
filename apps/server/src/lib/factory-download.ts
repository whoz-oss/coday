/**
 * factory-download.ts
 *
 * Provision the Factory JARs from the GitHub Release assets (same release as the AgentOS JARs:
 * `release/<version>`, or `workstream-<slug>-<n>` for workstream prereleases). The set of JARs
 * (service + every `factory-*-plugin`) is derived from the release's `checksums.sha256`,
 * nothing is listed by hand — see jar-set.ts for the rules, the layout and the completeness check.
 *
 *   <configDir>/factory/factory-service-<version>.jar
 *   <configDir>/factory/plugins/factory-<name>-plugin-<version>.jar
 *
 * The requested version always wins: when the set is not complete for it, the full set is
 * downloaded (blocking) and every other version is removed. An older installed version is
 * never started instead.
 *
 * INVARIANTS: never throws, no side effects at import time.
 */

import { DownloadOutcome, DownloadResult, ensureJarSet, jarFilePath } from './jar-set'

export type FactoryDownloadOutcome = DownloadOutcome
export type FactoryDownloadResult = DownloadResult

/** Path of a Factory artifact in the managed layout. */
export function factoryJarPath(configDir: string, artifactId: string, version: string): string {
  return jarFilePath(configDir, 'factory', artifactId, version)
}

/** Make sure the full Factory JAR set is installed at `version` (see ensureJarSet). */
export function downloadFactoryJars(configDir: string, version: string): Promise<FactoryDownloadResult> {
  return ensureJarSet(configDir, 'factory', version)
}
