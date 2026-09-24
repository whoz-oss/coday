import { unlinkSync, writeFileSync } from 'node:fs'

/** caseId → label (ou caseId si aucune étiquette n'est fournie). */
const registry = new Map<string, string>()

/** Chemin du fichier d'observabilité de test, ou null si non configuré. */
const observabilityFile = process.env.FACTORY_ACTIVE_CASE_FILE ?? null

/**
 * Enregistre un case AgentOS comme actif.
 * Idempotent : enregistrer un caseId déjà présent est une no-op.
 */
export function registerActiveCase(caseId: string, label?: string): void {
  if (registry.has(caseId)) return
  registry.set(caseId, label ?? caseId)
  if (observabilityFile) {
    try {
      writeFileSync(observabilityFile, caseId, 'utf8')
    } catch {
      // Les erreurs d'observabilité ne doivent jamais interrompre le workflow.
    }
  }
}

/**
 * Retire un case du registre.
 * Idempotent : retirer un caseId absent est une no-op.
 */
export function unregisterActiveCase(caseId: string): void {
  registry.delete(caseId)
  if (observabilityFile && registry.size === 0) {
    try {
      unlinkSync(observabilityFile)
    } catch {
      // Les erreurs d'observabilité ne doivent jamais interrompre le workflow.
    }
  }
}

/** Retourne un snapshot des caseIds actuellement actifs. */
export function getActiveCaseIds(): string[] {
  return [...registry.keys()]
}

/** API legacy : délègue vers registerActiveCase. */
export function setActiveCaseId(caseId: string): void {
  registerActiveCase(caseId)
}

/** API legacy : délègue vers unregisterActiveCase et accepte null. */
export function clearActiveCaseId(caseId: string | null): void {
  if (caseId != null) {
    unregisterActiveCase(caseId)
  }
}

/** API legacy : retourne le premier case actif, ou null. */
export function getActiveCaseId(): string | null {
  const first = registry.keys().next()
  return first.done ? null : first.value
}
