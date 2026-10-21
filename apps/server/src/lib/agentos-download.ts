/**
 * agentos-download.ts
 *
 * Provision the AgentOS JARs from the GitHub Release assets. The set of JARs (service + every
 * `agentos-*-plugin`) is derived from the release's `checksums.sha256`, nothing is listed by
 * hand — see jar-set.ts for the rules, the layout and the completeness check.
 *
 *   <configDir>/agentos/agentos-service-<version>.jar
 *   <configDir>/agentos/plugins/agentos-<name>-plugin-<version>.jar
 *
 * The requested version always wins: when the set is not complete for it, the full set is
 * downloaded (blocking) and every other version is removed.
 *
 * INVARIANTS: never throws, no side effects at import time.
 */

import { DownloadOutcome, DownloadResult, ensureJarSet } from './jar-set'

export type { DownloadOutcome, DownloadResult }

/** Make sure the full AgentOS JAR set is installed at `version` (see ensureJarSet). */
export function downloadAgentosJars(configDir: string, version: string): Promise<DownloadResult> {
  return ensureJarSet(configDir, 'agentos', version)
}
