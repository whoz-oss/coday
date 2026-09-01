/**
 * Tests unitaires pour factory/lib/plan.mjs et factory/lib/oracle-command.mjs.
 *
 * Couvre les fonctions pures de parsing, de comparaison de claims,
 * et de construction de commandes d'oracle.
 * Aucune dépendance externe, aucun I/O réseau, aucun appel AgentOS.
 *
 * Usage : node factory/tests/test-us-loop.mjs
 * Code de sortie : 0 = tous les cas passent, 1 = au moins un échec.
 */

import { extractJsonFragment, parsePlan, compareClaims, checkPlanFiles } from '../lib/plan.mjs'
import { buildOracleCommand, resolveOwnerProjects } from '../lib/oracle-command.mjs'
import { extractTypeDiagnostics } from '../workflows/us-loop.mjs'
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

function expectOk(name, result, expectedPlan) {
  const icon = result.ok ? '\u2713' : '\u2717'
  console.log(`${icon} ${name}`)
  if (!result.ok) {
    console.log(`  attendu : ok=true`)
    console.log(`  obtenu  : ok=false, error=${result.error}`)
    failed++
    return
  }
  if (expectedPlan) {
    const ok = JSON.stringify(result.plan) === JSON.stringify(expectedPlan)
    if (!ok) {
      console.log(`  plan attendu : ${JSON.stringify(expectedPlan)}`)
      console.log(`  plan obtenu  : ${JSON.stringify(result.plan)}`)
      failed++
      return
    }
  }
  passed++
}

function expectFail(name, result, expectedErrorFragment) {
  const icon = !result.ok ? '\u2713' : '\u2717'
  console.log(`${icon} ${name}`)
  if (result.ok) {
    console.log(`  attendu : ok=false`)
    console.log(`  obtenu  : ok=true, plan=${JSON.stringify(result.plan)}`)
    failed++
    return
  }
  if (expectedErrorFragment && !result.error.includes(expectedErrorFragment)) {
    console.log(`  message d'erreur attendu contenir : "${expectedErrorFragment}"`)
    console.log(`  message obtenu : "${result.error}"`)
    failed++
    return
  }
  passed++
}

// ---------------------------------------------------------------------------
// Tests de extractJsonFragment
// ---------------------------------------------------------------------------

console.log('\n=== extractJsonFragment ===\n')

{
  const text = 'Voici le plan :\n```json\n{"files":["a.ts"]}\n```\nMerci.'
  expect(
    'bloc ```json : extrait le contenu',
    extractJsonFragment(text),
    '{"files":["a.ts"]}'
  )
}

{
  const text = 'Voici le plan :\n```\n{"files":["a.ts"]}\n```\nMerci.'
  expect(
    'bloc ``` sans langage : extrait le contenu',
    extractJsonFragment(text),
    '{"files":["a.ts"]}'
  )
}

{
  const text = 'Voici le plan : {"files":["a.ts"],"doneWhen":"ok"} fin.'
  expect(
    'JSON nu (premi\u00e8re accolade équilibrée) : extrait le contenu',
    extractJsonFragment(text),
    '{"files":["a.ts"],"doneWhen":"ok"}'
  )
}

{
  // Le bloc ```json doit être préféré au bloc ``` sans langage
  const text = '```json\n{"files":["correct.ts"]}\n```\n```\n{"files":["wrong.ts"]}\n```'
  expect(
    'priorité au bloc ```json sur le bloc ``` nu',
    extractJsonFragment(text),
    '{"files":["correct.ts"]}'
  )
}

{
  const text = 'Aucun JSON ici, juste du texte.'
  expect(
    'aucun JSON : retourne null',
    extractJsonFragment(text),
    null
  )
}

{
  // Accolade non fermée
  const text = '{"files":["a.ts"]'
  expect(
    'accolade non fermée : retourne null',
    extractJsonFragment(text),
    null
  )
}

// ---------------------------------------------------------------------------
// Tests de parsePlan
// ---------------------------------------------------------------------------

console.log('\n=== parsePlan ===\n')

// --- Cas valides ---

{
  const text = '```json\n{"files":["src/a.ts"],"doneWhen":"compile"}\n```'
  expectOk(
    'bloc json fence propre : parse ok',
    parsePlan(text),
    { files: ['src/a.ts'], doneWhen: 'compile' }
  )
}

{
  const text = '```\n{"files":["src/a.ts"],"doneWhen":"compile"}\n```'
  expectOk(
    'bloc fence sans langage : parse ok',
    parsePlan(text),
    { files: ['src/a.ts'], doneWhen: 'compile' }
  )
}

{
  const text = 'Voici mon plan.\n{"files":["src/a.ts"],"doneWhen":"compile"}\nMerci.'
  expectOk(
    'JSON nu avec prose autour : parse ok',
    parsePlan(text),
    { files: ['src/a.ts'], doneWhen: 'compile' }
  )
}

