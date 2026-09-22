/**
 * Oracle baseline, diagnostic normalization, and classification.
 *
 * ## Architecture
 *
 * Before any agent edits files, the workflow calls `runBaselineOracle()` for each
 * oracle in the domain. This records the deterministic state of the repo BEFORE
 * editing. After editing, the workflow calls `classifyOracleResult()` to compare
 * post-edit diagnostics against the baseline.
 *
 * ## Classification taxonomy
 *
 * CLEAN
 *   Post-edit passes (exitCode === 0, tasks.executed > 0).
 *
 * PRODUCT_REGRESSION
 *   Baseline passed and post-edit failed with new diagnostics attributable to
 *   edited files; OR baseline failed and new diagnostics were introduced.
 *   Only new diagnostics go to the editor retry.
 *
 * BASELINE_FAILURE
 *   Post-edit failure consists only of diagnostics already present at baseline.
 *   No new errors introduced — the edit did not make things worse.
 *
 * ORACLE_INFRASTRUCTURE
 *   Command/config/execution failure rather than product diagnostics:
 *     - zero-execution (tasks.executed === 0, emptySuccess guard)
 *     - timeout
 *     - known TypeScript configuration codes: TS5090, TS6059, TS18003, TS6305, TS6307
 *     - no meaningful execution evidence
 *   If mixed with product diagnostics, new product diagnostics are classified
 *   separately (PRODUCT_REGRESSION) rather than hiding them.
 *
 * INDETERMINATE_OUT_OF_SCOPE
 *   Diagnostics cannot be confidently attributed and reference files outside
 *   the union of planned files and actually changed files.
 *
 * ## Diagnostic identity
 *
 * A stable normalized identity is derived from each diagnostic line:
 *   - ANSI escape codes stripped
 *   - Noisy timing/cache lines filtered
 *   - For TS errors: errorCode + relative file path + line/col
 *   - For test errors: test suite name + test name + assertion type
 *   - For others: first 120 chars of the normalized line
 *
 * Identities are stored as strings in a Set for O(1) lookup.
 *
 * ## Fail-closed
 *
 * The baseline is observation only — it never causes the workflow to skip an
 * oracle or pass a failing check. A baseline run failure is recorded as a fact.
 * If the baseline itself cannot run (timeout, infrastructure error), the
 * classification for the corresponding post-edit oracle defaults to
 * ORACLE_INFRASTRUCTURE with the baseline failure as evidence.
 */

import { runCommand, countTaskOutcomes } from './oracle.mjs'
import { extractTypeDiagnostics, extractTestDiagnostics } from '../workflows/us-loop.mjs'
import { buildOracleCommand, resolveOwnerProjects } from './oracle-command.mjs'

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

/** TypeScript error codes that indicate config/infrastructure issues, not product bugs. */
const TS_INFRASTRUCTURE_CODES = new Set(['TS5090', 'TS6059', 'TS18003', 'TS6305', 'TS6307'])

/** Max lines to include in baseline diagnostic evidence. */
const BASELINE_TAIL_LINES = 40

// ---------------------------------------------------------------------------
// Types (JSDoc)
// ---------------------------------------------------------------------------

/**
 * @typedef {{
 *   command: string,
 *   cwd: string,
 *   projects: string[],
 *   exitCode: number,
 *   timedOut: boolean,
 *   emptySuccess: boolean,
 *   durationMs: number,
 *   tasks: object,
 *   diagnosticIdentities: string[],
 *   rawDiagnosticLines: string[],
 *   executionEvidence: string,
 *   ranAt: string,
 * }} BaselineOracleResult
 *
 * @typedef {'CLEAN'|'PRODUCT_REGRESSION'|'BASELINE_FAILURE'|'ORACLE_INFRASTRUCTURE'|'INDETERMINATE_OUT_OF_SCOPE'} OracleClassification
 *
 * @typedef {{
 *   classification: OracleClassification,
 *   reason: string,
 *   baselineIdentities: string[],
 *   postEditIdentities: string[],
 *   newDiagnostics: string[],
 *   preExistingDiagnostics: string[],
 *   newDiagnosticLines: string[],
 *   baselinePassed: boolean,
 *   postEditPassed: boolean,
 * }} OracleClassificationResult
 */

// ---------------------------------------------------------------------------
// Diagnostic normalization
// ---------------------------------------------------------------------------

/**
 * Strip ANSI escape codes from a string.
 *
 * @param {string} s
 * @returns {string}
 */
