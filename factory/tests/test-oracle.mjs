/**
 * Tests unitaires pour factory/lib/oracle.mjs — fonction countTaskOutcomes.
 *
 * Couvre les cas obligatoires initiaux (C1.d) plus les cas A9/F32 :
 *   1. Sortie Nx avec cache (capturée réelle)
 *   2. Sortie Nx sans cache (capturée réelle, après pnpm nx reset)
 *   3. Sortie Gradle fabriquée à la main
 *   4. Sortie vide
 *   5. Sortie Nx colorée (séquences ANSI)
 *   6. (A9/F32) Sortie Nx fraîche multi-projets — fixture capturée réelle
 *      lors du run WZ-27053 (type-check 4 projets, pnpm nx reset préalable).
 *      summaryFound doit être true grâce à la ligne de succès Nx.
 *   7. (A9/F32) Sortie Nx fraîche + cachée mixte — les deux lignes de synthèse
 *      sont présentes. summaryFound=true, countMismatch=false si cohérent.
 *   8. (A9/F32) Désaccord sur le total via ligne de succès seule.
 *   9. (A9/F32) summaryAbsenceReason='no-nx-tasks' sur sortie Gradle.
 *  10. (A9/F32) summaryAbsenceReason='fresh-run' sur sortie Nx fraîche sans
 *      ligne de succès (format inattendu).
 *  11. (A9/F32) Variante singulier : "for 1 project".
 *
 * Usage : node factory/tests/test-oracle.mjs
 * Code de sortie : 0 = tous les cas passent, 1 = au moins un échec.
 */

import { countTaskOutcomes } from '../lib/oracle.mjs'

// ---------------------------------------------------------------------------
// Runner minimal — même mécanique que test-us-loop.mjs
// ---------------------------------------------------------------------------

let passed = 0
let failed = 0

/**
 * Vérifie qu'une valeur est égale à la valeur attendue (comparaison JSON).
 *
 * @param {string} name
 * @param {unknown} actual
 * @param {unknown} expected
 */
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

/**
 * Vérifie qu'une valeur numérique est >= au seuil donné.
 *
 * @param {string} name
 * @param {number} actual
 * @param {number} min
 */
function expectAtLeast(name, actual, min) {
  const ok = actual >= min
  const icon = ok ? '\u2713' : '\u2717'
  console.log(`${icon} ${name}`)
  if (!ok) {
    console.log(`  attendu : >= ${min}`)
    console.log(`  obtenu  : ${actual}`)
  }
  if (ok) passed++
  else failed++
}

// ---------------------------------------------------------------------------
// Cas 1 — Sortie Nx avec cache (capturée réelle)
//
// Obtenue avec : pnpm nx run-many -t lint -p client 2>&1 | cat
// (seconde exécution, cache chaud)
//
// Points clés :
//   - Le marqueur [existing outputs match the cache, left as is] est sur la
//     MÊMe ligne que > nx run client:lint (pas sur la suivante).
//   - La ligne de synthèse cache est présente : summaryFound doit être true.
//   - La ligne de succès est aussi présente : les deux cross-checks s'accordent.
// ---------------------------------------------------------------------------

console.log('\n=== Cas 1 : sortie Nx avec cache (capturée réelle) ===\n')

{
  const output = ` NX   Running target lint for project client:\n\n- client\n\n\n> nx run client:lint  [existing outputs match the cache, left as is]\n\n> eslint .\n\n\n\n NX   Successfully ran target lint for 1 project\n\nNx read the output from the cache instead of running the command for 1 out of 1 tasks.\n`

  const r = countTaskOutcomes(output)

  expect('Cas 1 : fromCache = 1', r.fromCache, 1)
  expect('Cas 1 : executed = 0', r.executed, 0)
  expect('Cas 1 : upToDate = 0', r.upToDate, 0)
  expect('Cas 1 : skipped = 0', r.skipped, 0)
  expect('Cas 1 : summaryFound = true', r.summaryFound, true)
  expect('Cas 1 : summaryAbsenceReason = null', r.summaryAbsenceReason, null)
  expect('Cas 1 : summaryFromCache = 1', r.summaryFromCache, 1)
  expect('Cas 1 : summaryTotal = 1', r.summaryTotal, 1)
  expect('Cas 1 : countMismatch = false', r.countMismatch, false)
}