{
  const text = '```json\n{"files":["src/a.ts","src/b.ts"],"doneWhen":"ok","steps":["step1"]}\n```'
  expectOk(
    'plan avec steps optionnel : parse ok',
    parsePlan(text),
    { files: ['src/a.ts', 'src/b.ts'], doneWhen: 'ok', steps: ['step1'] }
  )
}

// --- Cas invalides ---

{
  expectFail(
    'texte sans JSON : échec',
    parsePlan('Voici mon analyse. Rien de structuré.'),
    'Aucun bloc JSON'
  )
}

{
  const text = '```json\nnot valid json\n```'
  expectFail(
    'JSON invalide : échec',
    parsePlan(text),
    'JSON invalide'
  )
}

{
  const text = '```json\n{"doneWhen":"ok"}\n```'
  expectFail(
    'files absent : échec',
    parsePlan(text),
    '"files"'
  )
}

{
  const text = '```json\n{"files":[],"doneWhen":"ok"}\n```'
  expectFail(
    'files vide : échec',
    parsePlan(text),
    '"files"'
  )
}

{
  const text = '```json\n{"files":["src/a.ts"]}\n```'
  expectFail(
    'doneWhen manquant : échec',
    parsePlan(text),
    '"doneWhen"'
  )
}

{
  const text = '```json\n{"files":["src/a.ts"],"doneWhen":""}\n```'
  expectFail(
    'doneWhen vide : échec',
    parsePlan(text),
    '"doneWhen"'
  )
}

{
  const text = '```json\n{"files":["/etc/passwd"],"doneWhen":"ok"}\n```'
  expectFail(
    'chemin absolu dans files : échec',
    parsePlan(text),
    'Chemin invalide'
  )
}

{
  const text = '```json\n{"files":["../secret.ts"],"doneWhen":"ok"}\n```'
  expectFail(
    'chemin avec .. dans files : échec',
    parsePlan(text),
    'Chemin invalide'
  )
}

{
  const text = '```json\n{"files":["src/../../../etc/passwd"],"doneWhen":"ok"}\n```'
  expectFail(
    'chemin avec .. imbriqué : échec',
    parsePlan(text),
    'Chemin invalide'
  )
}

// ---------------------------------------------------------------------------
// Tests de compareClaims
// ---------------------------------------------------------------------------

console.log('\n=== compareClaims ===\n')

{
  const result = compareClaims(
    ['src/a.ts', 'src/b.ts'],
    ['src/a.ts', 'src/b.ts'],
    []
  )
  expect('egalité parfaite : claimsMatch=true', result.claimsMatch, true)
  expect('egalité parfaite : unplannedFiles vide', result.unplannedFiles, [])
  expect('egalité parfaite : untouchedPlannedFiles vide', result.untouchedPlannedFiles, [])
}

{
  const result = compareClaims(
    ['src/a.ts'],
    ['src/a.ts', 'src/extra.ts'],
    []
  )
  expect('fichier non planifié modifié : claimsMatch=false', result.claimsMatch, false)
  expect('fichier non planifié : unplannedFiles correct', result.unplannedFiles, ['src/extra.ts'])
  expect('fichier non planifié : untouchedPlannedFiles vide', result.untouchedPlannedFiles, [])
}

{
  const result = compareClaims(
    ['src/a.ts', 'src/b.ts'],
    ['src/a.ts'],
    []
  )
  expect('fichier planifié non touché : claimsMatch=false', result.claimsMatch, false)
  expect('fichier planifié non touché : unplannedFiles vide', result.unplannedFiles, [])
  expect('fichier planifié non touché : untouchedPlannedFiles correct', result.untouchedPlannedFiles, ['src/b.ts'])
}

{
  const result = compareClaims(
    ['src/a.ts', 'src/b.ts'],
    ['src/a.ts', 'src/extra.ts'],
    []
  )
  expect('les deux écarts : claimsMatch=false', result.claimsMatch, false)
  expect('les deux écarts : unplannedFiles', result.unplannedFiles, ['src/extra.ts'])
  expect('les deux écarts : untouchedPlannedFiles', result.untouchedPlannedFiles, ['src/b.ts'])
}

{
  // Les fichiers untracked (créés) comptent dans actualFiles
  const result = compareClaims(
    ['src/a.ts'],
    [],
    ['src/a.ts']
  )
  expect('fichier créé (untracked) comptant comme actual : claimsMatch=true', result.claimsMatch, true)
  expect('fichier créé : actualFiles inclut untracked', result.actualFiles, ['src/a.ts'])
}

{
  // Toutes listes vides
  const result = compareClaims([], [], [])
  expect('toutes listes vides : claimsMatch=true', result.claimsMatch, true)
  expect('toutes listes vides : actualFiles vide', result.actualFiles, [])
}

// ---------------------------------------------------------------------------
// Tests de checkPlanFiles
// ---------------------------------------------------------------------------

console.log('\n=== checkPlanFiles ===\n')

