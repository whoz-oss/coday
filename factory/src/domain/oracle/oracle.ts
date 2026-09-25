/**
 * Pure oracle domain: task-outcome counting over build output and the snapshot
 * vocabulary used to detect what an edit actually changed.
 *
 * Le verdict d'un oracle reste `exitCode === 0`, rien d'autre. `countTaskOutcomes`
 * lit la sortie mais ne rend AUCUN verdict : un build entièrement servi par le
 * cache retourne `exitCode: 0` sans avoir rien exécuté. Compter les tâches dit
 * sur quoi le verdict portait, pas s'il est fondé.
 *
 * Authority: this TypeScript source is bundled into
 * `factory/runtime/factory-operational.mjs`; `factory/lib/oracle.mjs` is a
 * stateless compatibility facade re-exporting from that bundle.
 *
 * Domain purity: this module carries no `node:*` dependency at all. Process
 * execution, git access and content reads live in `application/oracle/`.
 */

/**
 * Why an Nx summary line was or was not found. `null` when a summary was found.
 *  - `no-nx-tasks`: no `> nx run` line seen (Gradle or empty output) — normal.
 *  - `fresh-run`: Nx tasks were counted but no Nx summary line was seen — an
 *    unexpected output format worth recording.
 */
export type OracleSummaryAbsenceReason = 'no-nx-tasks' | 'fresh-run' | null

/**
 * A coarse count of what a build command actually did. This is a recorded fact,
 * never a verdict.
 */
export interface TaskOutcomes {
  upToDate: number
  fromCache: number
  skipped: number
  executed: number
  summaryFound: boolean
  summaryAbsenceReason: OracleSummaryAbsenceReason
  summaryFromCache: number | null
  summaryTotal: number | null
  countMismatch: boolean
}

/**
 * Compte les issues de tâches dans la sortie d'un build.
 *
 * Reconnaît deux grammaires :
 *   - Gradle : `> Task :chemin:nom [UP-TO-DATE|FROM-CACHE|SKIPPED|NO-SOURCE]`
 *   - Nx     : `> nx run <projet>:<cible>  [existing outputs match the cache, left as is]`
 *     (marqueur de cache sur la MÊME ligne) plus les lignes de synthèse
 *     `Nx read the output from the cache ... for N out of M tasks.` et
 *     `NX   Successfully ran target <cible> for N projects`.
 *
 * @param output stdout + stderr concaténés.
 */
export function countTaskOutcomes(output: string): TaskOutcomes {
  // Nx colore sa sortie même redirigée. Les séquences ANSI sont retirées avant
  // analyse pour que les préfixes de ligne soient reconnaissables.
  const plain = output.replace(/\u001b\[[0-9;]*m/g, '')
  const lines = plain.split('\n')

  let upToDate = 0
  let fromCache = 0
  let skipped = 0
  let executed = 0
  let nxTaskLines = 0

  let cacheSummaryFound = false
  let cacheSummaryFromCache: number | null = null
  let cacheSummaryTotal: number | null = null

  let successSummaryFound = false
  let successSummaryTotal: number | null = null

  for (const line of lines) {
    // --- Gradle : `> Task :chemin:nom [MARQUEUR]`
    if (line.startsWith('> Task ')) {
      if (line.includes('UP-TO-DATE')) upToDate++
      else if (line.includes('FROM-CACHE')) fromCache++
      else if (line.includes('SKIPPED') || line.includes('NO-SOURCE')) skipped++
      else executed++
      continue
    }

    // --- Nx : `> nx run <projet>:<cible>` (marqueur de cache sur la même ligne)
    if (line.startsWith('> nx run ')) {
      nxTaskLines++
      if (line.includes('existing outputs match the cache')) fromCache++
      else executed++
      continue
    }

    // --- Ligne de synthèse cache Nx
    const cacheSummaryMatch = line.match(
      /Nx\s+read\s+the\s+output\s+from\s+the\s+cache\s+instead\s+of\s+running\s+the\s+command\s+for\s+(\d+)\s+out\s+of\s+(\d+)\s+tasks/
    )
    if (cacheSummaryMatch) {
      cacheSummaryFound = true
      cacheSummaryFromCache = parseInt(cacheSummaryMatch[1] ?? '0', 10)
      cacheSummaryTotal = parseInt(cacheSummaryMatch[2] ?? '0', 10)
      continue
    }

    // --- Ligne de succès Nx
    const successMatch = line.match(/NX\s+Successfully\s+ran\s+target\s+\S+\s+for\s+(\d+)\s+projects?/)
    if (successMatch) {
      successSummaryFound = true
      successSummaryTotal = parseInt(successMatch[1] ?? '0', 10)
      continue
    }
  }

  const summaryFound = cacheSummaryFound || successSummaryFound
  const summaryFromCache = cacheSummaryFound ? cacheSummaryFromCache : null
  const summaryTotal = cacheSummaryFound ? cacheSummaryTotal : successSummaryFound ? successSummaryTotal : null

  let summaryAbsenceReason: OracleSummaryAbsenceReason = null
  if (!summaryFound) summaryAbsenceReason = nxTaskLines === 0 ? 'no-nx-tasks' : 'fresh-run'

  // Désaccord entre mesures indépendantes : signale un changement de format.
  // `false` quand aucune ligne de synthèse n'est présente — une absence n'est
  // pas un désaccord.
  let countMismatch = false
  const lineTotal = upToDate + fromCache + skipped + executed
  if (cacheSummaryFound) countMismatch = fromCache !== cacheSummaryFromCache || lineTotal !== cacheSummaryTotal
  if (successSummaryFound) countMismatch = countMismatch || lineTotal !== successSummaryTotal

  return {
    upToDate,
    fromCache,
    skipped,
    executed,
    summaryFound,
    summaryAbsenceReason,
    summaryFromCache,
    summaryTotal,
    countMismatch,
  }
}

/**
 * Snapshot of the working tree: for each tracked-modified and untracked file,
 * the content fingerprint that lets a later diff detect a rewrite.
 *
 * The fingerprint (not the path list) is the measure: a one-line-for-one-line
 * replacement keeps the same path AND the same line counts, so only a content
 * digest can answer "did this file change".
 */
export interface OracleSnapshot {
  modified: Map<string, string>
  untracked: Map<string, string>
}

/** Paths whose content changed between two snapshots. */
export interface OracleSnapshotDelta {
  modified: string[]
  untracked: string[]
}

/**
 * Pure comparison of two snapshots. A path is retained if it appeared or if its
 * fingerprint changed. The return value is a list of paths — the fingerprints
 * are a detection means, not a recorded fact.
 */
export function diffSnapshots(before: OracleSnapshot, after: OracleSnapshot): OracleSnapshotDelta {
  const modified: string[] = []
  for (const [path, fingerprint] of after.modified) {
    if (before.modified.get(path) !== fingerprint) modified.push(path)
  }

  const untracked: string[] = []
  for (const [path, fingerprint] of after.untracked) {
    if (before.untracked.get(path) !== fingerprint) untracked.push(path)
  }

  return { modified, untracked }
}
