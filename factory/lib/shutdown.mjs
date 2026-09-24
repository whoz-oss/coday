/**
 * Stateless legacy composition facade for the generated operational cluster.
 *
 * The generated bundle owns the active-case registry and shutdown application.
 * Legacy registry.mjs and review-gate.mjs remain authoritative for their existing
 * module-level state; they are injected here and are intentionally not copied to TS.
 */
import {
  createAgentOsHttpCaseTerminator,
  createShutdownController,
  endCurrentRunOnce,
  getActiveCaseIds,
  installSigtermHandler,
  processExit,
} from '../runtime/factory-operational.mjs'
import { rejectAllPendingGates } from './review-gate.mjs'

let controller = null

export function initShutdownHandler({ log } = {}) {
  const warn = (message) => log?.error ? log.error(message) : console.error(message)
  controller = createShutdownController({
    activeCaseIds: getActiveCaseIds,
    caseTerminator: createAgentOsHttpCaseTerminator({
      baseUrl: process.env.AGENTOS_URL ?? 'http://localhost:8124',
      userId: process.env.FACTORY_USER ?? 'benjamin.valdes',
    }),
    endCurrentRunOnce,
    rejectPendingGates: rejectAllPendingGates,
    warn,
    exit: processExit(),
  })
  installSigtermHandler(controller)
}

export function markCompleted() {
  controller?.markCompleted()
}