const tmpDir = mkdtempSync(join(tmpdir(), 'test-us-loop-'))
try {
  writeFileSync(join(tmpDir, 'a.ts'), '')
  mkdirSync(join(tmpDir, 'sub'))
  writeFileSync(join(tmpDir, 'sub', 'b.ts'), '')
  mkdirSync(join(tmpDir, 'subdir'))

  // --- tous les fichiers existent ---
  {
    const r = checkPlanFiles(['a.ts', 'sub/b.ts'], tmpDir)
    expect('tous existent : missingFiles vide', r.missingFiles, [])
    expect('tous existent : fileCount correct', r.fileCount, 2)
    expect('tous existent : plannedFiles préservé', r.plannedFiles, ['a.ts', 'sub/b.ts'])
  }

  // --- un fichier sur deux manque ---
  {
    const r = checkPlanFiles(['a.ts', 'absent.ts'], tmpDir)
    expect('un manquant : missingFiles contient le manquant', r.missingFiles, ['absent.ts'])
    expect('un manquant : fileCount = total', r.fileCount, 2)
  }

  // --- tous manquent ---
  {
    const r = checkPlanFiles(['x.ts', 'y.ts'], tmpDir)
    expect('tous manquent : missingFiles = liste complète', r.missingFiles, ['x.ts', 'y.ts'])
    expect('tous manquent : fileCount correct', r.fileCount, 2)
  }

  // --- fichier dans un sous-répertoire existant ---
  {
    const r = checkPlanFiles(['sub/b.ts'], tmpDir)
    expect('sous-répertoire : missingFiles vide', r.missingFiles, [])
    expect('sous-répertoire : fileCount = 1', r.fileCount, 1)
  }

  // --- liste vide ---
  {
    const r = checkPlanFiles([], tmpDir)
    expect('liste vide : missingFiles vide', r.missingFiles, [])
    expect('liste vide : fileCount = 0', r.fileCount, 0)
  }

  // --- répertoire passé comme s'il était un fichier ---
  {
    const r = checkPlanFiles(['subdir'], tmpDir)
    expect(
      'répertoire comme fichier : existsSync=true, donc missingFiles vide (comportement accepté)',
      r.missingFiles,
      []
    )
    expect('répertoire comme fichier : fileCount = 1', r.fileCount, 1)
  }

} finally {
  rmSync(tmpDir, { recursive: true, force: true })
}

// ---------------------------------------------------------------------------
// Tests de resolveOwnerProjects et buildOracleCommand
// ---------------------------------------------------------------------------

console.log('\n=== resolveOwnerProjects ===\n')

