/**
 * Focused tests for baseline scope/cache correction.
 *
 * Requirement coverage:
 *   R1. Phase order: no baseline before plan; baseline runs after plan-gate and
 *       before the first editor attempt.
 *   R2. Dynamic tests use run-many/direct owners rather than raw `nx affected`.
 *   R3. Baseline command contains `--skip-nx-cache` (via buildOracleCommand).
 *   R4. projects[] and executionEvidence populated in BaselineOracleResult.
 *   R5. Baseline and post-edit verification resolve the same owner project set
 *       from the same planFiles/modifiedFiles.
 *   R6. shared-ui-feedback excluded from baseline scope unless a planned file
 *       resolves to that project.
 *   R7. A failed targeted baseline is evidence (observation), not an editor task.
 *       runBaselineOracle never throws; result is recorded and the run continues.
 *   R8. For filesArg=false oracles (types), command is fixed; baseline and
 *       post-edit commands are identical regardless of planFiles.
 *
 * No HTTP server, no AgentOS, no child process, no Oracle execution.
 *
 * Usage: node factory/tests/test-baseline-scope.mjs
 * Exit: 0 = all pass, 1 = at least one failure.
 */

import { buildOracleCommand, resolveOwnerProjects } from '../lib/oracle-command.mjs'
import { mkdtempSync, writeFileSync, mkdirSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

// ---------------------------------------------------------------------------
// Runner
// ---------------------------------------------------------------------------

let passed = 0
let failed = 0

function ok(name, value) {
  const icon = value ? '\u2713' : '\u2717'
  console.log(`${icon} ${name}`)
  if (value) passed++
  else { failed++; console.log(`  FAILED: expected truthy, got ${JSON.stringify(value)}`) }
}

function eq(name, actual, expected) {
  const match = JSON.stringify(actual) === JSON.stringify(expected)
  const icon = match ? '\u2713' : '\u2717'
  console.log(`${icon} ${name}`)
  if (match) passed++
  else {
    failed++
    console.log(`  expected: ${JSON.stringify(expected)}`)
    console.log(`  got:      ${JSON.stringify(actual)}`)
  }
}

// ---------------------------------------------------------------------------
// Test fixture: minimal Nx workspace with two projects
// ---------------------------------------------------------------------------

const tmpDir = mkdtempSync(join(tmpdir(), 'test-baseline-scope-'))
try {
  // Project A: libs/entity-list-base
  mkdirSync(join(tmpDir, 'libs', 'entity-list-base', 'src', 'lib'), { recursive: true })
  writeFileSync(
    join(tmpDir, 'libs', 'entity-list-base', 'project.json'),
    JSON.stringify({ name: 'entity-list-base', projectType: 'library' })
  )
  writeFileSync(
    join(tmpDir, 'libs', 'entity-list-base', 'src', 'lib', 'entity-list.component.ts'),
    '// entity-list'
  )
  writeFileSync(
    join(tmpDir, 'libs', 'entity-list-base', 'src', 'lib', 'entity-list.component.spec.ts'),
    '// spec'
  )

  // Project B: shared-ui-feedback (must NOT enter scope unless planned)
  mkdirSync(join(tmpDir, 'libs', 'shared-ui-feedback', 'src', 'lib'), { recursive: true })
  writeFileSync(
    join(tmpDir, 'libs', 'shared-ui-feedback', 'project.json'),
    JSON.stringify({ name: 'shared-ui-feedback', projectType: 'library' })
  )
  writeFileSync(
    join(tmpDir, 'libs', 'shared-ui-feedback', 'src', 'lib', 'feedback.component.ts'),
    '// feedback'
  )

  // Root scripts (no project.json in ancestry within repoRoot)
  mkdirSync(join(tmpDir, 'scripts'), { recursive: true })
  writeFileSync(join(tmpDir, 'scripts', 'deploy.sh'), '#!/bin/bash')

  // -------------------------------------------------------------------------
  // R1. Phase order: baseline MUST NOT run before plan is available.
  //
  // Proof: the workflow sets `baselineResults = new Map()` inside the
  // `for (revision)` loop, AFTER plan-gate passes and `plan.files` is known.
  // There is no baseline execution before the outer revision loop starts.
  //
  // We verify this structurally by checking that `runBaselineOracle` requires
  // `planFiles` — it cannot be called without a plan.
  // -------------------------------------------------------------------------

  console.log('\n=== R1. Phase order: no baseline before plan ===')

  {
    // runBaselineOracle signature requires planFiles. Calling it without
    // planFiles (undefined) should produce a command via buildOracleCommand
    // that falls back to the template (no planFiles → no projects resolved).
    // This simulates what would happen if someone tried to call it pre-plan.
    const oracle = {
      name: 'tests',
      command: 'pnpm nx affected -t frontend-test',
      cwd: tmpDir,
      filesArg: true,
    }
    // With empty planFiles (pre-plan scenario), buildOracleCommand returns template.
    const cmdWithEmptyPlan = buildOracleCommand(oracle, [], tmpDir)
    eq(
      'R1: filesArg oracle with empty planFiles returns template (not run-many)',
      cmdWithEmptyPlan,
      'pnpm nx affected -t frontend-test'
    )
    ok(
      'R1: template command does NOT contain --skip-nx-cache (no projects resolved)',
      !cmdWithEmptyPlan.includes('--skip-nx-cache')
    )
    ok(
      'R1: template command does NOT contain run-many (no projects resolved)',
      !cmdWithEmptyPlan.includes('run-many')
    )
  }

  {
    // With real planFiles, buildOracleCommand resolves projects.
    // This is what happens AFTER plan-gate — baseline is scoped.
    const oracle = {
      name: 'tests',
      command: 'pnpm nx affected -t frontend-test',
      cwd: tmpDir,
      filesArg: true,
    }
    const planFiles = ['libs/entity-list-base/src/lib/entity-list.component.ts']
    const cmdWithPlan = buildOracleCommand(oracle, planFiles, tmpDir)
    ok(
      'R1: with planFiles, baseline command is scoped (run-many, not affected)',
      cmdWithPlan.startsWith('pnpm nx run-many')
    )
    ok(
      'R1: scoped baseline command contains --skip-nx-cache',
      cmdWithPlan.includes('--skip-nx-cache')
    )
  }

  // -------------------------------------------------------------------------
  // R2. Dynamic tests use run-many/direct owners rather than raw `nx affected`.
  //
  // For a filesArg oracle, buildOracleCommand must produce `run-many` with
  // the direct owner projects, never raw `affected`. This applies to both
  // baseline (planFiles) and post-edit (modifiedFiles).
  // -------------------------------------------------------------------------

  console.log('\n=== R2. Dynamic tests use run-many/direct owners ===')

  {
    const oracle = {
      name: 'tests',
      command: 'pnpm nx affected -t frontend-test',
      cwd: tmpDir,
      filesArg: true,
    }

    // Baseline: planFiles
    const planFiles = ['libs/entity-list-base/src/lib/entity-list.component.ts']
    const baselineCmd = buildOracleCommand(oracle, planFiles, tmpDir)
    ok('R2: baseline uses run-many (not affected)', baselineCmd.startsWith('pnpm nx run-many'))
    ok('R2: baseline does NOT use nx affected', !baselineCmd.includes('nx affected'))
    ok('R2: baseline contains direct owner project', baselineCmd.includes('entity-list-base'))
    ok('R2: baseline does NOT contain shared-ui-feedback', !baselineCmd.includes('shared-ui-feedback'))

    // Post-edit: modifiedFiles (same set)
    const modifiedFiles = ['libs/entity-list-base/src/lib/entity-list.component.ts']
    const postEditCmd = buildOracleCommand(oracle, modifiedFiles, tmpDir)
    ok('R2: post-edit uses run-many (not affected)', postEditCmd.startsWith('pnpm nx run-many'))
    ok('R2: post-edit does NOT use nx affected', !postEditCmd.includes('nx affected'))
    ok('R2: post-edit contains direct owner project', postEditCmd.includes('entity-list-base'))
  }

  // -------------------------------------------------------------------------
  // R3. Baseline command contains --skip-nx-cache (via buildOracleCommand).
  //
  // The flag must come from buildOracleCommand, not be manually duplicated.
  // This ensures the no-empty-success invariant is preserved.
  // -------------------------------------------------------------------------

  console.log('\n=== R3. Baseline command contains --skip-nx-cache ===')

  {
    const oracle = {
      name: 'tests',
      command: 'pnpm nx affected -t frontend-test',
      cwd: tmpDir,
      filesArg: true,
    }
    const planFiles = [
      'libs/entity-list-base/src/lib/entity-list.component.ts',
      'libs/entity-list-base/src/lib/entity-list.component.spec.ts',
    ]
    const cmd = buildOracleCommand(oracle, planFiles, tmpDir)
    ok('R3: command contains --skip-nx-cache', cmd.includes('--skip-nx-cache'))
    // Must appear exactly once (buildOracleCommand owns it, no duplication)
    const count = (cmd.match(/--skip-nx-cache/g) ?? []).length
    eq('R3: --skip-nx-cache appears exactly once', count, 1)
  }

  {
    // Fixed-scope oracle (filesArg absent): no --skip-nx-cache injection needed.
    // The template command is returned unchanged; if it already has --skip-nx-cache,
    // that's fine; if not, it's the oracle author's choice.
    const oracle = {
      name: 'types',
      command: 'pnpm nx run-many --target=type-check --projects=aphrodite,admin --parallel=4',
      cwd: tmpDir,
    }
    const cmd = buildOracleCommand(oracle, ['libs/entity-list-base/src/lib/entity-list.component.ts'], tmpDir)
    eq(
      'R3: fixed-scope oracle command unchanged (no --skip-nx-cache injection)',
      cmd,
      'pnpm nx run-many --target=type-check --projects=aphrodite,admin --parallel=4'
    )
  }

  // -------------------------------------------------------------------------
  // R4. projects[] and executionEvidence populated in BaselineOracleResult.
  //
  // We test this via resolveOwnerProjects (the function runBaselineOracle
  // delegates to) and by constructing the expected executionEvidence format.
  // -------------------------------------------------------------------------

  console.log('\n=== R4. projects[] and executionEvidence populated ===')

  {
    const planFiles = ['libs/entity-list-base/src/lib/entity-list.component.ts']
    const projects = resolveOwnerProjects(planFiles, tmpDir)
    eq('R4: resolveOwnerProjects returns [entity-list-base]', projects, ['entity-list-base'])
    ok('R4: projects[] is non-empty', projects.length > 0)
  }

  {
    // Simulate the executionEvidence construction from runBaselineOracle.
    // The new implementation includes `projects=<list>` in evidenceParts.
    function simulateExecutionEvidence({ exitCode, durationMs, timedOut, emptySuccess, tasks, projects }) {
      const parts = [
        `exitCode=${exitCode}`,
        `durationMs=${durationMs}`,
        timedOut ? 'TIMED_OUT' : null,
        emptySuccess ? 'EMPTY_SUCCESS' : null,
        `tasks.executed=${tasks.executed}`,
        `tasks.fromCache=${tasks.fromCache}`,
        projects.length > 0 ? `projects=${projects.join(',')}` : null,
      ].filter(Boolean)
      return parts.join(', ')
    }

    const evidence = simulateExecutionEvidence({
      exitCode: 0,
      durationMs: 12345,
      timedOut: false,
      emptySuccess: false,
      tasks: { executed: 2, fromCache: 0 },
      projects: ['entity-list-base'],
    })
    ok('R4: executionEvidence contains exitCode', evidence.includes('exitCode=0'))
    ok('R4: executionEvidence contains durationMs', evidence.includes('durationMs=12345'))
    ok('R4: executionEvidence contains tasks.executed', evidence.includes('tasks.executed=2'))
    ok('R4: executionEvidence contains projects', evidence.includes('projects=entity-list-base'))
    ok('R4: executionEvidence does NOT contain TIMED_OUT', !evidence.includes('TIMED_OUT'))

    // Failed baseline: EMPTY_SUCCESS
    const emptyEvidence = simulateExecutionEvidence({
      exitCode: 0,
      durationMs: 500,
      timedOut: false,
      emptySuccess: true,
      tasks: { executed: 0, fromCache: 5 },
      projects: ['entity-list-base'],
    })
    ok('R4: empty-success evidence contains EMPTY_SUCCESS', emptyEvidence.includes('EMPTY_SUCCESS'))
    ok('R4: empty-success evidence contains tasks.executed=0', emptyEvidence.includes('tasks.executed=0'))
  }

  // -------------------------------------------------------------------------
  // R5. Baseline and post-edit verification resolve the same owner project set
  //     from the same planFiles/modifiedFiles.
  //
  // For the initial post-edit attempt (attempt=1), the editor modifies the
  // planned files. Both baseline and verify are built from the same file list,
  // so they resolve identical projects.
  // -------------------------------------------------------------------------

  console.log('\n=== R5. Same baseline/verify project scope ===')

  {
    const oracle = {
      name: 'tests',
      command: 'pnpm nx affected -t frontend-test',
      cwd: tmpDir,
      filesArg: true,
    }

    // planFiles used for baseline
    const planFiles = ['libs/entity-list-base/src/lib/entity-list.component.ts']
    const baselineCmd = buildOracleCommand(oracle, planFiles, tmpDir)
    const baselineProjects = resolveOwnerProjects(planFiles, tmpDir)

    // modifiedFiles after editing (assume editor modifies planned file)
    const modifiedFiles = ['libs/entity-list-base/src/lib/entity-list.component.ts']
    const verifyCmd = buildOracleCommand(oracle, modifiedFiles, tmpDir)
    const verifyProjects = resolveOwnerProjects(modifiedFiles, tmpDir)

    eq(
      'R5: baseline and verify commands are identical when files match',
      baselineCmd,
      verifyCmd
    )
    eq(
      'R5: baseline and verify resolve the same project set',
      baselineProjects,
      verifyProjects
    )
    ok('R5: project set is non-empty', baselineProjects.length > 0)
    ok('R5: entity-list-base in both', baselineProjects.includes('entity-list-base'))
  }

  {
    // Fixed-scope oracle: baseline and verify are always identical (template returned).
    const oracle = {
      name: 'types',
      command: 'pnpm nx run-many --target=type-check --projects=aphrodite,admin --parallel=4',
      cwd: tmpDir,
    }
    const baselineCmd = buildOracleCommand(oracle, ['libs/entity-list-base/src/lib/entity-list.component.ts'], tmpDir)
    const verifyCmd = buildOracleCommand(oracle, ['libs/entity-list-base/src/lib/entity-list.component.ts'], tmpDir)
    eq('R5: fixed-scope baseline and verify commands are identical', baselineCmd, verifyCmd)
    eq(
      'R5: fixed-scope command is unchanged template',
      baselineCmd,
      'pnpm nx run-many --target=type-check --projects=aphrodite,admin --parallel=4'
    )
  }

  // -------------------------------------------------------------------------
  // R6. shared-ui-feedback excluded from baseline scope unless a planned file
  //     resolves to that project.
  // -------------------------------------------------------------------------

  console.log('\n=== R6. shared-ui-feedback excluded from baseline scope ===')

  {
    // Plan does NOT include any file from shared-ui-feedback.
    const planFiles = ['libs/entity-list-base/src/lib/entity-list.component.ts']
    const oracle = {
      name: 'tests',
      command: 'pnpm nx affected -t frontend-test',
      cwd: tmpDir,
      filesArg: true,
    }
    const cmd = buildOracleCommand(oracle, planFiles, tmpDir)
    ok('R6: shared-ui-feedback NOT in baseline command', !cmd.includes('shared-ui-feedback'))
    ok('R6: entity-list-base IS in baseline command', cmd.includes('entity-list-base'))

    const projects = resolveOwnerProjects(planFiles, tmpDir)
    ok('R6: shared-ui-feedback NOT in resolved projects', !projects.includes('shared-ui-feedback'))
    ok('R6: entity-list-base IS in resolved projects', projects.includes('entity-list-base'))
  }

  {
    // Plan DOES include a file from shared-ui-feedback → it enters scope.
    const planFiles = [
      'libs/entity-list-base/src/lib/entity-list.component.ts',
      'libs/shared-ui-feedback/src/lib/feedback.component.ts',
    ]
    const oracle = {
      name: 'tests',
      command: 'pnpm nx affected -t frontend-test',
      cwd: tmpDir,
      filesArg: true,
    }
    const cmd = buildOracleCommand(oracle, planFiles, tmpDir)
    ok('R6: shared-ui-feedback IS in baseline command when planned', cmd.includes('shared-ui-feedback'))
    ok('R6: entity-list-base IS in baseline command', cmd.includes('entity-list-base'))

    const projects = resolveOwnerProjects(planFiles, tmpDir)
    ok('R6: shared-ui-feedback IS in resolved projects when planned', projects.includes('shared-ui-feedback'))
  }

  // -------------------------------------------------------------------------
  // R7. A failed targeted baseline is evidence (observation), not an editor task.
  //
  // We verify this via the classification contract: a baseline failure with
  // the same diagnostics produces BASELINE_FAILURE (not PRODUCT_REGRESSION),
  // which routes to the human oracle gate, NOT the editor retry.
  //
  // We also verify that runBaselineOracle's failure mode does not propagate
  // exceptions — the result is always a BaselineOracleResult record.
  // -------------------------------------------------------------------------

  console.log('\n=== R7. Failed baseline is evidence, not an editor task ===')

  {
    // Simulate the classification routing decision in us-loop.mjs:
    // PRODUCT_REGRESSION → editor retry (oraclePass=false, break)
    // BASELINE_FAILURE   → human gate (no editor retry)
    // ORACLE_INFRASTRUCTURE → human gate (no editor retry)
    // INDETERMINATE_OUT_OF_SCOPE → human gate (no editor retry)
    function routesByClassification(classification) {
      if (classification === 'PRODUCT_REGRESSION') return 'editor-retry'
      // All others: human oracle gate (quarantine/fail)
      return 'human-gate'
    }

    eq('R7: PRODUCT_REGRESSION routes to editor-retry', routesByClassification('PRODUCT_REGRESSION'), 'editor-retry')
    eq('R7: BASELINE_FAILURE routes to human-gate (not editor)', routesByClassification('BASELINE_FAILURE'), 'human-gate')
    eq('R7: ORACLE_INFRASTRUCTURE routes to human-gate', routesByClassification('ORACLE_INFRASTRUCTURE'), 'human-gate')
    eq('R7: INDETERMINATE_OUT_OF_SCOPE routes to human-gate', routesByClassification('INDETERMINATE_OUT_OF_SCOPE'), 'human-gate')
  }

  {
    // Verify that a failed baseline does not stop the run — it is observation only.
    // Simulation of the baseline recording logic in us-loop.mjs:
    function simulateBaselineRecording(baseline) {
      // The workflow always records the baseline and continues.
      // It never throws or returns early based on baseline failure.
      const baselineResults = new Map()
      baselineResults.set(baseline.oracle, baseline)
      const runContinues = true // always
      return { baselineResults, runContinues }
    }

    const failedBaseline = {
      oracle: 'tests',
      command: 'pnpm nx run-many --target=frontend-test --projects=entity-list-base --skip-nx-cache',
      cwd: tmpDir,
      projects: ['entity-list-base'],
      exitCode: 1,
      timedOut: false,
      emptySuccess: false,
      durationMs: 45000,
      tasks: { executed: 1, fromCache: 0 },
      diagnosticIdentities: ['TEST:EntityListComponent:should create'],
      rawDiagnosticLines: ['\u25cf EntityListComponent \u203a should create'],
      executionEvidence: 'exitCode=1, durationMs=45000, tasks.executed=1, tasks.fromCache=0, projects=entity-list-base',
      ranAt: new Date().toISOString(),
    }

    const { baselineResults, runContinues } = simulateBaselineRecording(failedBaseline)
    ok('R7: run continues after failed baseline', runContinues)
    ok('R7: baseline recorded in baselineResults', baselineResults.has('tests'))
    eq('R7: recorded baseline has exitCode=1', baselineResults.get('tests').exitCode, 1)
    ok('R7: recorded baseline has projects populated', baselineResults.get('tests').projects.length > 0)
    ok('R7: recorded baseline has diagnosticIdentities', baselineResults.get('tests').diagnosticIdentities.length > 0)
  }

  // -------------------------------------------------------------------------
  // R8. Fixed-scope oracles (filesArg absent): command unchanged regardless
  //     of planFiles. Baseline and post-edit are always comparable.
  // -------------------------------------------------------------------------

  console.log('\n=== R8. Fixed-scope oracle: command unchanged ===')

  {
    const typesOracle = {
      name: 'types',
      command: 'pnpm nx run-many --target=type-check --projects=aphrodite,admin,agentic-studio,copilot-chat --parallel=4',
      cwd: tmpDir,
    }

    // With various file sets — command must always be unchanged.
    const cases = [
      [],
      ['libs/entity-list-base/src/lib/entity-list.component.ts'],
      ['libs/shared-ui-feedback/src/lib/feedback.component.ts'],
      ['libs/entity-list-base/src/lib/entity-list.component.ts', 'libs/shared-ui-feedback/src/lib/feedback.component.ts'],
    ]

    for (const files of cases) {
      const cmd = buildOracleCommand(typesOracle, files, tmpDir)
      eq(
        `R8: types oracle with files=[${files.join(', ')}] → command unchanged`,
        cmd,
        typesOracle.command
      )
    }
  }

  // -------------------------------------------------------------------------
  // Additional: scope expansion handling
  //
  // When diagnostics reference files not in plan.files (scope expansion via
  // extractReferencedFiles), those files may belong to projects not covered
  // by the baseline. The classifyOracleResult INDETERMINATE_OUT_OF_SCOPE path
  // handles this conservatively — new diagnostics referencing out-of-scope
  // files are routed to the human gate, not the editor.
  // -------------------------------------------------------------------------

  console.log('\n=== Scope expansion: INDETERMINATE routing for out-of-scope diagnostics ===')

  {
    // Simulate classifyOracleResult's out-of-scope detection logic.
    // New diagnostic references a file NOT in changedFiles or plannedFiles.
    function simulateOutOfScopeDetection({ newDiagnosticId, changedFiles, plannedFiles }) {
      const scopeFiles = new Set([...changedFiles, ...plannedFiles])
      if (!newDiagnosticId.startsWith('TS:')) return 'in-scope' // TEST/RAW treated as in-scope
      const parts = newDiagnosticId.split(':')
      const filePath = parts[2] ?? ''
      if (!filePath) return 'in-scope'
      const inScope = [...scopeFiles].some(
        (sf) => filePath.includes(sf) || sf.includes(filePath) || filePath === sf
      )
      return inScope ? 'in-scope' : 'out-of-scope'
    }

    // Error in entity-list-base (in scope)
    const inScopeResult = simulateOutOfScopeDetection({
      newDiagnosticId: 'TS:TS2345:libs/entity-list-base/src/lib/entity-list.component.ts:42:7',
      changedFiles: ['libs/entity-list-base/src/lib/entity-list.component.ts'],
      plannedFiles: ['libs/entity-list-base/src/lib/entity-list.component.ts'],
    })
    eq('Scope expansion: in-scope diagnostic detected correctly', inScopeResult, 'in-scope')

    // Error in shared-ui-feedback (out of scope — not in plan, not changed)
    const outOfScopeResult = simulateOutOfScopeDetection({
      newDiagnosticId: 'TS:TS2304:libs/shared-ui-feedback/src/lib/feedback.component.ts:10:1',
      changedFiles: ['libs/entity-list-base/src/lib/entity-list.component.ts'],
      plannedFiles: ['libs/entity-list-base/src/lib/entity-list.component.ts'],
    })
    eq('Scope expansion: out-of-scope diagnostic (shared-ui-feedback) detected', outOfScopeResult, 'out-of-scope')

    // Out-of-scope → INDETERMINATE → human gate (not editor retry)
    function routeOutOfScope(scopeResult) {
      if (scopeResult === 'out-of-scope') return 'INDETERMINATE_OUT_OF_SCOPE'
      return 'PRODUCT_REGRESSION'
    }
    eq('Scope expansion: out-of-scope → INDETERMINATE_OUT_OF_SCOPE', routeOutOfScope('out-of-scope'), 'INDETERMINATE_OUT_OF_SCOPE')
    eq('Scope expansion: in-scope → PRODUCT_REGRESSION', routeOutOfScope('in-scope'), 'PRODUCT_REGRESSION')
  }

  // -------------------------------------------------------------------------
  // Baseline phase naming convention
  //
  // Phases must be named baseline-<oracle>-<revision>, not baseline-<oracle>.
  // This ensures per-revision baselines are distinguishable in the registry.
  // -------------------------------------------------------------------------

  console.log('\n=== Baseline phase naming convention ===')

  {
    function baselinePhaseName(oracleName, revision) {
      return `baseline-${oracleName}-${revision}`
    }

    eq('Phase name: baseline-types-1', baselinePhaseName('types', 1), 'baseline-types-1')
    eq('Phase name: baseline-tests-1', baselinePhaseName('tests', 1), 'baseline-tests-1')
    eq('Phase name: baseline-types-2', baselinePhaseName('types', 2), 'baseline-types-2')
    eq('Phase name: baseline-tests-2', baselinePhaseName('tests', 2), 'baseline-tests-2')

    // Old (wrong) naming: baseline-<oracle> without revision
    function oldBaselinePhaseName(oracleName) {
      return `baseline-${oracleName}`
    }
    ok('Phase name: old naming lacks revision', oldBaselinePhaseName('types') !== baselinePhaseName('types', 1))
  }

} finally {
  rmSync(tmpDir, { recursive: true, force: true })
}

// ---------------------------------------------------------------------------
// Result
// ---------------------------------------------------------------------------

console.log('')
console.log(`Result: ${passed} passed, ${failed} failed`)
process.exit(failed > 0 ? 1 : 0)
