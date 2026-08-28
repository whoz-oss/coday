/**
 * Tests statiques de la table de dispatch de run.mjs.
 *
 * Vérifie :
 *   - Les commandes catégorisées canoniques résolvent vers le bon module.
 *   - Les alias hérités résolvent vers la bonne cible.
 *   - Les alias de diagnostic portent le flag `deprecated`.
 *   - Les alias de workflow ne portent PAS le flag `deprecated`.
 *   - Les modules cibles existent sur le système de fichiers.
 *   - Les commandes inconnues ne résolvent pas.
 *
 * Aucun workflow ni diagnostic n'est exécuté. Aucun appel réseau.
 *
 * Usage : node factory/tests/test-run-dispatch.mjs
 * Code de sortie : 0 = tous les cas passent, 1 = au moins un échec.
 */

import { existsSync } from 'node:fs'
import { join, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

const __dirname = dirname(fileURLToPath(import.meta.url))

// ---------------------------------------------------------------------------
// Reproduction de la table de dispatch de run.mjs (logique pure)
// On ne peut pas importer run.mjs directement (il appelle main() en top-level).
// On reproduit ici uniquement la table, pas la logique d'exécution.
// ---------------------------------------------------------------------------

const DISPATCH = {
  'workflow:us-loop':                { category: 'workflow',   path: join(__dirname, '..', 'workflows', 'us-loop.mjs') },
  'workflow:fix-loop':               { category: 'workflow',   path: join(__dirname, '..', 'workflows', 'fix-loop.mjs') },
  'diagnostic:agentos-smoke':        { category: 'diagnostic', path: join(__dirname, '..', 'diagnostics', 'agentos-smoke.mjs') },
  'diagnostic:backend-oracle-check': { category: 'diagnostic', path: join(__dirname, '..', 'diagnostics', 'backend-oracle-check.mjs') },

  'alias:smoke':        { aliasFor: 'diagnostic:agentos-smoke',        deprecated: true },
  'alias:verify-back':  { aliasFor: 'diagnostic:backend-oracle-check', deprecated: true },
  'alias:us-loop':      { aliasFor: 'workflow:us-loop' },
  'alias:fix-loop':     { aliasFor: 'workflow:fix-loop' },
}

/**
 * Résout une clé de dispatch en suivant les alias.
 * Retourne l'entrée finale (avec `path` et `category`) ou null.
 */
function resolve(key) {
  const entry = DISPATCH[key]
  if (!entry) return null
  if (entry.aliasFor) return DISPATCH[entry.aliasFor] ?? null
  return entry
}

/**
 * Simule l'analyse des arguments CLI de run.mjs.
 * Retourne la clé de dispatch correspondante.
 */
function argvToKey(argv2, argv3) {
  if (argv2 === 'workflow' || argv2 === 'diagnostic') {
    if (!argv3) return null
    return `${argv2}:${argv3}`
  }
  return `alias:${argv2}`
}

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
  return expect(name, value, true)
}

// ---------------------------------------------------------------------------
// 1. Commandes catégorisées canoniques
// ---------------------------------------------------------------------------

console.log('\n=== Commandes catégorisées canoniques ===\n')

{
  const key = argvToKey('workflow', 'us-loop')
  const entry = resolve(key)
  expect('workflow us-loop : clé correcte', key, 'workflow:us-loop')
  expect('workflow us-loop : category = workflow', entry?.category, 'workflow')
  expectTrue('workflow us-loop : module existe', existsSync(entry?.path ?? ''))
}

{
  const key = argvToKey('workflow', 'fix-loop')
  const entry = resolve(key)
  expect('workflow fix-loop : clé correcte', key, 'workflow:fix-loop')
  expect('workflow fix-loop : category = workflow', entry?.category, 'workflow')
  expectTrue('workflow fix-loop : module existe', existsSync(entry?.path ?? ''))
}

{
  const key = argvToKey('diagnostic', 'agentos-smoke')
  const entry = resolve(key)
  expect('diagnostic agentos-smoke : clé correcte', key, 'diagnostic:agentos-smoke')
  expect('diagnostic agentos-smoke : category = diagnostic', entry?.category, 'diagnostic')
  expectTrue('diagnostic agentos-smoke : module existe', existsSync(entry?.path ?? ''))
}

{
  const key = argvToKey('diagnostic', 'backend-oracle-check')
  const entry = resolve(key)
  expect('diagnostic backend-oracle-check : clé correcte', key, 'diagnostic:backend-oracle-check')
  expect('diagnostic backend-oracle-check : category = diagnostic', entry?.category, 'diagnostic')
  expectTrue('diagnostic backend-oracle-check : module existe', existsSync(entry?.path ?? ''))
}

// ---------------------------------------------------------------------------
// 2. Alias hérités — diagnostics (avec déprécation)
// ---------------------------------------------------------------------------

console.log('\n=== Alias hérités — diagnostics ===\n')

{
  const key = argvToKey('smoke')
  const rawEntry = DISPATCH[key]
  const resolved = resolve(key)
  expect('alias smoke : clé correcte', key, 'alias:smoke')
  expect('alias smoke : aliasFor = diagnostic:agentos-smoke', rawEntry?.aliasFor, 'diagnostic:agentos-smoke')
  expect('alias smoke : deprecated = true', rawEntry?.deprecated, true)
  expect('alias smoke : résout vers category = diagnostic', resolved?.category, 'diagnostic')
  expectTrue('alias smoke : module cible existe', existsSync(resolved?.path ?? ''))
}