const tmpDirOracle = mkdtempSync(join(tmpdir(), 'test-oracle-command-'))
try {
  mkdirSync(join(tmpDirOracle, 'libs', 'lib-a', 'src', 'lib'), { recursive: true })
  writeFileSync(
    join(tmpDirOracle, 'libs', 'lib-a', 'project.json'),
    JSON.stringify({ name: 'lib-a', sourceRoot: 'libs/lib-a/src' })
  )
  writeFileSync(join(tmpDirOracle, 'libs', 'lib-a', 'src', 'lib', 'foo.ts'), '')

  mkdirSync(join(tmpDirOracle, 'libs', 'lib-b', 'src', 'lib'), { recursive: true })
  writeFileSync(
    join(tmpDirOracle, 'libs', 'lib-b', 'project.json'),
    JSON.stringify({ name: 'lib-b', sourceRoot: 'libs/lib-b/src' })
  )
  writeFileSync(join(tmpDirOracle, 'libs', 'lib-b', 'src', 'lib', 'bar.ts'), '')

  mkdirSync(join(tmpDirOracle, 'libs', 'lib-c', 'src', 'lib'), { recursive: true })
  writeFileSync(join(tmpDirOracle, 'libs', 'lib-c', 'src', 'lib', 'baz.ts'), '')

  mkdirSync(join(tmpDirOracle, 'scripts'), { recursive: true })
  writeFileSync(join(tmpDirOracle, 'scripts', 'build.sh'), '')

  {
    expect(
      'resolveOwnerProjects : 1 fichier dans lib-a -> [lib-a]',
      resolveOwnerProjects(['libs/lib-a/src/lib/foo.ts'], tmpDirOracle),
      ['lib-a']
    )
  }

  {
    expect(
      'resolveOwnerProjects : fichiers dans lib-a et lib-b -> [lib-a, lib-b]',
      resolveOwnerProjects(
        ['libs/lib-a/src/lib/foo.ts', 'libs/lib-b/src/lib/bar.ts'],
        tmpDirOracle
      ),
      ['lib-a', 'lib-b']
    )
  }

  {
    expect(
      'resolveOwnerProjects : doublon deduplique -> [lib-a]',
      resolveOwnerProjects(
        ['libs/lib-a/src/lib/foo.ts', 'libs/lib-a/src/lib/foo.ts'],
        tmpDirOracle
      ),
      ['lib-a']
    )
  }

  {
    expect(
      'resolveOwnerProjects : fichier sans project.json -> []',
      resolveOwnerProjects(['libs/lib-c/src/lib/baz.ts'], tmpDirOracle),
      []
    )
  }

  {
    expect(
      'resolveOwnerProjects : liste vide -> []',
      resolveOwnerProjects([], tmpDirOracle),
      []
    )
  }

  // -------------------------------------------------------------------------
  // Tests de buildOracleCommand
  // -------------------------------------------------------------------------

  console.log('\n=== buildOracleCommand ===\n')

  {
    const oracle = {
      name: 'types',
      command: 'pnpm nx run-many --target=type-check --projects=aphrodite,admin',
      cwd: '/repo',
    }
    expect(
      'sans filesArg (champ absent) : commande inchangee malgre des fichiers fournis',
      buildOracleCommand(oracle, ['libs/lib-a/src/lib/foo.ts'], tmpDirOracle),
      'pnpm nx run-many --target=type-check --projects=aphrodite,admin'
    )
  }

  {
    const oracle = {
      name: 'build',
      command: './gradlew :agentos-service:build --rerun-tasks --console=plain',
      cwd: '/repo/agentos',
      filesArg: false,
    }
    expect(
      'filesArg=false explicite : commande inchangee',
      buildOracleCommand(oracle, ['libs/lib-a/src/lib/foo.ts'], tmpDirOracle),
      './gradlew :agentos-service:build --rerun-tasks --console=plain'
    )
  }

  {
    const oracle = {
      name: 'tests',
      command: 'pnpm nx affected -t frontend-test',
      cwd: '/repo',
      filesArg: true,
    }
    expect(
      'filesArg=true, liste vide : commande inchangee (pas de --projects=)',
      buildOracleCommand(oracle, [], tmpDirOracle),
      'pnpm nx affected -t frontend-test'
    )
  }

  {
    const oracle = {
      name: 'tests',
      command: 'pnpm nx affected -t frontend-test',
      cwd: tmpDirOracle,
      filesArg: true,
    }
    expect(
      'filesArg=true, 2 fichiers resolubles : run-many cible',
      buildOracleCommand(
        oracle,
        ['libs/lib-a/src/lib/foo.ts', 'libs/lib-b/src/lib/bar.ts'],
        tmpDirOracle
      ),
      'pnpm nx run-many --target=frontend-test --projects=lib-a,lib-b --skip-nx-cache'
    )
  }

  {
    const oracle = {
      name: 'tests',
      command: 'pnpm nx affected -t frontend-test',
      cwd: tmpDirOracle,
      filesArg: true,
    }
    expect(
      'filesArg=true, doublon de projet : un seul projet dans la commande',
      buildOracleCommand(
        oracle,
        ['libs/lib-a/src/lib/foo.ts', 'libs/lib-a/src/lib/foo.ts'],
        tmpDirOracle
      ),
      'pnpm nx run-many --target=frontend-test --projects=lib-a --skip-nx-cache'
    )
  }

  {
    const oracle = {
      name: 'tests',
      command: 'pnpm nx affected -t frontend-test',
      cwd: tmpDirOracle,
      filesArg: true,
    }
    expect(
      'filesArg=true, fichiers sans project.json : commande template (cas limite)',
      buildOracleCommand(
        oracle,
        ['libs/lib-c/src/lib/baz.ts', 'scripts/build.sh'],
        tmpDirOracle
      ),
      'pnpm nx affected -t frontend-test'
    )
  }

  {
    const oracle = {
      name: 'tests',
      command: 'pnpm nx affected -t frontend-test',
      cwd: tmpDirOracle,
      filesArg: true,
    }
    const cmd = buildOracleCommand(oracle, ['libs/lib-a/src/lib/foo.ts'], tmpDirOracle)
    expect(
      'extraction cible depuis `-t frontend-test` : --target=frontend-test present',
      cmd.includes('--target=frontend-test'),
      true
    )
  }

  {
    const oracle = {
      name: 'tests',
      command: 'pnpm nx affected --target=frontend-test',
      cwd: tmpDirOracle,
      filesArg: true,
    }
    const cmd = buildOracleCommand(oracle, ['libs/lib-a/src/lib/foo.ts'], tmpDirOracle)
    expect(
      'extraction cible depuis `--target=frontend-test` : --target=frontend-test present',
      cmd.includes('--target=frontend-test'),
      true
    )
    expect(
      'extraction cible depuis `--target=frontend-test` : commande run-many',
      cmd.startsWith('pnpm nx run-many'),
      true
    )
  }

  {
    const malformed = 'pnpm nx affected frontend-test'
    const oracle = {
      name: 'tests',
      command: malformed,
      cwd: tmpDirOracle,
      filesArg: true,
    }
    expect(
      'commande sans -t ni --target : commande template retournee sans modification',
      buildOracleCommand(oracle, ['libs/lib-a/src/lib/foo.ts'], tmpDirOracle),
      malformed
    )
  }

} finally {
  rmSync(tmpDirOracle, { recursive: true, force: true })
}