// ---------------------------------------------------------------------------
// Cas 2 — Sortie Nx sans cache (capturée réelle)
//
// Obtenue avec : pnpm nx reset && pnpm nx run-many -t lint -p client 2>&1 | cat
//
// Observations :
//   - Aucun marqueur [existing outputs match the cache] sur la ligne > nx run.
//   - La ligne de synthèse cache "Nx read the output from the cache..." est ABSENTE.
//   - La ligne de succès " NX   Successfully ran target lint for 1 project" est
//     présente — summaryFound doit valoir true (A9/F32).
//   - summaryFromCache est null (pas de cache summary), summaryTotal = 1.
//   - L'absence de ligne cache ne doit PAS déclencher countMismatch.
// ---------------------------------------------------------------------------

console.log('\n=== Cas 2 : sortie Nx sans cache (capturée réelle) ===\n')

{
  const output = ` NX   Running target lint for project client:\n\n- client\n\n\n> nx run client:lint\n\n> eslint .\n\n\n\n NX   Successfully ran target lint for 1 project\n\n`

  const r = countTaskOutcomes(output)

  expectAtLeast('Cas 2 : executed >= 1', r.executed, 1)
  expect('Cas 2 : fromCache = 0', r.fromCache, 0)
  // A9/F32 : summaryFound est maintenant true grâce à la ligne de succès
  expect('Cas 2 : summaryFound = true (ligne de succès Nx)', r.summaryFound, true)
  expect('Cas 2 : summaryAbsenceReason = null', r.summaryAbsenceReason, null)
  // summaryFromCache est null car la ligne cache est absente
  expect('Cas 2 : summaryFromCache = null', r.summaryFromCache, null)
  // summaryTotal vient de la ligne de succès
  expect('Cas 2 : summaryTotal = 1 (ligne de succès)', r.summaryTotal, 1)
  // Critique : l'absence de ligne cache ne doit PAS être un désaccord.
  expect('Cas 2 : countMismatch = false (absence cache ≠ désaccord)', r.countMismatch, false)
}

// ---------------------------------------------------------------------------
// Cas 3 — Sortie Gradle fabriquée
//
// ATTENTION : cette sortie est fabriquée à la main, pas capturée.
// Elle reproduit le format `> Task :chemin:nom MARQUEUR` tel qu'il apparaît
// dans une sortie Gradle --console=plain réelle, mais les tâches et chemins
// sont fictifs. Elle couvre les quatre marqueurs : UP-TO-DATE, FROM-CACHE,
// SKIPPED (via NO-SOURCE), et exécutée (sans marqueur).
// ---------------------------------------------------------------------------

console.log('\n=== Cas 3 : sortie Gradle fabriquée ===\n')

{
  const output = `
> Task :libs:model:compileTypeScript UP-TO-DATE
> Task :libs:model:lint UP-TO-DATE
> Task :apps:server:compileTypeScript FROM-CACHE
> Task :apps:client:generateStyles NO-SOURCE
> Task :apps:client:compileTypeScript
> Task :apps:client:lint

BUILD SUCCESSFUL in 3s
`

  const r = countTaskOutcomes(output)

  expect('Cas 3 : upToDate = 2', r.upToDate, 2)
  expect('Cas 3 : fromCache = 1', r.fromCache, 1)
  expect('Cas 3 : skipped = 1', r.skipped, 1)
  expect('Cas 3 : executed = 2', r.executed, 2)
  // La ligne de synthèse est spécifique à Nx — absente dans Gradle.
  expect('Cas 3 : summaryFound = false', r.summaryFound, false)
  // Raison : aucune ligne `> nx run` vue (Gradle, pas Nx)
  expect('Cas 3 : summaryAbsenceReason = no-nx-tasks', r.summaryAbsenceReason, 'no-nx-tasks')
  expect('Cas 3 : countMismatch = false', r.countMismatch, false)
}