{
  const key = argvToKey('verify-back')
  const rawEntry = DISPATCH[key]
  const resolved = resolve(key)
  expect('alias verify-back : clé correcte', key, 'alias:verify-back')
  expect('alias verify-back : aliasFor = diagnostic:backend-oracle-check', rawEntry?.aliasFor, 'diagnostic:backend-oracle-check')
  expect('alias verify-back : deprecated = true', rawEntry?.deprecated, true)
  expect('alias verify-back : résout vers category = diagnostic', resolved?.category, 'diagnostic')
  expectTrue('alias verify-back : module cible existe', existsSync(resolved?.path ?? ''))
}

// ---------------------------------------------------------------------------
// 3. Alias hérités — workflows (sans déprécation)
// ---------------------------------------------------------------------------

console.log('\n=== Alias hérités — workflows ===\n')

{
  const key = argvToKey('us-loop')
  const rawEntry = DISPATCH[key]
  const resolved = resolve(key)
  expect('alias us-loop : clé correcte', key, 'alias:us-loop')
  expect('alias us-loop : aliasFor = workflow:us-loop', rawEntry?.aliasFor, 'workflow:us-loop')
  expect('alias us-loop : pas de flag deprecated', rawEntry?.deprecated, undefined)
  expect('alias us-loop : résout vers category = workflow', resolved?.category, 'workflow')
  expectTrue('alias us-loop : module cible existe', existsSync(resolved?.path ?? ''))
}

{
  const key = argvToKey('fix-loop')
  const rawEntry = DISPATCH[key]
  const resolved = resolve(key)
  expect('alias fix-loop : clé correcte', key, 'alias:fix-loop')
  expect('alias fix-loop : aliasFor = workflow:fix-loop', rawEntry?.aliasFor, 'workflow:fix-loop')
  expect('alias fix-loop : pas de flag deprecated', rawEntry?.deprecated, undefined)
  expect('alias fix-loop : résout vers category = workflow', resolved?.category, 'workflow')
  expectTrue('alias fix-loop : module cible existe', existsSync(resolved?.path ?? ''))
}

// ---------------------------------------------------------------------------
// 4. Commandes inconnues
// ---------------------------------------------------------------------------

console.log('\n=== Commandes inconnues ===\n')

{
  const key = argvToKey('workflow', 'unknown-workflow')
  const entry = resolve(key)
  expect('workflow inconnu : résolution = null', entry, null)
}

{
  const key = argvToKey('diagnostic', 'unknown-diag')
  const entry = resolve(key)
  expect('diagnostic inconnu : résolution = null', entry, null)
}

{
  const key = argvToKey('random-alias')
  const entry = resolve(key)
  expect('alias inconnu : résolution = null', entry, null)
}

// ---------------------------------------------------------------------------
// 5. Les anciens fichiers workflows/ sont bien présents (workflows de production)
// ---------------------------------------------------------------------------

console.log('\n=== Fichiers de workflows de production ===\n')

{
  const p = join(__dirname, '..', 'workflows', 'us-loop.mjs')
  expectTrue('workflows/us-loop.mjs existe', existsSync(p))
}

{
  const p = join(__dirname, '..', 'workflows', 'fix-loop.mjs')
  expectTrue('workflows/fix-loop.mjs existe', existsSync(p))
}

// ---------------------------------------------------------------------------
// 6. Les fichiers de diagnostics sont dans diagnostics/ et non dans workflows/
// ---------------------------------------------------------------------------

console.log('\n=== Emplacement des diagnostics ===\n')

{
  const inDiag = join(__dirname, '..', 'diagnostics', 'agentos-smoke.mjs')
  const inWf = join(__dirname, '..', 'workflows', 'agentos-smoke.mjs')
  expectTrue('agentos-smoke.mjs est dans diagnostics/', existsSync(inDiag))
  expect('agentos-smoke.mjs n\'est PAS dans workflows/', existsSync(inWf), false)
}

{
  const inDiag = join(__dirname, '..', 'diagnostics', 'backend-oracle-check.mjs')
  const inWf = join(__dirname, '..', 'workflows', 'backend-oracle-check.mjs')
  expectTrue('backend-oracle-check.mjs est dans diagnostics/', existsSync(inDiag))
  expect('backend-oracle-check.mjs n\'est PAS dans workflows/', existsSync(inWf), false)
}

// ---------------------------------------------------------------------------
// 7. Les anciens fichiers smoke.mjs et verify-back.mjs ne sont plus dans workflows/
// ---------------------------------------------------------------------------

console.log('\n=== Anciens fichiers workflows/ supprimés ===\n')

{
  const p = join(__dirname, '..', 'workflows', 'smoke.mjs')
  expect('workflows/smoke.mjs n\'existe plus', existsSync(p), false)
}

{
  const p = join(__dirname, '..', 'workflows', 'verify-back.mjs')
  expect('workflows/verify-back.mjs n\'existe plus', existsSync(p), false)
}

// ---------------------------------------------------------------------------
// Résultat
// ---------------------------------------------------------------------------

console.log('')
console.log(`Résultat : ${passed} passé(s), ${failed} échoué(s)`)
process.exit(failed > 0 ? 1 : 0)