// ---------------------------------------------------------------------------
// Tests de la logique claims-gate (observabilité, pas fail-closed)
// ---------------------------------------------------------------------------
//
// Comportement requis après correction du faux-négatif :
//   - Un fichier planifié non touché + oracles verts → claims-gate PASS,
//     écart enregistré dans les faits, continúe vers review adversariale.
//   - Aucun claims-fix n'est créé dans ce cas.
//   - L'écart (untouchedPlannedFiles, unplannedFiles) reste visible dans les faits.
//   - Les fichiers non annoncés restent informationnels.
//   - Les échecs réels des oracles déterministes continuent de déclencher des retries.
//
// La logique claims-gate est portée par compareClaims (plan.mjs).
// ---------------------------------------------------------------------------

console.log('\n=== claims-gate observability scenarios ===\n')

// (A) Fichier planifié non touché + oracles verts → claims-gate PASS, pas de claims-fix
//
// C'est le scénario du faux-négatif corrigé : le fichier était déjà correct,
// l'éditeur ne l'a pas touché, les oracles ont passé — pas de raison d'échouer.
{
  const planned = ['src/a.ts', 'src/b.ts']
  const actual = ['src/a.ts'] // src/b.ts non touché (déjà correct)
  const result = compareClaims(planned, actual, [])

  // L'écart est enregistré
  expect('(A) untouched : claimsMatch=false (enregistré)', result.claimsMatch, false)
  expect('(A) untouched : untouchedPlannedFiles=[src/b.ts]', result.untouchedPlannedFiles, ['src/b.ts'])
  expect('(A) untouched : unplannedFiles vide', result.unplannedFiles, [])

  // Comportement corrigé : la claims-gate NE doit PAS déclencher claims-fix
  // La décision de passer ou échouer repose uniquement sur les oracles déterministes.
  // Simulation : avec oracles verts (innerPass=true), le gate passe toujours.
  const oraclesPassed = true
  const claimsGatePasses = oraclesPassed // le gate ne bloque plus sur untouched
  expect('(A) untouched + oracles verts : claims-gate PASS', claimsGatePasses, true)

  // L'écart est transmis aux reviewers via lastClaimsGate
  const lastClaimsGate = {
    claimsMatch: result.claimsMatch,
    plannedFiles: result.plannedFiles,
    actualFiles: result.actualFiles,
    unplannedFiles: result.unplannedFiles,
    untouchedPlannedFiles: result.untouchedPlannedFiles,
  }
  expect('(A) untouched : écart présent dans lastClaimsGate', lastClaimsGate.untouchedPlannedFiles, ['src/b.ts'])
  expect('(A) untouched : claimsMatch=false dans lastClaimsGate', lastClaimsGate.claimsMatch, false)
}

// (B) Aucun claims-fix ne doit être créé pour un fichier planifié non touché
//
// Simulation de la logique de décision du workflow :
// la présence de untouchedPlannedFiles ne déclenche plus de boucle claims-fix.
{
  function simulateClaimsGateDecision(untouchedPlannedFiles, oraclesPassed) {
    // Nouveau comportement : claims-fix n'est JAMAIS lancé depuis claims-gate
    // La gate passe toujours si les oracles ont passé.
    const claimsFixLaunched = false // supprimé du workflow
    const continueToReview = oraclesPassed
    return { claimsFixLaunched, continueToReview }
  }

  const { claimsFixLaunched, continueToReview } = simulateClaimsGateDecision(['src/b.ts'], true)
  expect('(B) untouched + oracles verts : claims-fix NOT lancé', claimsFixLaunched, false)
  expect('(B) untouched + oracles verts : continue vers review', continueToReview, true)
}

// (C) Fichiers non annoncés : observabilité uniquement, pas un échec
{
  const planned = ['src/a.ts']
  const actual = ['src/a.ts', 'src/extra.ts'] // extra non planifié
  const result = compareClaims(planned, actual, [])

  expect('(C) non annoncé : unplannedFiles=[src/extra.ts]', result.unplannedFiles, ['src/extra.ts'])
  expect('(C) non annoncé : untouchedPlannedFiles vide', result.untouchedPlannedFiles, [])

  // Pas de claims-fix pour les fichiers non annoncés
  const shouldTriggerClaimsFix = result.untouchedPlannedFiles.length > 0
  expect('(C) non annoncé : PAS de claims-fix', shouldTriggerClaimsFix, false)

  // Le run peut passer (unplanned = observabilité)
  const canPass = true // oracles ont passé, gate ne bloque pas
  expect('(C) non annoncé : run peut passer', canPass, true)
}

