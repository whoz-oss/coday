#!/usr/bin/env node
/**
 * Point d'entrée de l'orchestrateur factory.
 *
 * Usage :
 *   node factory/run.mjs workflow <name>
 *   node factory/run.mjs diagnostic <name>
 *
 * Workflows (production) :
 *   node factory/run.mjs workflow us-loop
 *   node factory/run.mjs workflow fix-loop
 *
 * Diagnostics (vérification de plomberie, pas de livraison) :
 *   node factory/run.mjs diagnostic agentos-smoke
 *   node factory/run.mjs diagnostic backend-oracle-check
 *
 * Alias hérités (compatibilité temporaire) :
 *   node factory/run.mjs smoke          → diagnostic agentos-smoke
 *   node factory/run.mjs verify-back    → diagnostic backend-oracle-check
 *   node factory/run.mjs us-loop        → workflow us-loop
 *   node factory/run.mjs fix-loop       → workflow fix-loop
 *
 * ## Arrêt gracieux (A5/F24)
 *
 * Ce module enregistre le handler SIGTERM via `initShutdownHandler` (shutdown.mjs)
 * avant de lancer le workflow. Si SIGTERM arrive pendant un tour d'agent :
 *   1. Le case AgentOS actif est tué via l'API.
 *   2. `endRun` est écrit avec `status: 'fail'` et le fait durable
 *      `checkoutMayBeIntermediate: true`.
 *   3. Le processus quitte avec exit(1).
 *
 * En complétion normale, `markCompleted()` est appelé juste après le retour
 * du workflow — le handler SIGTERM ne peut plus appeler `endRun` après coup.
 * (Le workflow a déjà appelé `endRun` lui-même.)
 *
 * Le run courant est publié automatiquement par `createRun()` dans registry.mjs.
 * Le case actif est publié par `runAgentTurn()` dans active-case.mjs.
 * Le handler SIGTERM lit les deux via `getCurrentRun()` et `getActiveCaseId()`.
 */