// ---------------------------------------------------------------------------
// Cas 4 — Sortie vide
// ---------------------------------------------------------------------------

console.log('\n=== Cas 4 : sortie vide ===\n')

{
  const r = countTaskOutcomes('')

  expect('Cas 4 : upToDate = 0', r.upToDate, 0)
  expect('Cas 4 : fromCache = 0', r.fromCache, 0)
  expect('Cas 4 : skipped = 0', r.skipped, 0)
  expect('Cas 4 : executed = 0', r.executed, 0)
  expect('Cas 4 : summaryFound = false', r.summaryFound, false)
  expect('Cas 4 : summaryAbsenceReason = no-nx-tasks', r.summaryAbsenceReason, 'no-nx-tasks')
  expect('Cas 4 : summaryFromCache = null', r.summaryFromCache, null)
  expect('Cas 4 : summaryTotal = null', r.summaryTotal, null)
  expect('Cas 4 : countMismatch = false', r.countMismatch, false)
}

// ---------------------------------------------------------------------------
// Cas 5 — Sortie Nx colorée (séquences ANSI)
//
// Nx colore sa sortie même redirigée dans certaines configurations.
// Les séquences ANSI doivent être retirées avant analyse.
// Ce cas vérifie que le résultat est identique à la version décolorée.
// ---------------------------------------------------------------------------

console.log('\n=== Cas 5 : sortie Nx colorée (séquences ANSI) ===\n')

{
  // \u001b[1m = gras, \u001b[32m = vert, \u001b[0m = reset
  const output =
    `\u001b[1m\u001b[32m NX \u001b[0m   Running target lint for project client:\n` +
    `\n- client\n\n\n` +
    `> \u001b[1mnx run\u001b[0m client:lint  \u001b[33m[existing outputs match the cache, left as is]\u001b[0m\n` +
    `\n> eslint .\n\n\n` +
    `\u001b[1m\u001b[32m NX \u001b[0m   Successfully ran target lint for 1 project\n` +
    `\nNx read the output from the cache instead of running the command for 1 out of 1 tasks.\n`

  const r = countTaskOutcomes(output)

  expect('Cas 5 : fromCache = 1 (ANSI retiré)', r.fromCache, 1)
  expect('Cas 5 : executed = 0 (ANSI retiré)', r.executed, 0)
  expect('Cas 5 : summaryFound = true (ANSI retiré)', r.summaryFound, true)
  expect('Cas 5 : summaryAbsenceReason = null', r.summaryAbsenceReason, null)
  expect('Cas 5 : summaryFromCache = 1', r.summaryFromCache, 1)
  expect('Cas 5 : summaryTotal = 1', r.summaryTotal, 1)
  expect('Cas 5 : countMismatch = false', r.countMismatch, false)
}

// ---------------------------------------------------------------------------
// Cas 6 — (A9/F32) Fixture réelle : type-check 4 projets fraîche
//
// Capturée avec capture-oracle-output.mjs après pnpm nx reset.
// C'est exactement ce que le run WZ-27053 a produit (verify-types-1-1).
// summaryFound était false avant A9/F32 ; doit être true après.
// ---------------------------------------------------------------------------

console.log('\n=== Cas 6 : (A9/F32) fixture réelle type-check 4 projets fraîche ===\n')