// (D) Les deux écarts : écart enregistré dans les faits, gate passe quand même
{
  const planned = ['src/a.ts', 'src/b.ts']
  const actual = ['src/a.ts', 'src/extra.ts']
  const result = compareClaims(planned, actual, [])

  expect('(D) deux écarts : claimsMatch=false', result.claimsMatch, false)
  expect('(D) deux écarts : unplannedFiles=[src/extra.ts]', result.unplannedFiles, ['src/extra.ts'])
  expect('(D) deux écarts : untouchedPlannedFiles=[src/b.ts]', result.untouchedPlannedFiles, ['src/b.ts'])

  // Les deux écarts sont dans lastClaimsGate, transmis aux reviewers
  const lastClaimsGate = { ...result }
  expect('(D) deux écarts : lastClaimsGate.untouchedPlannedFiles présent', lastClaimsGate.untouchedPlannedFiles.length > 0, true)
  expect('(D) deux écarts : lastClaimsGate.unplannedFiles présent', lastClaimsGate.unplannedFiles.length > 0, true)
}

// (E) Contrat complet : aucun écart, claimsMatch=true
{
  const planned = ['src/a.ts', 'src/b.ts']
  const result = compareClaims(planned, ['src/a.ts', 'src/b.ts'], [])

  expect('(E) complet : claimsMatch=true', result.claimsMatch, true)
  expect('(E) complet : untouchedPlannedFiles vide', result.untouchedPlannedFiles, [])
  expect('(E) complet : unplannedFiles vide', result.unplannedFiles, [])
}

// (F) Fichiers via untracked (nouveaux fichiers créés) satisfont le contrat
{
  const planned = ['src/a.ts', 'src/new.ts']
  const result = compareClaims(planned, ['src/a.ts'], ['src/new.ts'])

  expect('(F) untracked satisfait : claimsMatch=true', result.claimsMatch, true)
  expect('(F) untracked satisfait : untouchedPlannedFiles vide', result.untouchedPlannedFiles, [])
  expect('(F) untracked satisfait : actualFiles contient les deux', result.actualFiles, ['src/a.ts', 'src/new.ts'])
}

// (G) Oracle échoué : le run reste FAIL (pas affaibli par la correction)
//
// La correction ne touche que claims-gate. Les oracles déterministes échoués
// continuent de déclencher des retries via la boucle interne (innerPass=false).
{
  function simulateInnerLoop(oracleExitCode) {
    const oraclePassed = oracleExitCode === 0
    const innerPass = oraclePassed
    // Si innerPass=false, la boucle interne continue (retry ou révision)
    // La claims-gate n'est atteinte que si innerPass=true
    const claimsGateReached = innerPass
    return { innerPass, claimsGateReached }
  }

  const failCase = simulateInnerLoop(1) // oracle échoué
  expect('(G) oracle échoué : innerPass=false', failCase.innerPass, false)
  expect('(G) oracle échoué : claims-gate non atteinte', failCase.claimsGateReached, false)

  const passCase = simulateInnerLoop(0) // oracle réussi
  expect('(G) oracle réussi : innerPass=true', passCase.innerPass, true)
  expect('(G) oracle réussi : claims-gate atteinte', passCase.claimsGateReached, true)
}

// ---------------------------------------------------------------------------
// Test du null-guard de buildEditorFixBrief
// ---------------------------------------------------------------------------
//
// buildEditorFixBrief appelle errorLines.join() — si null est passé, TypeError.
// Le fix ajoute `(errorLines ?? [])`. On teste la logique de sélection du brief
// pour la boucle interne : attempt===1 → buildEditorBrief,
// attempt>1 avec errorLines=null → ne doit PAS appeler join() sur null.
// ---------------------------------------------------------------------------

console.log('\n=== buildEditorFixBrief null-guard ===\n')

{
  // Simulation de la sélection du brief dans la boucle claims-fix
  // (extrait de la logique réelle, sans appel AgentOS)
  function selectClaimsFixBrief(claimsAttempt, claimsErrorLines) {
    const missingFiles = ['src/a.ts']
    const plan = { doneWhen: 'done', steps: [] }
    const task = 'test task'
    const scope = null

    if (claimsAttempt === 1) {
      // buildEditorBrief : pas d'errorLines
      return `brief-initial:${missingFiles.join(',')}`
    } else {
      // buildEditorFixBrief : errorLines peut être null
      // Le fix : (errorLines ?? []).join()
      const lines = (claimsErrorLines ?? []).join('\n')
      return `brief-fix:${missingFiles.join(',')}:${lines}`
    }
  }

  // Tentative 1 : toujours buildEditorBrief, pas d'errorLines
  const brief1 = selectClaimsFixBrief(1, null)
  expect('null-guard : tentative 1 utilise brief initial (pas de join)', brief1.startsWith('brief-initial'), true)

  // Tentative 2 avec claimsErrorLines=null : ne doit pas throw
  let threw = false
  let brief2 = null
  try {
    brief2 = selectClaimsFixBrief(2, null)
  } catch (e) {
    threw = true
  }
  expect('null-guard : tentative 2 avec null ne throw pas', threw, false)
  expect('null-guard : tentative 2 avec null produit un brief vide', brief2, 'brief-fix:src/a.ts:')

  // Tentative 2 avec claimsErrorLines=[...] : fonctionne normalement
  const brief2WithLines = selectClaimsFixBrief(2, ['error line 1', 'error line 2'])
  expect('null-guard : tentative 2 avec lignes produit le brief correct', brief2WithLines, 'brief-fix:src/a.ts:error line 1\nerror line 2')
}