function stripAnsi(s) {
  // eslint-disable-next-line no-control-regex
  return s.replace(/\u001b\[[0-9;]*m/g, '')
}

/**
 * Returns true when a line is a noisy infrastructure/timing line that should
 * not be treated as a product diagnostic.
 *
 * @param {string} line
 * @returns {boolean}
 */
function isNoiseLine(line) {
  const t = line.trim()
  if (!t) return true
  // Nx summary/timing lines
  if (t.startsWith('NX') || t.startsWith('> NX') || t.includes('Running target')) return true
  if (t.includes('Successfully ran target')) return true
  if (t.includes('Nx read the output from the cache')) return true
  if (t.includes('existing outputs match the cache')) return true
  if (t.startsWith('> nx run ')) return true
  if (t.startsWith('> Task ')) return true
  if (t.includes('Failed tasks:') || t.includes('Hint: ')) return true
  // pnpm/node timing
  if (t.startsWith('PASS ') && !t.includes('.spec.') && !t.includes('.test.')) return true
  return false
}

/**
 * Derive a stable normalized identity string from a single diagnostic line.
 *
 * For TypeScript errors:
 *   `TS:<code>:<relative-path>:<line>:<col>`
 *   e.g. `TS:TS2345:src/app/foo.ts:42:7`
 *
 * For Jest test failures (bullet ● lines):
 *   `TEST:<suite>:<test-name>`
 *   e.g. `TEST:FooComponent:should create`
 *
 * For other lines:
 *   `RAW:<first-120-chars-normalized>`
 *
 * @param {string} rawLine
 * @returns {string|null}  null if the line is noise and should be ignored
 */
export function normalizeDiagnosticLine(rawLine) {
  const line = stripAnsi(rawLine).trim()
  if (!line || isNoiseLine(line)) return null

  // TypeScript error: `path/to/file.ts(line,col): error TSxxxx: message`
  // Also matches tsconfig.json files (e.g. TS5090 from tsconfig.app.json).
  const tsMatch =
    line.match(/([^\s(]+(?:\.tsx?|\.json))(\(\d+,\d+\))?:\s*error\s+(TS\d+):/) ??
    line.match(/([^\s(]+)(\(\d+,\d+\))?:\s*error\s+(TS\d+):/)
  if (tsMatch) {
    const filePath = tsMatch[1]
    const location = tsMatch[2] ?? ''
    const code = tsMatch[3]
    // Normalize location: extract line,col or leave empty
    const locMatch = location.match(/(\d+),(\d+)/)
    const locStr = locMatch ? `:${locMatch[1]}:${locMatch[2]}` : ''
    return `TS:${code}:${filePath}${locStr}`
  }

  // Jest bullet: `● SuiteName › test name` or `● SuiteName > test name`
  const jestMatch = line.match(/^\u25cf\s+(.+?)\s+[›>]\s+(.+)$/)
  if (jestMatch) {
    const suite = jestMatch[1].trim()
    const testName = jestMatch[2].trim()
    return `TEST:${suite}:${testName}`
  }

  // Generic: first 120 chars of normalized line
  return `RAW:${line.slice(0, 120)}`
}

/**
 * Extract and normalize diagnostic identities from oracle output.
 *
 * Reuses `extractTypeDiagnostics` / `extractTestDiagnostics` for line selection,
 * then normalizes each selected line into a stable identity.
 *
 * @param {string} oracleName
 * @param {string} stdout
 * @param {string} stderr
 * @param {number} [maxLines=200]
 * @returns {{ identities: string[], rawLines: string[] }}
 */
export function extractOracleDiagnostics(oracleName, stdout, stderr, maxLines = 200) {
  let rawLines
  if (oracleName === 'types') {
    rawLines = extractTypeDiagnostics(stdout, stderr, maxLines)
  } else if (oracleName === 'tests') {
    rawLines = extractTestDiagnostics(stdout, stderr, maxLines)
  } else {
    // Generic fallback: non-empty non-noise lines from stderr then stdout
    const source = stderr.trim().length > 0 ? stderr : stdout
    rawLines = source
      .split('\n')
      .map((l) => stripAnsi(l))
      .filter((l) => l.trim().length > 0 && !isNoiseLine(l))
      .slice(-maxLines)
  }

  const identities = []
  for (const line of rawLines) {
    const id = normalizeDiagnosticLine(line)
    if (id !== null) identities.push(id)
  }

  // Deduplicate while preserving order
  const seen = new Set()
  const uniqueIdentities = []
  for (const id of identities) {
    if (!seen.has(id)) {
      seen.add(id)
      uniqueIdentities.push(id)
    }
  }

  return { identities: uniqueIdentities, rawLines }
}

// ---------------------------------------------------------------------------
// Infrastructure code detection
// ---------------------------------------------------------------------------

/**
 * Returns true if the diagnostic identity represents a known TypeScript
 * infrastructure/configuration error code (not a product bug).
 *
 * @param {string} identity
 * @returns {boolean}
 */
export function isInfrastructureIdentity(identity) {
  if (!identity.startsWith('TS:')) return false
  const parts = identity.split(':')
  // identity format: TS:<code>:<path>:<line>:<col>
  const code = parts[1]
  return TS_INFRASTRUCTURE_CODES.has(code)
}

// ---------------------------------------------------------------------------
// Baseline oracle execution
// ---------------------------------------------------------------------------

/**
 * Run a single oracle as a baseline observation (before editing).
 *
 * The baseline command is built through the same `buildOracleCommand` path
 * as post-edit verification, using `planFiles` as the scope input. This
 * ensures baseline and post-edit verification are comparable: both resolve
 * the same owner project set from the same file list.
 *
 * For oracles without `filesArg` (e.g. `types`, `build`), the command is
 * fixed and independent of `planFiles` — baseline and post-edit are always
 * comparable for those.
 *
 * For oracles with `filesArg: true` (e.g. `tests`), the baseline runs
 * `run-many --projects=<owners of planFiles> --skip-nx-cache`, which is
 * exactly what post-edit verification will run for the same `planFiles`.
 * This guarantees that `shared-ui-feedback` and other unrelated projects
 * never enter baseline scope unless a planned file resolves to them.
 *
 * The baseline is OBSERVATION ONLY — it never causes the workflow to skip
 * an oracle or pass a failing check. A baseline run failure is recorded as
 * a durable fact. If the baseline itself cannot run (timeout, empty), the
 * classification for the corresponding post-edit oracle defaults to
 * ORACLE_INFRASTRUCTURE.
 *
 * @param {{
 *   oracle: { name: string, command: string, cwd: string, filesArg?: boolean },
 *   planFiles: string[],
 *   repoRoot: string,
 *   timeoutMs: number,
 * }} params
 * @returns {BaselineOracleResult}
 */
export function runBaselineOracle({ oracle, planFiles, repoRoot, timeoutMs }) {
  // Build the command through buildOracleCommand with planFiles as scope.
  // For fixed-scope oracles (filesArg absent/false), this returns oracle.command
  // unchanged. For filesArg oracles, this builds run-many --projects=<owners>
  // --skip-nx-cache — identical to what post-edit verification will build for
  // the same planFiles.
  const command = buildOracleCommand(oracle, planFiles, repoRoot)
  const cwd = oracle.cwd

  // Resolve and record the projects that this baseline covers (for durable facts
  // and comparability assertion in tests).
  const projects = oracle.filesArg ? resolveOwnerProjects(planFiles, repoRoot) : []

  const result = runCommand(command, { cwd, timeoutMs })
  const tasks = countTaskOutcomes(result.stdout + '\n' + result.stderr)

  const timedOut = result.timedOut
  const emptySuccess = result.exitCode === 0 && tasks.executed === 0

  const { identities, rawLines } =
    result.exitCode !== 0 && !timedOut && !emptySuccess
      ? extractOracleDiagnostics(oracle.name, result.stdout, result.stderr)
      : { identities: [], rawLines: [] }

  // Build a brief execution evidence string for the review packet.
  const evidenceParts = [
    `exitCode=${result.exitCode}`,
    `durationMs=${result.durationMs}`,
    timedOut ? 'TIMED_OUT' : null,
    emptySuccess ? 'EMPTY_SUCCESS' : null,
    `tasks.executed=${tasks.executed}`,
    `tasks.fromCache=${tasks.fromCache}`,
    projects.length > 0 ? `projects=${projects.join(',')}` : null,
  ].filter(Boolean)

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

/**
 * Classify a post-edit oracle result relative to its baseline.
 *
 * @param {{
 *   oracle: { name: string, command: string, cwd: string, filesArg?: boolean },
 *   baseline: BaselineOracleResult | null,
 *   postEdit: {
 *     exitCode: number,
 *     timedOut: boolean,
 *     emptySuccess: boolean,
 *     stdout: string,
 *     stderr: string,
 *     tasks: object,
 *   },
 *   changedFiles: string[],
 *   plannedFiles: string[],
 * }} params
 * @returns {OracleClassificationResult}
 */
export function classifyOracleResult({ oracle, baseline, postEdit, changedFiles, plannedFiles }) {
  const baselinePassed = baseline !== null && baseline.exitCode === 0 && !baseline.timedOut && !baseline.emptySuccess
  const postEditPassed = postEdit.exitCode === 0 && !postEdit.timedOut && !postEdit.emptySuccess

  // CLEAN: post-edit passes.
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

  // Post-edit failed (or infrastructure issue). Extract diagnostics.
  const { identities: postEditIdentities, rawLines: postEditRawLines } =
    !postEdit.timedOut && !postEdit.emptySuccess
      ? extractOracleDiagnostics(oracle.name, postEdit.stdout, postEdit.stderr)
      : { identities: [], rawLines: [] }

  const baselineIdentities = baseline?.diagnosticIdentities ?? []
  const baselineSet = new Set(baselineIdentities)

  // ORACLE_INFRASTRUCTURE: timeout or empty-success.
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

  // ORACLE_INFRASTRUCTURE: baseline itself timed out or was empty-success.
  // We cannot compare against a baseline that never measured anything.
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

  // Separate infrastructure identities from product identities in post-edit output.
  const infraIdentities = postEditIdentities.filter(isInfrastructureIdentity)
  const productIdentities = postEditIdentities.filter((id) => !isInfrastructureIdentity(id))

  // ORACLE_INFRASTRUCTURE: only infrastructure error codes, no product diagnostics.
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

  // From here: we have product diagnostics (possibly mixed with infra codes).
  // Compute new vs pre-existing relative to baseline.
  const newProductDiagnostics = productIdentities.filter((id) => !baselineSet.has(id))
  const preExistingProductDiagnostics = productIdentities.filter((id) => baselineSet.has(id))

  // Also check infra identities for pre-existing status
  const newInfraDiagnostics = infraIdentities.filter((id) => !baselineSet.has(id))
  const newDiagnostics = [...newProductDiagnostics, ...newInfraDiagnostics]
  const preExistingDiagnostics = [
    ...preExistingProductDiagnostics,
    ...infraIdentities.filter((id) => baselineSet.has(id)),
  ]

  // Build raw lines for new diagnostics (for editor brief)
  // We include lines that contain any new identity's key parts.
  const newDiagnosticLines = postEditRawLines.filter((line) => {
    const id = normalizeDiagnosticLine(line)
    return id !== null && newDiagnostics.includes(id)
  })

  // BASELINE_FAILURE: all post-edit diagnostics were already present at baseline.
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

  // INDETERMINATE_OUT_OF_SCOPE: new diagnostics reference files outside the
  // union of planned and changed files.
  if (newProductDiagnostics.length > 0) {
    const scopeFiles = new Set([...changedFiles, ...plannedFiles])
    const allNewInScope = newProductDiagnostics.every((id) => {
      // Extract file path from identity (TS:<code>:<path>:... or TEST:... or RAW:...)
      if (id.startsWith('TS:')) {
        const parts = id.split(':')
        const filePath = parts[2] ?? ''
        if (!filePath) return false
        // Check if any scope file matches or is a prefix
        return [...scopeFiles].some((sf) => filePath.includes(sf) || sf.includes(filePath) || filePath === sf)
      }
      // For TEST and RAW identities: cannot reliably attribute to a file — treat as in-scope
      // to avoid false INDETERMINATE classification
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

  // PRODUCT_REGRESSION: new product diagnostics attributable to edited files.
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

  // Fallback: no product diagnostics, no infra — unexpected state.
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

/**
 * Build a durable quarantine record for a non-product oracle failure.
 *
 * This is stored in the JSONL facts and included in the review packet.
 * It never rewrites a failed oracle as passed.
 *
 * @param {{
 *   oracleName: string,
 *   classification: OracleClassification,
 *   reason: string,
 *   baseline: BaselineOracleResult | null,
 *   postEdit: { exitCode: number, timedOut: boolean, emptySuccess: boolean, durationMs: number },
 *   classificationResult: OracleClassificationResult,
 *   humanDecision: 'continue' | 'fail',
 *   humanMessage: string,
 * }} params
 * @returns {object}
 */
export function buildQuarantineRecord(params) {
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