{
  // Sortie exacte capturée (stdout len=553, stderr="Creating project graph nodes...")
  const output = ` NX   Running target type-check for 4 projects:\n\n- aphrodite\n- admin\n- agentic-studio\n- copilot-chat\n\n\n\n> nx run aphrodite:type-check\n\n> tsc -p frontend/apps/aphrodite/tsconfig.app.json --noEmit\n\n\n> nx run agentic-studio:type-check\n\n> tsc -p frontend/apps/agentic-studio/tsconfig.app.json --noEmit\n\n\n> nx run copilot-chat:type-check\n\n> tsc -p frontend/apps/copilot-chat/tsconfig.app.json --noEmit\n\n\n> nx run admin:type-check\n\n> tsc -p frontend/apps/admin/tsconfig.app.json --noEmit\n\n\n\n NX   Successfully ran target type-check for 4 projects\n\n\n`

  const r = countTaskOutcomes(output)

  expect('Cas 6 : executed = 4', r.executed, 4)
  expect('Cas 6 : fromCache = 0', r.fromCache, 0)
  // A9/F32 : summaryFound est maintenant true (ligne de succès présente)
  expect('Cas 6 : summaryFound = true (A9/F32)', r.summaryFound, true)
  expect('Cas 6 : summaryAbsenceReason = null', r.summaryAbsenceReason, null)
  // summaryFromCache est null (pas de cache summary)
  expect('Cas 6 : summaryFromCache = null', r.summaryFromCache, null)
  // summaryTotal vient de la ligne de succès
  expect('Cas 6 : summaryTotal = 4 (ligne de succès)', r.summaryTotal, 4)
  // 4 exécutées, 4 dans la ligne de succès — pas de désaccord
  expect('Cas 6 : countMismatch = false', r.countMismatch, false)
}

// ---------------------------------------------------------------------------
// Cas 7 — (A9/F32) Sortie mixte : 2 fraîches + 2 cachées
//
// Les deux lignes de synthèse sont présentes.
// summaryFromCache vient de la ligne cache (2), summaryTotal de la ligne cache (4).
// La ligne de succès doit être cohérente (4 projets).
// ---------------------------------------------------------------------------

console.log('\n=== Cas 7 : (A9/F32) sortie mixte fraîche + cachée ===\n')

{
  const output = ` NX   Running target type-check for 4 projects:\n\n- aphrodite\n- admin\n- agentic-studio\n- copilot-chat\n\n\n\n> nx run aphrodite:type-check\n\n> tsc -p frontend/apps/aphrodite/tsconfig.app.json --noEmit\n\n\n> nx run agentic-studio:type-check\n\n> tsc -p frontend/apps/agentic-studio/tsconfig.app.json --noEmit\n\n\n> nx run copilot-chat:type-check  [existing outputs match the cache, left as is]\n\n> tsc -p frontend/apps/copilot-chat/tsconfig.app.json --noEmit\n\n\n> nx run admin:type-check  [existing outputs match the cache, left as is]\n\n> tsc -p frontend/apps/admin/tsconfig.app.json --noEmit\n\n\n\n NX   Successfully ran target type-check for 4 projects\n\nNx read the output from the cache instead of running the command for 2 out of 4 tasks.\n`

  const r = countTaskOutcomes(output)

  expect('Cas 7 : executed = 2', r.executed, 2)
  expect('Cas 7 : fromCache = 2', r.fromCache, 2)
  expect('Cas 7 : summaryFound = true', r.summaryFound, true)
  expect('Cas 7 : summaryAbsenceReason = null', r.summaryAbsenceReason, null)
  expect('Cas 7 : summaryFromCache = 2', r.summaryFromCache, 2)
  expect('Cas 7 : summaryTotal = 4', r.summaryTotal, 4)
  expect('Cas 7 : countMismatch = false', r.countMismatch, false)
}

// ---------------------------------------------------------------------------
// Cas 8 — (A9/F32) Désaccord via ligne de succès seule
//
// La ligne de succès dit 3 projets, mais on a compté 4 lignes `> nx run`.
// countMismatch doit être true.
// ---------------------------------------------------------------------------

console.log('\n=== Cas 8 : (A9/F32) désaccord via ligne de succès ===\n')

