/**
 * Registre des cases AgentOS actuellement en cours de polling.
 *
 * Remplace l'ancien singleton par un registre multi-case, nécessaire pour
 * supporter l'éditeur séquentiel ET les reviewers parallèles simultanément.
 *
 * Module sans dépendance — utilisé par agentos.mjs (pour écrire)
 * et par shutdown.mjs (pour lire). Il existe pour briser la dépendance
 * circulaire qui résulterait de agentos → shutdown → agentos.
 *
 * ## Sémantique du registre
 *
 * - `registerActiveCase(caseId, label?)` : enregistre un case actif.
 *   Idempotent : enregistrer un caseId déjà présent est une no-op.
 * - `unregisterActiveCase(caseId)` : retire un case du registre.
 *   Idempotent : retirer un caseId absent est une no-op.
 * - `getActiveCaseIds()` : retourne un tableau snapshot (copie) des caseIds actifs.
 *
 * ## Compatibilité API legacy
 *
 * Les fonctions `setActiveCaseId`, `clearActiveCaseId` et `getActiveCaseId`
 * sont conservées pour la compatibilité avec agentos.mjs (runAgentTurn).
 * Elles délèguent vers register/unregister et retournent le premier case actif.
 *
 * ## Observabilité de test (FACTORY_ACTIVE_CASE_FILE)
 *
 * Si la variable d'environnement FACTORY_ACTIVE_CASE_FILE est définie,
 * registerActiveCase() écrit le caseId dans ce fichier et unregisterActiveCase()
 * le supprime quand le registre devient vide. Ce mécanisme est destiné
 * exclusivement aux tests d'intégration (test-shutdown-operational.sh).
 *
 * Aucun code de production ne lit ce fichier. Les erreurs d'I/O sur ce fichier
 * sont silencieusement ignorées — elles ne doivent jamais interrompre le workflow.
 */

import { writeFileSync, unlinkSync } from 'node:fs'

/** @type {Map<string, string>} caseId → label (ou caseId si pas de label) */
const _registry = new Map()

/** Chemin du fichier d'observabilité de test, ou null si non configuré. */
const _observabilityFile = process.env.FACTORY_ACTIVE_CASE_FILE ?? null

// ---------------------------------------------------------------------------
// API multi-case (principale)
// ---------------------------------------------------------------------------

/**
 * Enregistre un case AgentOS comme actif.
 * Idempotent : enregistrer un caseId déjà présent est une no-op.
 *
 * @param {string} caseId
 * @param {string} [label]  Étiquette lisible (pour le debug/logs). Défaut = caseId.
 */
export function registerActiveCase(caseId, label) {
  if (_registry.has(caseId)) return
  _registry.set(caseId, label ?? caseId)
  if (_observabilityFile) {
    try { writeFileSync(_observabilityFile, caseId, 'utf8') } catch { /* ignore */ }
  }
}

/**
 * Retire un case du registre.
 * Idempotent : retirer un caseId absent est une no-op.
 *
 * @param {string} caseId
 */
export function unregisterActiveCase(caseId) {
  _registry.delete(caseId)
  if (_observabilityFile && _registry.size === 0) {
    try { unlinkSync(_observabilityFile) } catch { /* ignore */ }
  }
}

/**
 * Retourne un tableau snapshot des caseIds actuellement actifs.
 * La copie garantit que les modifications ultérieures du registre
 * n'affectent pas l'itération en cours.
 *
 * @returns {string[]}
 */
export function getActiveCaseIds() {
  return [..._registry.keys()]
}

// ---------------------------------------------------------------------------
// API legacy (compatibilité avec agentos.mjs / runAgentTurn)
// ---------------------------------------------------------------------------

/**
 * Publie le case AgentOS actuellement en cours de polling (API legacy).
 * Délègue vers registerActiveCase.
 *
 * @param {string} caseId
 */
export function setActiveCaseId(caseId) {
  registerActiveCase(caseId)
}

/**
 * Efface le case actif (API legacy).
 * Délègue vers unregisterActiveCase.
 *
 * @param {string} caseId  Le caseId à retirer (nécessaire dans l'API multi-case).
 */
export function clearActiveCaseId(caseId) {
  if (caseId != null) {
    unregisterActiveCase(caseId)
  }
}

/**
 * Retourne le premier case actif, ou null s'il n'y en a pas.
 * Conservé pour la compatibilité avec les tests qui vérifient l'état
 * après un setActiveCaseId/clearActiveCaseId.
 *
 * Dans un contexte multi-case, préférer getActiveCaseIds().
 *
 * @returns {string|null}
 */
export function getActiveCaseId() {
  const first = _registry.keys().next()
  return first.done ? null : first.value
}
