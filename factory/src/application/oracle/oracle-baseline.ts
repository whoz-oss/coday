/**
 * Oracle baseline, diagnostic normalization, and classification.
 *
 * ## Architecture
 * Before any agent edits files, the workflow runs each oracle as a baseline. It
 * records the deterministic state of the repo BEFORE editing. After editing it
 * classifies the post-edit result against that baseline.
 *
 * ## Classification taxonomy
 *   CLEAN                      post-edit passes (exitCode === 0, work executed).
 *   PRODUCT_REGRESSION         new diagnostics attributable to edited files.
 *   BASELINE_FAILURE           post-edit failure consists only of pre-existing
 *                              diagnostics — the edit made nothing worse.
 *   ORACLE_INFRASTRUCTURE      command/config/execution failure, not product:
 *                              zero-execution, timeout, TS5090/TS6059/TS18003/
 *                              TS6305/TS6307, or no execution evidence.
 *   INDETERMINATE_OUT_OF_SCOPE new diagnostics reference files outside the union
 *                              of planned and changed files.
 *
 * ## Fail-closed
 * The baseline is observation only — it never causes the workflow to skip an
 * oracle or pass a failing check. A baseline run failure is recorded as a fact.
 *
 * Application layer: this module runs commands and uses `Date`, but its unit
 * surface is pure string processing; the diagnostic identity functions are
 * exported for tests.
 *
 * Authority: this TypeScript source is bundled into
 * `factory/runtime/factory-operational.mjs`; `factory/lib/oracle-baseline.mjs`
 * is a stateless compatibility facade re-exporting from that bundle.
 */

import { countTaskOutcomes, type TaskOutcomes } from '../../domain/oracle/oracle.js'
import { buildOracleCommand, resolveOwnerProjects, type OracleCommandSpec } from './oracle-command.js'
import { runCommand } from './oracle-executor.js'

/** TypeScript error codes that indicate config/infrastructure issues, not product bugs. */
const TS_INFRASTRUCTURE_CODES = new Set(['TS5090', 'TS6059', 'TS18003', 'TS6305', 'TS6307'])

/** Max lines to include in baseline diagnostic evidence. */
const BASELINE_TAIL_LINES = 40

/** An oracle as scoped by the baseline workflow. */
export interface BaselineOracleSpec extends OracleCommandSpec {
  name: string
  cwd: string
}

/** A durable record of a baseline oracle observation. */
export interface BaselineOracleResult {
  command: string
  cwd: string
  projects: string[]
  exitCode: number
  timedOut: boolean
  emptySuccess: boolean
  durationMs: number
  tasks: TaskOutcomes
  diagnosticIdentities: string[]
  rawDiagnosticLines: string[]
  executionEvidence: string
  ranAt: string
}

/** The classification of a post-edit oracle result. */
export type OracleClassification =
  | 'CLEAN'
  | 'PRODUCT_REGRESSION'
  | 'BASELINE_FAILURE'
  | 'ORACLE_INFRASTRUCTURE'
  | 'INDETERMINATE_OUT_OF_SCOPE'

/** The result of classifying a post-edit oracle against its baseline. */
export interface OracleClassificationResult {
  classification: OracleClassification
  reason: string
  baselineIdentities: string[]
  postEditIdentities: string[]
  newDiagnostics: string[]
  preExistingDiagnostics: string[]
  newDiagnosticLines: string[]
  baselinePassed: boolean
  postEditPassed: boolean
}

/** The post-edit observation fed to `classifyOracleResult`. */
export interface PostEditOracleResult {
  exitCode: number
  timedOut: boolean
  emptySuccess: boolean
  stdout: string
  stderr: string
  tasks: TaskOutcomes
}

// ---------------------------------------------------------------------------
// Diagnostic normalization
// ---------------------------------------------------------------------------