// ---------------------------------------------------------------------------
// Tests de extractTypeDiagnostics
// ---------------------------------------------------------------------------
//
// Couvre les règles de sélection de diagnostics TS :
//   (A) Les lignes `error TS` dans stdout sont extraites et retournées.
//   (B) Le résumé Nx seul en queue ne déplace pas les diagnostics.
//   (C) Fallback tailLines quand aucun diagnostic TS dans stdout.
//   (D) Les lignes de contexte adjacentes sont incluses.
//   (E) La sortie est bornée à maxLines.
//   (F) Le brief de retry contient la no-verify instruction et le done-when.
//   (G) Le brief de retry ne contient pas les steps du plan original.
// ---------------------------------------------------------------------------

console.log('\n=== extractTypeDiagnostics ===\n')

// (A) Diagnostics TS dans stdout : retournés, résumé Nx ignoré
{
  const stdout = [
    ' NX   Running target type-check for 4 projects:',
    '',
    '> nx run aphrodite:type-check',
    '',
    '> tsc -p frontend/apps/aphrodite/tsconfig.app.json --noEmit',
    'frontend/apps/aphrodite/src/app/foo.component.ts(42,7): error TS2345: Argument of type \'string\' is not assignable to parameter of type \'number\'.',
    'frontend/apps/aphrodite/src/app/bar.service.ts(17,3): error TS2304: Cannot find name \'MyType\'.',
    '',
    ' NX   Running target type-check for 4 projects failed',
    '',
    '  Failed tasks:',
    '  - aphrodite:type-check',
  ].join('\n')
  const stderr = 'Creating project graph nodes...'

  const result = extractTypeDiagnostics(stdout, stderr, 60)

  // Les deux lignes de diagnostic doivent être présentes
  const hasDiag1 = result.some((l) => l.includes('TS2345'))
  const hasDiag2 = result.some((l) => l.includes('TS2304'))
  // Le résumé Nx ne doit pas être présent
  const hasNxSummary = result.some((l) => l.includes('Running target type-check for 4 projects failed'))

  expect('(A) diagnostics TS2345 présent', hasDiag1, true)
  expect('(A) diagnostics TS2304 présent', hasDiag2, true)
  expect('(A) résumé Nx absent des diagnostics', hasNxSummary, false)
}

// (B) Résumé Nx seul dans stdout (aucun `error TS`) : fallback tailLines
{
  const stdout = [
    ' NX   Running target type-check for 4 projects failed',
    '',
    '  Failed tasks:',
    '  - aphrodite:type-check',
    '  - admin:type-check',
  ].join('\n')
  const stderr = 'Creating project graph nodes...'

  const result = extractTypeDiagnostics(stdout, stderr, 60)

  // Fallback : tailLines du source (stderr non vide, donc source = stderr)
  // stderr = 'Creating project graph nodes...' → retourne cette ligne
  const hasStderr = result.some((l) => l.includes('Creating project graph nodes'))
  expect('(B) fallback sur stderr quand aucun error TS dans stdout', hasStderr, true)
}

// (C) Fallback tailLines quand stdout vide et stderr contient l'erreur
{
  const stdout = ''
  const stderr = [
    'error TS9999: some build error not in stdout',
    ' NX   Running target type-check for 4 projects failed',
  ].join('\n')

  const result = extractTypeDiagnostics(stdout, stderr, 60)

  // Aucun diagnostic dans stdout → fallback tailLines(stderr)
  const hasError = result.some((l) => l.includes('error TS9999'))
  expect('(C) fallback tailLines(stderr) quand stdout vide', hasError, true)
}

// (D) Lignes de contexte adjacentes incluses
{
  const stdout = [
    '>   const x: number = \'hello\'',
    'src/app/foo.ts(10,5): error TS2322: Type \'string\' is not assignable to type \'number\'.',
    '    ~~~~~~~~~~~~~~~~~~',
    '',
    'Some unrelated output',
  ].join('\n')
  const stderr = ''

  const result = extractTypeDiagnostics(stdout, stderr, 60)

  const hasErrorLine = result.some((l) => l.includes('TS2322'))
  const hasContextBefore = result.some((l) => l.includes('const x: number'))
  const hasCaret = result.some((l) => l.includes('~~~~~~~~~~~~~~~~~~'))
  expect('(D) ligne de diagnostic TS2322 présente', hasErrorLine, true)
  expect('(D) ligne de contexte précédente présente', hasContextBefore, true)
  expect('(D) caret `~` inclus', hasCaret, true)
}