{
  const output = ` NX   Running target type-check for 4 projects:\n\n> nx run aphrodite:type-check\n> nx run admin:type-check\n> nx run agentic-studio:type-check\n> nx run copilot-chat:type-check\n\n NX   Successfully ran target type-check for 3 projects\n\n`

  const r = countTaskOutcomes(output)

  expect('Cas 8 : executed = 4', r.executed, 4)
  expect('Cas 8 : summaryFound = true', r.summaryFound, true)
  expect('Cas 8 : summaryTotal = 3 (ligne de succès)', r.summaryTotal, 3)
  // 4 lignes comptées vs 3 dans la ligne de succès — désaccord
  expect('Cas 8 : countMismatch = true', r.countMismatch, true)
}

// ---------------------------------------------------------------------------
// Cas 9 — (A9/F32) summaryAbsenceReason='no-nx-tasks' sur sortie Gradle
//
// Aucune ligne `> nx run` : la raison d'absence doit être 'no-nx-tasks'.
// C'est le cas normal Gradle — pas une anomalie.
// ---------------------------------------------------------------------------

console.log('\n=== Cas 9 : (A9/F32) summaryAbsenceReason no-nx-tasks (Gradle) ===\n')

{
  const output = `
> Task :libs:model:compileKotlin
> Task :apps:server:test

BUILD SUCCESSFUL in 5s
`

  const r = countTaskOutcomes(output)

  expect('Cas 9 : summaryFound = false', r.summaryFound, false)
  expect('Cas 9 : summaryAbsenceReason = no-nx-tasks', r.summaryAbsenceReason, 'no-nx-tasks')
  expect('Cas 9 : countMismatch = false', r.countMismatch, false)
}

// ---------------------------------------------------------------------------
// Cas 10 — (A9/F32) summaryAbsenceReason='fresh-run' : lignes nx run présentes
//          mais aucune ligne de succès Nx (format inattendu)
//
// Ce cas ne devrait pas se produire avec Nx actuel, mais le code doit le
// signaler clairement si le format change.
// ---------------------------------------------------------------------------

console.log('\n=== Cas 10 : (A9/F32) summaryAbsenceReason fresh-run (format inattendu) ===\n')

{
  // Lignes `> nx run` présentes, mais pas de ligne de succès
  const output = `> nx run aphrodite:type-check\n> tsc -p tsconfig.json --noEmit\n\nSome unexpected output without the NX success line.\n`

  const r = countTaskOutcomes(output)

  expect('Cas 10 : executed = 1', r.executed, 1)
  expect('Cas 10 : summaryFound = false', r.summaryFound, false)
  expect('Cas 10 : summaryAbsenceReason = fresh-run', r.summaryAbsenceReason, 'fresh-run')
  expect('Cas 10 : countMismatch = false', r.countMismatch, false)
}

// ---------------------------------------------------------------------------
// Cas 11 — (A9/F32) Variante singulier : "for 1 project"
//
// La regex doit accepter "project" (singulier) et "projects" (pluriel).
// Vérifié sur sortie réelle : Nx émet "for 1 project" au singulier.
// ---------------------------------------------------------------------------

console.log('\n=== Cas 11 : (A9/F32) variante singulier "for 1 project" ===\n')

{
  const output = ` NX   Running target lint for project client:\n\n- client\n\n\n> nx run client:lint\n\n> eslint .\n\n\n\n NX   Successfully ran target lint for 1 project\n\n`

  const r = countTaskOutcomes(output)

  expect('Cas 11 : executed = 1', r.executed, 1)
  expect('Cas 11 : summaryFound = true (singulier)', r.summaryFound, true)
  expect('Cas 11 : summaryAbsenceReason = null', r.summaryAbsenceReason, null)
  expect('Cas 11 : summaryTotal = 1', r.summaryTotal, 1)
  expect('Cas 11 : countMismatch = false', r.countMismatch, false)
}

// ---------------------------------------------------------------------------
// Résultat
// ---------------------------------------------------------------------------

console.log('')
console.log(`Résultat : ${passed} passé(s), ${failed} échoué(s)`)
process.exit(failed > 0 ? 1 : 0)