/** Strip ANSI escape codes from a string. */
export function stripAnsi(s: string): string {
  return s.replace(/\u001b\[[0-9;]*m/g, '')
}

/**
 * Returns true when a line is a noisy infrastructure/timing line that should
 * not be treated as a product diagnostic.
 */
function isNoiseLine(line: string): boolean {
  const t = line.trim()
  if (!t) return true
  if (t.startsWith('NX') || t.startsWith('> NX') || t.includes('Running target')) return true
  if (t.includes('Successfully ran target')) return true
  if (t.includes('Nx read the output from the cache')) return true
  if (t.includes('existing outputs match the cache')) return true
  if (t.startsWith('> nx run ')) return true
  if (t.startsWith('> Task ')) return true
  if (t.includes('Failed tasks:') || t.includes('Hint: ')) return true
  if (t.startsWith('PASS ') && !t.includes('.spec.') && !t.includes('.test.')) return true
  return false
}

/**
 * Derive a stable normalized identity string from a single diagnostic line.
 *
 *   `TS:<code>:<path>:<line>:<col>` for TypeScript errors
 *   `TEST:<suite>:<test-name>`      for Jest failures
 *   `RAW:<first-120-chars>`         otherwise
 *
 * @returns null if the line is noise and should be ignored.
 */
export function normalizeDiagnosticLine(rawLine: string): string | null {
  const line = stripAnsi(rawLine).trim()
  if (!line || isNoiseLine(line)) return null

  const tsMatch =
    line.match(/([^\s(]+(?:\.tsx?|\.json))(\(\d+,\d+\))?:\s*error\s+(TS\d+):/) ??
    line.match(/([^\s(]+)(\(\d+,\d+\))?:\s*error\s+(TS\d+):/)
  if (tsMatch) {
    const filePath = tsMatch[1] ?? ''
    const location = tsMatch[2] ?? ''
    const code = tsMatch[3] ?? ''
    const locMatch = location.match(/(\d+),(\d+)/)
    const locStr = locMatch ? `:${locMatch[1]}:${locMatch[2]}` : ''
    return `TS:${code}:${filePath}${locStr}`
  }

  const jestMatch = line.match(/^\u25cf\s+(.+?)\s+[›>]\s+(.+)$/)
  if (jestMatch) {
    const suite = (jestMatch[1] ?? '').trim()
    const testName = (jestMatch[2] ?? '').trim()
    return `TEST:${suite}:${testName}`
  }

  return `RAW:${line.slice(0, 120)}`
}

// ---------------------------------------------------------------------------
// Diagnostic line selection (ported from the us-loop diagnostic helpers so the
// operational bundle stays self-contained — no dependency on legacy workflows).
// ---------------------------------------------------------------------------

function tailLines(text: string, n: number): string[] {
  return text
    .split('\n')
    .filter((line) => line.trim().length > 0)
    .slice(-n)
}

/** Extrait les lignes de diagnostic TypeScript d'une sortie de type-check. */
function extractTypeDiagnostics(stdout: string, stderr: string, maxLines: number): string[] {
  const stdoutLines = stdout.split('\n')
  const diagnosticIndices = new Set<number>()

  for (let i = 0; i < stdoutLines.length; i++) {
    if ((stdoutLines[i] ?? '').includes('error TS')) {
      if (i > 0 && (stdoutLines[i - 1] ?? '').trim().length > 0) diagnosticIndices.add(i - 1)
      diagnosticIndices.add(i)
      if (i + 1 < stdoutLines.length && (stdoutLines[i + 1] ?? '').trim().length > 0) diagnosticIndices.add(i + 1)
      if (i + 2 < stdoutLines.length && (stdoutLines[i + 2] ?? '').trim().includes('~')) diagnosticIndices.add(i + 2)
    }
  }

  if (diagnosticIndices.size === 0) {
    const source = stderr.trim().length > 0 ? stderr : stdout
    return tailLines(source, maxLines)
  }

  const sorted = [...diagnosticIndices].sort((a, b) => a - b)
  return sorted
    .map((i) => stdoutLines[i] ?? '')
    .filter((line) => line.trim().length > 0)
    .slice(-maxLines)
}

/** Extrait les diagnostics Jest/frontend-test actionnables depuis stdout. */
function extractTestDiagnostics(stdout: string, stderr: string, maxLines: number): string[] {
  const plain = stripAnsi(stdout)
  const lines = plain.split('\n')
  const diagnosticIndices = new Set<number>()

  for (let i = 0; i < lines.length; i++) {
    const trimmed = (lines[i] ?? '').trim()

    if (trimmed.startsWith('\u25cf ') || trimmed.startsWith('\u25cf\u25cf')) {
      diagnosticIndices.add(i)
      let j = i + 1
      while (j < lines.length) {
        const next = (lines[j] ?? '').trim()
        if (next.startsWith('\u25cf ') || next.includes('Running target') || next.includes('Failed tasks')) break
        diagnosticIndices.add(j)
        j++
      }
      continue
    }

    if (trimmed.startsWith('FAIL ') && (trimmed.includes('.spec.') || trimmed.includes('.test.'))) {
      diagnosticIndices.add(i)
      continue
    }

    if (
      (trimmed.startsWith('at ') && trimmed.includes('.spec.')) ||
      (trimmed.startsWith('at ') && trimmed.includes('.test.'))
    ) {
      diagnosticIndices.add(i)
      continue
    }

    if (
      trimmed.startsWith('Expected:') ||
      trimmed.startsWith('Received:') ||
      trimmed.startsWith('Expected value') ||
      trimmed.startsWith('Received value') ||
      trimmed.startsWith('expect(') ||
      trimmed.startsWith('- Expected') ||
      trimmed.startsWith('+ Received')
    ) {
      if (i > 0) diagnosticIndices.add(i - 1)
      diagnosticIndices.add(i)
      if (i + 1 < lines.length) diagnosticIndices.add(i + 1)
      continue
    }
  }

  if (diagnosticIndices.size === 0) {
    const source = stderr.trim().length > 0 ? stderr : stdout
    return tailLines(source, maxLines)
  }

  const sorted = [...diagnosticIndices].sort((a, b) => a - b)
  return sorted
    .map((i) => lines[i] ?? '')
    .filter((line) => line.trim().length > 0)
    .slice(-maxLines)
}

/**
 * Extract and normalize diagnostic identities from oracle output. Reuses the
 * type/test line selection, then normalizes each selected line into a stable
 * identity and deduplicates while preserving order.
 */
export function extractOracleDiagnostics(
  oracleName: string,
  stdout: string,
  stderr: string,
  maxLines = 200
): { identities: string[]; rawLines: string[] } {
  let rawLines: string[]
  if (oracleName === 'types') {
    rawLines = extractTypeDiagnostics(stdout, stderr, maxLines)
  } else if (oracleName === 'tests') {
    rawLines = extractTestDiagnostics(stdout, stderr, maxLines)
  } else {
    const source = stderr.trim().length > 0 ? stderr : stdout
    rawLines = source
      .split('\n')
      .map((l) => stripAnsi(l))
      .filter((l) => l.trim().length > 0 && !isNoiseLine(l))
      .slice(-maxLines)
  }

  const identities: string[] = []
  for (const line of rawLines) {
    const id = normalizeDiagnosticLine(line)
    if (id !== null) identities.push(id)
  }

  const seen = new Set<string>()
  const uniqueIdentities: string[] = []
  for (const id of identities) {
    if (!seen.has(id)) {
      seen.add(id)
      uniqueIdentities.push(id)
    }
  }

  return { identities: uniqueIdentities, rawLines }
}

/**
 * Returns true if the diagnostic identity represents a known TypeScript
 * infrastructure/configuration error code (not a product bug).
 */
export function isInfrastructureIdentity(identity: string): boolean {
  if (!identity.startsWith('TS:')) return false
  const parts = identity.split(':')
  return TS_INFRASTRUCTURE_CODES.has(parts[1] ?? '')
}

// ---------------------------------------------------------------------------
// Baseline oracle execution
// ---------------------------------------------------------------------------

/**
 * Run a single oracle as a baseline observation (before editing). The command is
 * built through the same `buildOracleCommand` path as post-edit verification,
 * using `planFiles` as the scope input, so both resolve the same owner set.
 */
export function runBaselineOracle(params: {
  oracle: BaselineOracleSpec
  planFiles: readonly string[]
  repoRoot: string
  timeoutMs: number
}): BaselineOracleResult {
  const { oracle, planFiles, repoRoot, timeoutMs } = params

  const builtCommand = buildOracleCommand(oracle, planFiles, repoRoot)
  const command = typeof builtCommand === 'string' ? builtCommand : (builtCommand as unknown as string)
  const cwd = oracle.cwd

  const projects = oracle.filesArg ? resolveOwnerProjects(planFiles, repoRoot) : []

  const result = runCommand(command, { cwd, timeoutMs })
  const tasks = countTaskOutcomes(result.stdout + '\n' + result.stderr)

  const timedOut = result.timedOut
  const emptySuccess = result.exitCode === 0 && tasks.executed === 0

  const { identities, rawLines } =
    result.exitCode !== 0 && !timedOut && !emptySuccess
      ? extractOracleDiagnostics(oracle.name, result.stdout, result.stderr)
      : { identities: [] as string[], rawLines: [] as string[] }

  const evidenceParts = [
    `exitCode=${result.exitCode}`,
    `durationMs=${result.durationMs}`,
    timedOut ? 'TIMED_OUT' : null,
    emptySuccess ? 'EMPTY_SUCCESS' : null,
    `tasks.executed=${tasks.executed}`,
    `tasks.fromCache=${tasks.fromCache}`,
    projects.length > 0 ? `projects=${projects.join(',')}` : null,
  ].filter((part): part is string => part !== null)

  return {
    command,
    cwd,
    projects,
    exitCode: result.exitCode,
    timedOut,
    emptySuccess,
    durationMs: result.durationMs,
    tasks,
    diagnosticIdentities: identities,
    rawDiagnosticLines: rawLines.slice(0, BASELINE_TAIL_LINES),
    executionEvidence: evidenceParts.join(', '),
    ranAt: new Date().toISOString(),
  }
}

// ---------------------------------------------------------------------------
// Classification
// ---------------------------------------------------------------------------

/** Classify a post-edit oracle result relative to its baseline. */
export function classifyOracleResult(params: {
  oracle: BaselineOracleSpec
  baseline: BaselineOracleResult | null
  postEdit: PostEditOracleResult
  changedFiles: readonly string[]
  plannedFiles: readonly string[]
}): OracleClassificationResult {
  const { oracle, baseline, postEdit, changedFiles, plannedFiles } = params

  const baselinePassed = baseline !== null && baseline.exitCode === 0 && !baseline.timedOut && !baseline.emptySuccess
  const postEditPassed = postEdit.exitCode === 0 && !postEdit.timedOut && !postEdit.emptySuccess

  if (postEditPassed) {
    return {
      classification: 'CLEAN',
      reason: 'Post-edit oracle passed.',
      baselineIdentities: baseline?.diagnosticIdentities ?? [],
      postEditIdentities: [],
      newDiagnostics: [],
      preExistingDiagnostics: [],
      newDiagnosticLines: [],
      baselinePassed,
      postEditPassed: true,
    }
  }

  const { identities: postEditIdentities, rawLines: postEditRawLines } =
    !postEdit.timedOut && !postEdit.emptySuccess
      ? extractOracleDiagnostics(oracle.name, postEdit.stdout, postEdit.stderr)
      : { identities: [] as string[], rawLines: [] as string[] }

  const baselineIdentities = baseline?.diagnosticIdentities ?? []
  const baselineSet = new Set(baselineIdentities)

  if (postEdit.timedOut || postEdit.emptySuccess) {
    const reason = postEdit.timedOut
      ? `Oracle timed out (no verdict on code).`
      : `Oracle empty success (tasks.executed === 0, no verdict on code).`
    return {
      classification: 'ORACLE_INFRASTRUCTURE',
      reason,
      baselineIdentities,
      postEditIdentities: [],
      newDiagnostics: [],
      preExistingDiagnostics: [],
      newDiagnosticLines: [],
      baselinePassed,
      postEditPassed: false,
    }
  }

  if (baseline !== null && (baseline.timedOut || baseline.emptySuccess)) {
    return {
      classification: 'ORACLE_INFRASTRUCTURE',
      reason: baseline.timedOut
        ? 'Baseline oracle timed out — cannot compare post-edit diagnostics.'
        : 'Baseline oracle had empty success — cannot compare post-edit diagnostics.',
      baselineIdentities: [],
      postEditIdentities,
      newDiagnostics: postEditIdentities,
      preExistingDiagnostics: [],
      newDiagnosticLines: postEditRawLines,
      baselinePassed: false,
      postEditPassed: false,
    }
  }

  const infraIdentities = postEditIdentities.filter(isInfrastructureIdentity)
  const productIdentities = postEditIdentities.filter((id) => !isInfrastructureIdentity(id))

  if (infraIdentities.length > 0 && productIdentities.length === 0) {
    return {
      classification: 'ORACLE_INFRASTRUCTURE',
      reason: `Only TypeScript infrastructure/config error codes found: ${infraIdentities.map((id) => id.split(':')[1]).join(', ')}. Not a product regression.`,
      baselineIdentities,
      postEditIdentities,
      newDiagnostics: infraIdentities,
      preExistingDiagnostics: [],
      newDiagnosticLines: postEditRawLines,
      baselinePassed,
      postEditPassed: false,
    }
  }

  const newProductDiagnostics = productIdentities.filter((id) => !baselineSet.has(id))
  const preExistingProductDiagnostics = productIdentities.filter((id) => baselineSet.has(id))

  const newInfraDiagnostics = infraIdentities.filter((id) => !baselineSet.has(id))
  const newDiagnostics = [...newProductDiagnostics, ...newInfraDiagnostics]
  const preExistingDiagnostics = [
    ...preExistingProductDiagnostics,
    ...infraIdentities.filter((id) => baselineSet.has(id)),
  ]

  const newDiagnosticLines = postEditRawLines.filter((line) => {
    const id = normalizeDiagnosticLine(line)
    return id !== null && newDiagnostics.includes(id)
  })

  if (newProductDiagnostics.length === 0 && preExistingProductDiagnostics.length > 0) {
    return {
      classification: 'BASELINE_FAILURE',
      reason: `All ${preExistingProductDiagnostics.length} post-edit diagnostic(s) were already present at baseline. The edit did not introduce new failures.`,
      baselineIdentities,
      postEditIdentities,
      newDiagnostics: [],
      preExistingDiagnostics,
      newDiagnosticLines: [],
      baselinePassed,
      postEditPassed: false,
    }
  }

  if (newProductDiagnostics.length > 0) {
    const scopeFiles = new Set([...changedFiles, ...plannedFiles])
    const allNewInScope = newProductDiagnostics.every((id) => {
      if (id.startsWith('TS:')) {
        const parts = id.split(':')
        const filePath = parts[2] ?? ''
        if (!filePath) return false
        return [...scopeFiles].some((sf) => filePath.includes(sf) || sf.includes(filePath) || filePath === sf)
      }
      // TEST and RAW identities cannot be reliably attributed to a file — treat
      // as in-scope to avoid false INDETERMINATE classification.
      return true
    })

    if (!allNewInScope) {
      return {
        classification: 'INDETERMINATE_OUT_OF_SCOPE',
        reason: `New diagnostics reference files outside the union of planned and changed files. Cannot confidently attribute to this edit.`,
        baselineIdentities,
        postEditIdentities,
        newDiagnostics,
        preExistingDiagnostics,
        newDiagnosticLines,
        baselinePassed,
        postEditPassed: false,
      }
    }
  }

  if (newProductDiagnostics.length > 0) {
    const reason = baselinePassed
      ? `Baseline passed; post-edit introduced ${newProductDiagnostics.length} new diagnostic(s).`
      : `Baseline failed; post-edit introduced ${newProductDiagnostics.length} new diagnostic(s) beyond baseline.`
    return {
      classification: 'PRODUCT_REGRESSION',
      reason,
      baselineIdentities,
      postEditIdentities,
      newDiagnostics,
      preExistingDiagnostics,
      newDiagnosticLines,
      baselinePassed,
      postEditPassed: false,
    }
  }

  return {
    classification: 'ORACLE_INFRASTRUCTURE',
    reason: 'No classifiable diagnostics found in post-edit output.',
    baselineIdentities,
    postEditIdentities,
    newDiagnostics: [],
    preExistingDiagnostics: [],
    newDiagnosticLines: [],
    baselinePassed,
    postEditPassed: false,
  }
}

// ---------------------------------------------------------------------------
// Quarantine record
// ---------------------------------------------------------------------------

/** The subset of a baseline result carried by a quarantine record. */
export interface QuarantineBaselineRef {
  exitCode: number
  timedOut: boolean
  emptySuccess: boolean
  executionEvidence: string
  diagnosticIdentities: string[]
}

/** The subset of a post-edit result carried by a quarantine record. */
export interface QuarantinePostEditRef {
  exitCode: number
  timedOut: boolean
  emptySuccess: boolean
  durationMs: number
}

/**
 * Build a durable quarantine record for a non-product oracle failure. It never
 * rewrites a failed oracle as passed.
 */
export function buildQuarantineRecord(params: {
  oracleName: string
  classification: OracleClassification
  reason: string
  baseline: QuarantineBaselineRef | null
  postEdit: QuarantinePostEditRef
  classificationResult: OracleClassificationResult
  humanDecision: 'continue' | 'fail'
  humanMessage: string
}): Record<string, unknown> {
  const { oracleName, classification, reason, baseline, postEdit, classificationResult, humanDecision, humanMessage } =
    params

  return {
    quarantinedAt: new Date().toISOString(),
    oracleName,
    classification,
    reason,
    humanDecision,
    humanMessage: humanMessage || null,
    // The oracle remains visibly failed — never rewritten as passed.
    oracleFailed: true,
    baseline: baseline
      ? {
          exitCode: baseline.exitCode,
          timedOut: baseline.timedOut,
          emptySuccess: baseline.emptySuccess,
          executionEvidence: baseline.executionEvidence,
          diagnosticCount: baseline.diagnosticIdentities.length,
        }
      : null,
    postEdit: {
      exitCode: postEdit.exitCode,
      timedOut: postEdit.timedOut,
      emptySuccess: postEdit.emptySuccess,
      durationMs: postEdit.durationMs,
    },
    diagnostics: {
      baseline: classificationResult.baselineIdentities,
      postEdit: classificationResult.postEditIdentities,
      new: classificationResult.newDiagnostics,
      preExisting: classificationResult.preExistingDiagnostics,
    },
  }
}
