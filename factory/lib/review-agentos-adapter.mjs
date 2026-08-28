/**
 * Adaptateur AgentOS réel pour review-engine.mjs.
 *
 * Ce module expose un objet `AgentOps` prêt à l'emploi qui câble les fonctions
 * de `agentos.mjs` vers l'interface `AgentOps` attendue par `runReview`.
 *
 * ## Ce que ce module fait
 *
 * - Fournit `makeReviewAgentOps(namespaceId)` qui retourne un `AgentOps` concret.
 * - Traduit les signatures de `agentos.mjs` vers celles de `AgentOps`.
 * - Reste déconnecté de us-loop et de tout workflow existant.
 *
 * ## Ce que ce module ne fait PAS
 *
 * - Invoquer des agents reviewers réels (c'est `runReview` qui le fait).
 * - Écrire dans le registre JSONL.
 * - Choisir les transitions de workflow.
 * - Contenir de la logique métier.
 *
 * ## Usage
 *
 * ```js
 * import { makeReviewAgentOps } from './review-agentos-adapter.mjs'
 * import { runReview } from './review-engine.mjs'
 *
 * const agentOps = makeReviewAgentOps(namespaceId)
 * const result = await runReview({ namespaceId, ..., agentOps })
 * ```
 *
 * ## Pourquoi un adaptateur séparé ?
 *
 * `review-engine.mjs` injecte `AgentOps` pour être testable sans réseau.
 * Cet adaptateur est le seul endroit qui importe `agentos.mjs` pour la revue.
 * Les tests de `review-engine.mjs` continuent d'utiliser des fakes injectables.
 * Les tests de cet adaptateur vérifient le câblage sans appeler AgentOS réel.
 */

import {
  createCase,
  runAgentTurn,
  killCase,
  preflightAgent,
  listIntegrations,
} from './agentos.mjs'

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

/**
 * @typedef {import('./review-engine.mjs').AgentOps} AgentOps
 */

// ---------------------------------------------------------------------------
// Fabrique
// ---------------------------------------------------------------------------

/**
 * Crée un `AgentOps` réel en câblant les fonctions de `agentos.mjs`.
 *
 * Le `namespaceId` est fourni par le workflow appelant et passé en closure
 * aux opérations qui en ont besoin (preflightAgent, listIntegrations, createCase).
 *
 * `runAgentTurn` et `killCase` prennent un `caseId` directement — ils n'ont
 * pas besoin du namespace (le case l'embarque déjà côté AgentOS).
 *
 * @param {string} namespaceId
 * @returns {AgentOps}
 */
export function makeReviewAgentOps(namespaceId) {
  return {
    /**
     * Crée un case racine dans AgentOS pour un reviewer.
     *
     * @param {string} _namespaceId  Ignoré : on utilise le namespaceId de la closure.
     * @param {string} title
     * @returns {Promise<{ id: string }>}
     */
    async createCase(_namespaceId, title) {
      return createCase(namespaceId, title)
    },

    /**
     * Poste un message et attend la quiescence du case.
     *
     * La signature de `runAgentTurn` dans `agentos.mjs` attend :
     *   (caseId, agentName, brief, opts?)
     *
     * La signature de `AgentOps.runAgentTurn` dans `review-engine.mjs` attend :
     *   (caseId, agentName, brief, opts?)
     *
     * Elles sont identiques — on passe directement.
     *
     * @param {string} caseId
     * @param {string} agentName
     * @param {string} brief
     * @param {{ startTimeoutMs?: number, workTimeoutMs?: number }} [opts]
     */
    async runAgentTurn(caseId, agentName, brief, opts) {
      return runAgentTurn(caseId, agentName, brief, opts)
    },

    /**
     * Tue un case AgentOS.
     *
     * @param {string} caseId
     */
    async killCase(caseId) {
      return killCase(caseId)
    },

    /**
     * Vérifie qu'un agent est utilisable comme reviewer (lecture seule, sans subAgents).
     *
     * @param {string} _namespaceId  Ignoré : on utilise le namespaceId de la closure.
     * @param {string} agentName
     */
    async preflightAgent(_namespaceId, agentName) {
      return preflightAgent(namespaceId, agentName)
    },

    /**
     * Liste les IntegrationConfig du namespace pour le contrôle des intégrations.
     *
     * @param {string} _namespaceId  Ignoré : on utilise le namespaceId de la closure.
     */
    async listIntegrations(_namespaceId) {
      return listIntegrations(namespaceId)
    },
  }
}
