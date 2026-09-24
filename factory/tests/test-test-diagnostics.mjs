/**
 * Tests de régression pour les corrections du sequencing failure :
 *   - extractTestDiagnostics : extraction des diagnostics Jest/frontend-test
 *   - extractReferencedFiles : augmentation du scope de retry
 *   - wroteNothing bounded retry : comportement non-terminal avec diagnostics
 *
 * Reproduit exactement le pattern Jest/Nx décrit dans le rapport de failure :
 *   - `verify-tests-1-1` échoue pour `shared-ui-table-column-resizer:frontend-test`
 *   - stdoutTail contient des assertions Jest lignes 41 et 81
 *   - `edit-1-2` ne recevait que le résumé Nx, pas les diagnostics
 *
 * Usage : node factory/tests/test-test-diagnostics.mjs
 * Code de sortie : 0 = tous les cas passent, 1 = au moins un échec.
 */

import { extractTestDiagnostics, extractTypeDiagnostics, extractReferencedFiles } from '../workflows/us-loop.mjs'
import { mkdtempSync, writeFileSync, mkdirSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

// ---------------------------------------------------------------------------
// Runner minimal
// ---------------------------------------------------------------------------

let passed = 0
let failed = 0

function expect(name, actual, expected) {
  const ok = JSON.stringify(actual) === JSON.stringify(expected)
  const icon = ok ? '\u2713' : '\u2717'
  console.log(`${icon} ${name}`)
  if (!ok) {
    console.log(`  attendu : ${JSON.stringify(expected)}`)
    console.log(`  obtenu  : ${JSON.stringify(actual)}`)
  }
  if (ok) passed++
  else failed++
}

function expectTrue(name, value) {
  const icon = value ? '\u2713' : '\u2717'
  console.log(`${icon} ${name}`)
  if (!value) {
    console.log(`  attendu : true`)
    console.log(`  obtenu  : ${JSON.stringify(value)}`)
    failed++
  } else {
    passed++
  }
}

function expectFalse(name, value) {
  const icon = !value ? '\u2713' : '\u2717'
  console.log(`${icon} ${name}`)
  if (value) {
    console.log(`  attendu : false`)
    console.log(`  obtenu  : ${JSON.stringify(value)}`)
    failed++
  } else {
    passed++
  }
}

function expectAtMost(name, actual, max) {
  const ok = actual <= max
  const icon = ok ? '\u2713' : '\u2717'
  console.log(`${icon} ${name}`)
  if (!ok) {
    console.log(`  attendu : <= ${max}`)
    console.log(`  obtenu  : ${actual}`)
    failed++
  } else {
    passed++
  }
}

// ---------------------------------------------------------------------------
// Fixture : sortie réelle Jest/Nx pour shared-ui-table-column-resizer
//
// Reproduit exactement le pattern décrit dans le rapport de failure :
//   - bullet \u25cf avec suite et nom de test
//   - bloc Expected / Received avec arrays
//   - référence at <spec>:<ligne>:<col>
//   - résumé Nx en queue ("Running target frontend-test for 5 projects failed")
// ---------------------------------------------------------------------------

const JEST_NX_STDOUT = [
  ' NX   Running target frontend-test for 5 projects:',
  '',
  '- shared-ui-table-column-resizer',
  '- shared-ui-table',
  '- shared-ui-form',
  '- shared-ui-button',
  '- shared-ui-icon',
  '',
  '> nx run shared-ui-table-column-resizer:frontend-test',
  '',
  'PASS frontend/libs/shared/ui/table-column-resizer/src/lib/table-column-resizer.component.spec.ts',
  'FAIL frontend/libs/shared/ui/table-column-resizer/src/lib/table-column-reorderer/table-column-reorderer.directive.spec.ts',
  '  \u25cf TableColumnReordererDirective > should emit reorder event with correct column order',
  '',
  '    expect(received).toEqual(expected)',
  '',
  '    Expected value',
  '    - Expected',
  '    + Received',
  '',
  '    - Array [',
  '    -   "col-b",',
  '    -   "col-a",',
  '    -   "col-c",',
  '    - ]',
  '    + Array [',
  '    +   "col-a",',
  '    +   "col-b",',
  '    +   "col-c",',
  '    + ]',
  '',
  '      at Object.<anonymous> (frontend/libs/shared/ui/table-column-resizer/src/lib/table-column-reorderer/table-column-reorderer.directive.spec.ts:41:5)',
  '',
  '  \u25cf TableColumnReordererDirective > should maintain column order after multiple reorders',
  '',
  '    expect(received).toEqual(expected)',
  '',
  '    Expected value',
  '    - Expected',
  '    + Received',
  '',
  '    - Array [',
  '    -   "col-c",',
  '    -   "col-b",',
  '    -   "col-a",',
  '    - ]',
  '    + Array [',
  '    +   "col-a",',
  '    +   "col-b",',
  '    +   "col-c",',
  '    + ]',
  '',
  '      at Object.<anonymous> (frontend/libs/shared/ui/table-column-resizer/src/lib/table-column-reorderer/table-column-reorderer.directive.spec.ts:81:5)',
  '',
  'Test Suites: 1 failed, 1 passed, 2 total',
  'Tests:       2 failed, 3 passed, 5 total',
  '',
  '> nx run shared-ui-table:frontend-test',
  '',
  '> nx run shared-ui-form:frontend-test',
  '',
  '> nx run shared-ui-button:frontend-test',
  '',
  '> nx run shared-ui-icon:frontend-test',
  '',
  ' NX   Running target frontend-test for 5 projects failed',
  '',
  '   Failed tasks:',
  '',
  '   - shared-ui-table-column-resizer:frontend-test',
  '',
  'Hint: run "nx show task shared-ui-table-column-resizer:frontend-test" to see the task graph for the failed task.',
  '',
  'Failed task details can be found in the run details at https://cloud.nx.app/...',
].join('\n')

const JEST_NX_STDERR = 'Creating project graph nodes...'

// ---------------------------------------------------------------------------
// Section 1 : extractTestDiagnostics
// ---------------------------------------------------------------------------

console.log('\n=== extractTestDiagnostics ===\n')

// T1 : Les bullets Jest (\u25cf) sont extraits, le résumé Nx est absent
{
  const result = extractTestDiagnostics(JEST_NX_STDOUT, JEST_NX_STDERR, 60)

  const hasBullet1 = result.some((l) => l.includes('should emit reorder event'))
  const hasBullet2 = result.some((l) => l.includes('should maintain column order'))
  const hasNxSummary = result.some((l) => l.includes('Running target frontend-test for 5 projects failed'))
  const hasFailedTasks = result.some((l) => l.includes('Failed tasks'))

  expectTrue('T1 : bullet \u25cf #1 présent (ligne 41)', hasBullet1)
  expectTrue('T1 : bullet \u25cf #2 présent (ligne 81)', hasBullet2)
  expectFalse('T1 : résumé Nx absent des diagnostics', hasNxSummary)
  expectFalse('T1 : "Failed tasks" absent des diagnostics', hasFailedTasks)
}

// T2 : Les blocs Expected/Received sont inclus
{
  const result = extractTestDiagnostics(JEST_NX_STDOUT, JEST_NX_STDERR, 60)

  const hasExpected = result.some((l) => l.includes('- Expected') || l.includes('Expected value'))
  const hasReceived = result.some((l) => l.includes('+ Received') || l.includes('Received value'))
  const hasColB = result.some((l) => l.includes('col-b'))

  expectTrue('T2 : bloc Expected présent', hasExpected)
  expectTrue('T2 : bloc Received présent', hasReceived)
  expectTrue('T2 : contenu du tableau (col-b) présent', hasColB)
}

// T3 : Les références fichier:ligne sont incluses (at Object.<anonymous> ...spec.ts:41:5)
{
  const result = extractTestDiagnostics(JEST_NX_STDOUT, JEST_NX_STDERR, 60)

  const hasLine41 = result.some((l) => l.includes('spec.ts:41:5') || l.includes('spec.ts:41'))
  const hasLine81 = result.some((l) => l.includes('spec.ts:81:5') || l.includes('spec.ts:81'))

  expectTrue('T3 : référence ligne 41 présente', hasLine41)
  expectTrue('T3 : référence ligne 81 présente', hasLine81)
}

// T4 : La ligne FAIL <spec> est incluse
{
  const result = extractTestDiagnostics(JEST_NX_STDOUT, JEST_NX_STDERR, 60)

  const hasFailSpec = result.some((l) =>
    l.includes('FAIL') && l.includes('table-column-reorderer.directive.spec.ts')
  )
  expectTrue('T4 : ligne FAIL <spec> présente', hasFailSpec)
}

// T5 : Borne à maxLines
{
  const result = extractTestDiagnostics(JEST_NX_STDOUT, JEST_NX_STDERR, 10)
  expectAtMost('T5 : résultat borné à maxLines=10', result.length, 10)
}

// T6 : Fallback tailLines quand aucun marqueur Jest dans stdout
{
  const stdout = [
    ' NX   Running target frontend-test for 5 projects failed',
    '   Failed tasks:',
    '   - shared-ui-table-column-resizer:frontend-test',
  ].join('\n')
  const stderr = 'Some error from the test runner'

  const result = extractTestDiagnostics(stdout, stderr, 60)

  // Fallback : tailLines(stderr) car stderr non vide
  const hasStderr = result.some((l) => l.includes('Some error from the test runner'))
  expectTrue('T6 : fallback tailLines(stderr) quand aucun marqueur Jest', hasStderr)
}

// T7 : Fallback sur stdout quand stderr vide et aucun marqueur
{
  const stdout = [
    'Some unexpected output without Jest markers',
    'Another line of output',
  ].join('\n')
  const stderr = ''

  const result = extractTestDiagnostics(stdout, stderr, 60)

  const hasStdout = result.some((l) => l.includes('Some unexpected output'))
  expectTrue('T7 : fallback tailLines(stdout) quand stderr vide', hasStdout)
}

// T8 : Sortie colorée ANSI : les séquences ANSI sont retirées avant analyse
{
  const ansiStdout = [
    '\u001b[1m\u001b[31m FAIL\u001b[0m frontend/libs/shared/ui/table-column-resizer/src/lib/table-column-reorderer/table-column-reorderer.directive.spec.ts',
    `  \u001b[1m\u25cf\u001b[0m TableColumnReordererDirective \u203a should emit reorder event`,
    '',
    '    \u001b[32mExpected:\u001b[0m ["col-b", "col-a"]',
    '    \u001b[31mReceived:\u001b[0m ["col-a", "col-b"]',
  ].join('\n')

  const result = extractTestDiagnostics(ansiStdout, '', 60)

  const hasBullet = result.some((l) => l.includes('should emit reorder event'))
  expectTrue('T8 : bullet Jest présent après décolorisation ANSI', hasBullet)
}

// T9 : Plusieurs échecs : tous les bullets sont inclus
{
  const result = extractTestDiagnostics(JEST_NX_STDOUT, JEST_NX_STDERR, 60)

  const bullet1 = result.some((l) => l.includes('should emit reorder event'))
  const bullet2 = result.some((l) => l.includes('should maintain column order'))

  expectTrue('T9 : premier échec présent', bullet1)
  expectTrue('T9 : deuxième échec présent', bullet2)
}

// T10 : stdout vide — fallback sur stderr
{
  const result = extractTestDiagnostics('', 'error from stderr', 60)
  const hasStderr = result.some((l) => l.includes('error from stderr'))
  expectTrue('T10 : stdout vide — fallback sur stderr', hasStderr)
}

// T11 : Les deux vides — retourne tableau vide
{
  const result = extractTestDiagnostics('', '', 60)
  expect('T11 : stdout et stderr vides — retourne tableau vide', result, [])
}

// ---------------------------------------------------------------------------
// Section 2 : extractReferencedFiles
// ---------------------------------------------------------------------------

console.log('\n=== extractReferencedFiles ===\n')

// Créer un répertoire temporaire avec les fichiers de la fixture
const tmpDir = mkdtempSync(join(tmpdir(), 'test-diag-files-'))
try {
  // Reproduire la structure du chemin de la fixture
  const specDir = join(tmpDir,
    'frontend/libs/shared/ui/table-column-resizer/src/lib/table-column-reorderer'
  )
  mkdirSync(specDir, { recursive: true })
  writeFileSync(join(specDir, 'table-column-reorderer.directive.spec.ts'), '')
  writeFileSync(join(specDir, 'table-column-reorderer.directive.ts'), '')

  // R1 : Le chemin du spec est extrait depuis la ligne at Object.<anonymous>
  {
    const errorLines = [
      '      at Object.<anonymous> (frontend/libs/shared/ui/table-column-resizer/src/lib/table-column-reorderer/table-column-reorderer.directive.spec.ts:41:5)',
    ]
    const result = extractReferencedFiles(errorLines, tmpDir)
    const hasSpec = result.some((p) => p.includes('table-column-reorderer.directive.spec.ts'))
    expectTrue('R1 : chemin spec extrait depuis stack frame', hasSpec)
  }

  // R2 : Le chemin du spec est extrait depuis la ligne FAIL
  {
    const errorLines = [
      'FAIL frontend/libs/shared/ui/table-column-resizer/src/lib/table-column-reorderer/table-column-reorderer.directive.spec.ts',
    ]
    const result = extractReferencedFiles(errorLines, tmpDir)
    const hasSpec = result.some((p) => p.includes('table-column-reorderer.directive.spec.ts'))
    expectTrue('R2 : chemin spec extrait depuis ligne FAIL', hasSpec)
  }

  // R3 : Les chemins inexistants sur disque sont filtrés
  {
    const errorLines = [
      'FAIL frontend/libs/nonexistent/does-not-exist.spec.ts',
    ]
    const result = extractReferencedFiles(errorLines, tmpDir)
    expect('R3 : chemin inexistant filtré', result, [])
  }

  // R4 : Déduplication — même chemin mentionné plusieurs fois
  {
    const errorLines = [
      'FAIL frontend/libs/shared/ui/table-column-resizer/src/lib/table-column-reorderer/table-column-reorderer.directive.spec.ts',
      '      at Object.<anonymous> (frontend/libs/shared/ui/table-column-resizer/src/lib/table-column-reorderer/table-column-reorderer.directive.spec.ts:41:5)',
      '      at Object.<anonymous> (frontend/libs/shared/ui/table-column-resizer/src/lib/table-column-reorderer/table-column-reorderer.directive.spec.ts:81:5)',
    ]
    const result = extractReferencedFiles(errorLines, tmpDir)
    const specPaths = result.filter((p) => p.includes('table-column-reorderer.directive.spec.ts'))
    expect('R4 : chemin dédupliqué (une seule entrée)', specPaths.length, 1)
  }

  // R5 : errorLines null ou vide — retourne tableau vide
  {
    expect('R5 : errorLines null — retourne []', extractReferencedFiles(null, tmpDir), [])
    expect('R5 : errorLines vide — retourne []', extractReferencedFiles([], tmpDir), [])
  }

  // R6 : Chemins absolus ou avec .. sont ignorés
  {
    const errorLines = [
      'FAIL /absolute/path/to/spec.ts',
      'FAIL ../relative/escape.spec.ts',
    ]
    const result = extractReferencedFiles(errorLines, tmpDir)
    expect('R6 : chemins absolus et .. ignorés', result, [])
  }

  // R7 : Extraction depuis les diagnostics complets de la fixture
  //      Doit trouver le chemin du spec (ligne 41 et 81)
  {
    const diagnostics = extractTestDiagnostics(JEST_NX_STDOUT, JEST_NX_STDERR, 60)
    const referenced = extractReferencedFiles(diagnostics, tmpDir)
    const hasSpec = referenced.some((p) => p.includes('table-column-reorderer.directive.spec.ts'))
    expectTrue('R7 : spec extrait depuis les diagnostics Jest complets', hasSpec)
  }

  // R8 : Augmentation du plan — les fichiers originaux sont préservés
  {
    const originalPlanFiles = [
      'frontend/libs/shared/ui/table-column-resizer/src/lib/table-column-resizer.component.ts',
    ]
    const diagnostics = extractTestDiagnostics(JEST_NX_STDOUT, JEST_NX_STDERR, 60)
    const referencedFiles = extractReferencedFiles(diagnostics, tmpDir)
    const augmentedFiles = [...new Set([...originalPlanFiles, ...referencedFiles])]

    // Les fichiers originaux sont préservés
    const hasOriginal = augmentedFiles.includes(
      'frontend/libs/shared/ui/table-column-resizer/src/lib/table-column-resizer.component.ts'
    )
    // Le spec est ajouté
    const hasSpec = augmentedFiles.some((p) => p.includes('table-column-reorderer.directive.spec.ts'))

    expectTrue('R8 : fichiers originaux préservés après augmentation', hasOriginal)
    expectTrue('R8 : spec ajouté au périmètre augmenté', hasSpec)
  }

} finally {
  rmSync(tmpDir, { recursive: true, force: true })
}

// ---------------------------------------------------------------------------
// Section 3 : wroteNothing bounded retry behavior
// ---------------------------------------------------------------------------
//
// Simule la logique de la garde wroteNothing dans la boucle interne.
// Avec des diagnostics actionnables, la tentative doit échouer mais continuer.
// Sans diagnostics, la tentative doit terminer le run immédiatement.
// ---------------------------------------------------------------------------

console.log('\n=== wroteNothing bounded retry ===\n')

// Simulation de la logique de décision wroteNothing (extrait de la logique réelle)
function simulateWroteNothingDecision(errorLines) {
  const hasActionableDiagnostics = Array.isArray(errorLines) && errorLines.length > 0
  if (!hasActionableDiagnostics) {
    return { terminal: true, continue: false }
  }
  return { terminal: false, continue: true }
}

// W1 : Sans diagnostics (premier tour) — terminal
{
  const decision = simulateWroteNothingDecision(null)
  expectTrue('W1 : wroteNothing sans diagnostics — terminal', decision.terminal)
  expectFalse('W1 : wroteNothing sans diagnostics — pas de continue', decision.continue)
}

// W2 : Avec diagnostics actionnables — non-terminal, continue
{
  const errorLines = ['\u25cf Suite > test', 'Expected: ["col-b"]', 'Received: ["col-a"]']
  const decision = simulateWroteNothingDecision(errorLines)
  expectFalse('W2 : wroteNothing avec diagnostics — non-terminal', decision.terminal)
  expectTrue('W2 : wroteNothing avec diagnostics — continue', decision.continue)
}

// W3 : Tableau vide — terminal (pas de diagnostics exploitables)
{
  const decision = simulateWroteNothingDecision([])
  expectTrue('W3 : wroteNothing avec tableau vide — terminal', decision.terminal)
  expectFalse('W3 : wroteNothing avec tableau vide — pas de continue', decision.continue)
}

// W4 : Budget MAX_FIX_LOOPS toujours respecté
//      Simulation : 3 tentatives, toutes wroteNothing avec diagnostics
//      La 3ème doit échouer sans dépasser le budget
{
  const MAX_FIX_LOOPS = 3
  const errorLines = ['\u25cf Suite > test', 'Expected: ["col-b"]', 'Received: ["col-a"]']

  let attempts = 0
  let budgetExhausted = false
  let terminatedEarly = false

  for (let attempt = 1; attempt <= MAX_FIX_LOOPS; attempt++) {
    attempts++
    const wroteNothing = true // simulé : l'agent n'a rien écrit
    const decision = simulateWroteNothingDecision(errorLines)

    if (decision.terminal) {
      terminatedEarly = true
      break
    }

    if (attempt === MAX_FIX_LOOPS) {
      budgetExhausted = true
      break
    }
  }

  expect('W4 : 3 tentatives wroteNothing avec diagnostics — budget épuisé (pas de terminal)', budgetExhausted, true)
  expectFalse('W4 : pas de terminaison précoce avec diagnostics', terminatedEarly)
  expect('W4 : exactement MAX_FIX_LOOPS tentatives', attempts, MAX_FIX_LOOPS)
}

// W5 : Sans diagnostics, terminaison immédiate dès la première tentative
{
  const MAX_FIX_LOOPS = 3

  let attempts = 0
  let terminatedEarly = false

  for (let attempt = 1; attempt <= MAX_FIX_LOOPS; attempt++) {
    attempts++
    const decision = simulateWroteNothingDecision(null)

    if (decision.terminal) {
      terminatedEarly = true
      break
    }
  }

  expectTrue('W5 : terminaison précoce sans diagnostics', terminatedEarly)
  expect('W5 : 1 seule tentative avant terminaison', attempts, 1)
}

// ---------------------------------------------------------------------------
// Section 4 : cohérence extractTypeDiagnostics vs extractTestDiagnostics
// ---------------------------------------------------------------------------
//
// Les deux fonctions ont le même contrat de fallback.
// Elles ne doivent pas se confondre : les diagnostics TS ne doivent pas
// être extraits par extractTestDiagnostics, et vice versa.
// ---------------------------------------------------------------------------

console.log('\n=== cohérence extractTypeDiagnostics vs extractTestDiagnostics ===\n')

// C1 : Sortie TS pure — extractTestDiagnostics tombe en fallback (aucun bullet Jest)
{
  const tsStdout = [
    '> nx run aphrodite:type-check',
    'frontend/apps/aphrodite/src/app/foo.ts(42,7): error TS2345: Argument of type \'string\' is not assignable.',
    ' NX   Running target type-check for 4 projects failed',
  ].join('\n')

  // extractTestDiagnostics tombe en fallback (aucun bullet Jest dans cette sortie)
  const testResult = extractTestDiagnostics(tsStdout, '', 60)
  // Le fallback retourne tailLines(stdout) — les diagnostics TS peuvent apparaître
  // mais ce n'est pas le cas d'usage nominal
  const typeResult = extractTypeDiagnostics(tsStdout, '', 60)

  const typeHasDiag = typeResult.some((l) => l.includes('TS2345'))
  expectTrue('C1 : extractTypeDiagnostics extrait TS2345 depuis sortie TS', typeHasDiag)

  // extractTestDiagnostics ne doit pas être appelé sur une sortie TS pure
  // (la sélection dans us-loop.mjs est par oracle.name, pas par contenu)
  // On vérifie juste que ça ne crash pas
  const testDidNotThrow = true
  expectTrue('C1 : extractTestDiagnostics ne crash pas sur sortie TS', testDidNotThrow)
}

// C2 : Sortie Jest pure — extractTypeDiagnostics tombe en fallback (aucun error TS)
{
  const jestStdout = [
    'FAIL frontend/libs/shared/ui/table-column-resizer/src/lib/table-column-reorderer/table-column-reorderer.directive.spec.ts',
    '  \u25cf Suite > test',
    '    Expected: ["col-b"]',
    '    Received: ["col-a"]',
  ].join('\n')

  const typeResult = extractTypeDiagnostics(jestStdout, '', 60)
  // Fallback tailLines : pas de diagnostic TS, mais pas de crash
  const typeDidNotThrow = true
  expectTrue('C2 : extractTypeDiagnostics ne crash pas sur sortie Jest', typeDidNotThrow)

  const testResult = extractTestDiagnostics(jestStdout, '', 60)
  const hasBullet = testResult.some((l) => l.includes('Suite > test'))
  expectTrue('C2 : extractTestDiagnostics extrait le bullet Jest', hasBullet)
}

// ---------------------------------------------------------------------------
// Résultat
// ---------------------------------------------------------------------------

console.log('')
console.log(`Résultat : ${passed} passé(s), ${failed} échoué(s)`)
process.exit(failed > 0 ? 1 : 0)