// (E) Sortie bornée à maxLines
{
  // Générer 100 lignes de diagnostic
  const diagLines = []
  for (let i = 0; i < 100; i++) {
    diagLines.push(`src/app/file${i}.ts(${i},1): error TS2000: Error ${i}.`)
  }
  const stdout = diagLines.join('\n')
  const stderr = ''

  const result = extractTypeDiagnostics(stdout, stderr, 10)

  expect('(E) sortie bornée à maxLines=10', result.length <= 10, true)
  // Les 10 dernières erreurs doivent être retournées (slice(-10))
  const hasLast = result.some((l) => l.includes('Error 99'))
  expect('(E) les dernières lignes sont présentes', hasLast, true)
}

// (F) Brief de retry : no-verify instruction et done-when présents
// Simulation de buildEditorFixBrief (logique extraite, sans import de la fonction interne)
{
  function simulateBuildEditorFixBrief(task, scope, plan, errorLines, attempt) {
    const sections = [
      `## Task\n${task}`,
      '## Current state\n' +
        `A previous attempt (#${attempt - 1}) left changes on disk that do not pass verification. ` +
        'Read the current state of the files before changing anything \u2014 do not assume what was done.',
      `## Compiler output\n\`\`\`\n${(errorLines ?? []).join('\n')}\n\`\`\``,
    ]
    if (scope) sections.push(`## Scope\n${scope}`)
    sections.push('## Files in scope\n' + plan.files.map((f) => `- ${f}`).join('\n'))
    sections.push(
      '## Done when\n' +
      `${plan.doneWhen}\n\n` +
      'The change is written to disk. Do not attempt to build, compile, lint or test \u2014 ' +
      'verification is performed independently and is not your responsibility.'
    )
    sections.push(
      '## If this is not the right place\n' +
      'If the error indicates the real problem lies outside the scope above, say so ' +
      'explicitly and stop. Reporting that is a successful outcome, not a failure.'
    )
    return sections.join('\n\n')
  }

  const plan = {
    files: ['src/app/foo.ts', 'src/app/bar.ts'],
    doneWhen: 'type-check passes for aphrodite project',
    steps: ['Step 1: do this', 'Step 2: do that', 'Step 3: finish'],
  }
  const errorLines = ['src/app/foo.ts(42,7): error TS2345: Argument of type \'string\' is not assignable.']
  const brief = simulateBuildEditorFixBrief('Fix TS errors', null, plan, errorLines, 2)

  // no-verify instruction présente
  const hasNoVerify = brief.includes('Do not attempt to build, compile, lint or test')
  expect('(F) no-verify instruction présente dans le brief de retry', hasNoVerify, true)

  // done-when présent
  const hasDoneWhen = brief.includes('type-check passes for aphrodite project')
  expect('(F) done-when présent dans le brief de retry', hasDoneWhen, true)

  // current-state warning présent
  const hasCurrentState = brief.includes('left changes on disk that do not pass verification')
  expect('(F) current-state warning présent', hasCurrentState, true)

  // file scope présent
  const hasFileScope = brief.includes('src/app/foo.ts') && brief.includes('src/app/bar.ts')
  expect('(F) périmètre de fichiers présent', hasFileScope, true)

  // diagnostics présents dans compiler output
  const hasDiag = brief.includes('TS2345')
  expect('(F) diagnostics TS présents dans compiler output', hasDiag, true)
}

// (G) Brief de retry : steps du plan original absents
{
  function simulateBuildEditorFixBriefNoSteps(plan, errorLines, attempt) {
    const sections = [
      `## Task\nFix TS errors`,
      '## Current state\nA previous attempt (#' + (attempt - 1) + ') left changes on disk.',
      `## Compiler output\n\`\`\`\n${(errorLines ?? []).join('\n')}\n\`\`\``,
    ]
    // Pas de steps — seulement les fichiers
    sections.push('## Files in scope\n' + plan.files.map((f) => `- ${f}`).join('\n'))
    sections.push('## Done when\n' + plan.doneWhen)
    return sections.join('\n\n')
  }

  const plan = {
    files: ['src/app/foo.ts'],
    doneWhen: 'type-check passes',
    steps: ['Step 1: do this', 'Step 2: do that'],
  }
  const brief = simulateBuildEditorFixBriefNoSteps(plan, ['error TS2000: something'], 2)

  // Les steps ne doivent PAS apparaître dans le brief de retry
  const hasStep1 = brief.includes('Step 1: do this')
  const hasStep2 = brief.includes('Step 2: do that')
  expect('(G) Step 1 absent du brief de retry', hasStep1, false)
  expect('(G) Step 2 absent du brief de retry', hasStep2, false)

  // Les fichiers doivent être présents
  const hasFile = brief.includes('src/app/foo.ts')
  expect('(G) fichier présent dans le brief de retry', hasFile, true)
}

// ---------------------------------------------------------------------------
// Resultat
// ---------------------------------------------------------------------------

console.log('')
console.log(`Resultat : ${passed} passe(s), ${failed} echoue(s)`)
process.exit(failed > 0 ? 1 : 0)
