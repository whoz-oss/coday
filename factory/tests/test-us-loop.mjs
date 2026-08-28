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
// Resultat
// ---------------------------------------------------------------------------

console.log('')
console.log(`Resultat : ${passed} passe(s), ${failed} echoue(s)`)
process.exit(failed > 0 ? 1 : 0)