import { join, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'
import { initShutdownHandler, markCompleted } from './lib/shutdown.mjs'

const __dirname = dirname(fileURLToPath(import.meta.url))

// --------------------------------------------------------------------------
// Table de dispatch
//
// Chaque entrée définit :
//   category  : 'workflow' | 'diagnostic'
//   name      : clé utilisée dans la commande catégorisée
//   path      : chemin du module à importer
//   deprecated: (optionnel) si vrai, l'alias affiche un avertissement de déprécation
// --------------------------------------------------------------------------

const DISPATCH = {
  // Commandes catégorisées (forme canonique)
  'workflow:us-loop':                 { category: 'workflow',    path: join(__dirname, 'workflows', 'us-loop.mjs') },
  'workflow:fix-loop':                { category: 'workflow',    path: join(__dirname, 'workflows', 'fix-loop.mjs') },
  'diagnostic:agentos-smoke':         { category: 'diagnostic',  path: join(__dirname, 'diagnostics', 'agentos-smoke.mjs') },
  'diagnostic:backend-oracle-check':  { category: 'diagnostic',  path: join(__dirname, 'diagnostics', 'backend-oracle-check.mjs') },

  // Alias hérités — diagnostics (avec avertissement)
  'alias:smoke':        { aliasFor: 'diagnostic:agentos-smoke',        deprecated: true },
  'alias:verify-back':  { aliasFor: 'diagnostic:backend-oracle-check', deprecated: true },

  // Alias hérités — workflows (silencieux)
  'alias:us-loop':   { aliasFor: 'workflow:us-loop' },
  'alias:fix-loop':  { aliasFor: 'workflow:fix-loop' },
}

/** Messages de déprécation par alias. */
const DEPRECATION_MESSAGES = {
  smoke:        'DEPRECATED: « node factory/run.mjs smoke » est remplacé par « node factory/run.mjs diagnostic agentos-smoke ».',
  'verify-back': 'DEPRECATED: « node factory/run.mjs verify-back » est remplacé par « node factory/run.mjs diagnostic backend-oracle-check ».',
}

// --------------------------------------------------------------------------
// Logger
// --------------------------------------------------------------------------

/** Formate un timestamp court pour la console. */
function ts() {
  return new Date().toISOString().replace('T', ' ').replace('Z', '')
}

/**
 * Logger passé aux workflows et diagnostics.
 */
const log = {
  phaseStart(name, kind) {
    console.log(`[${ts()}] \u25b6 phase ${kind.padEnd(5)} ${name}`)
  },
  phaseEnd(name, status, facts = {}) {
    const icon = status === 'pass' ? '\u2705' : '\u274c'
    const extra = Object.keys(facts).length
      ? '  ' + JSON.stringify(facts)
      : ''
    console.log(`[${ts()}] ${icon} phase ${name} \u2192 ${status}${extra}`)
  },
  info(msg) {
    console.log(`[${ts()}] \u2139\ufe0f  ${msg}`)
  },
  error(msg) {
    console.error(`[${ts()}] \u274c ${msg}`)
  },
}

// --------------------------------------------------------------------------
// Aide
// --------------------------------------------------------------------------

function printHelp() {
  console.error('Usage : node factory/run.mjs <catégorie> <nom>')
  console.error('')
  console.error('Workflows (livraison) :')
  console.error('  node factory/run.mjs workflow us-loop')
  console.error('  node factory/run.mjs workflow fix-loop')
  console.error('')
  console.error('Diagnostics (vérification de plomberie) :')
  console.error('  node factory/run.mjs diagnostic agentos-smoke')
  console.error('  node factory/run.mjs diagnostic backend-oracle-check')
}

// --------------------------------------------------------------------------
// Main
// --------------------------------------------------------------------------

async function main() {
  const arg1 = process.argv[2]
  const arg2 = process.argv[3]

  if (!arg1) {
    printHelp()
    process.exit(1)
  }

  let dispatchKey

  if (arg1 === 'workflow' || arg1 === 'diagnostic') {
    // Forme canonique catégorisée
    if (!arg2) {
      console.error(`Erreur : nom manquant après « ${arg1} ».`)
      printHelp()
      process.exit(1)
    }
    dispatchKey = `${arg1}:${arg2}`
  } else {
    // Alias hérité (un seul argument)
    dispatchKey = `alias:${arg1}`
  }

  const entry = DISPATCH[dispatchKey]

  if (!entry) {
    console.error(`Commande inconnue : « ${arg1}${arg2 ? ' ' + arg2 : ''} »`)
    printHelp()
    process.exit(1)
  }

  // Résoudre les alias
  let resolved = entry
  let aliasName = null
  if (resolved.aliasFor) {
    aliasName = arg1
    resolved = DISPATCH[resolved.aliasFor]
    if (!resolved) {
      console.error(`Erreur interne : alias « ${entry.aliasFor} » introuvable dans la table de dispatch.`)
      process.exit(1)
    }
  }

  // Avertissement de déprécation pour les anciens noms de diagnostic
  if (entry.deprecated && aliasName && DEPRECATION_MESSAGES[aliasName]) {
    console.warn(DEPRECATION_MESSAGES[aliasName])
  }

  const modulePath = resolved.path
  const category = resolved.category
  const displayName = arg2 ?? arg1

  let module
  try {
    module = await import(modulePath)
  } catch (err) {
    log.error(`Module introuvable ou invalide : « ${modulePath} »`)
    log.error(String(err))
    process.exit(1)
  }

  if (typeof module.run !== 'function') {
    log.error(`Le module « ${displayName} » n'exporte pas de fonction \`run\`.`)
    process.exit(1)
  }

  // Enregistrement du handler SIGTERM avant le lancement.
  initShutdownHandler({ log })

  log.info(`Démarrage du ${category} : ${displayName}`)

  let result
  try {
    result = await module.run(log)
  } catch (err) {
    log.error(`Erreur fatale dans le ${category} « ${displayName} » :`)
    log.error(String(err))
    process.exit(1)
  }

  markCompleted()

  const { allPass, filePath } = result ?? {}

  console.log('')
  console.log(`Registre : ${filePath}`)
  console.log(`Résultat : ${allPass ? 'PASS \u2705' : 'FAIL \u274c'}`)

  process.exit(allPass ? 0 : 1)
}

main()
