import type { CaseTerminator } from '../ports/case-terminator.js'

export interface CurrentRun {
  filePath: string
  _startedAt: number
}

export interface ShutdownDependencies {
  activeCaseIds(): string[]
  caseTerminator: CaseTerminator
  currentRun(): CurrentRun | null
  endRun(run: CurrentRun, status: 'fail', facts: Record<string, unknown>): void
  rejectPendingGates(): void
  warn(message: string): void
  exit(code: number): void
}

export interface ShutdownController {
  handle(signal: 'SIGTERM'): Promise<void>
  markCompleted(): void
}

/** Pure, injected shutdown policy. All state is instance-scoped. */
export function createShutdownController(deps: ShutdownDependencies): ShutdownController {
  let initiated = false
  let completed = false

  return {
    markCompleted() {
      completed = true
    },
    async handle(signal) {
      if (initiated || completed) return
      initiated = true
      deps.warn(`[shutdown] ${signal} reçu — arrêt gracieux en cours.`)
      deps.rejectPendingGates()

      const caseIds = deps.activeCaseIds()
      const hadActiveCases = caseIds.length > 0
      await Promise.allSettled(
        caseIds.map(async (caseId) => {
          try {
            await deps.caseTerminator.terminate(caseId)
            deps.warn(`[shutdown] Case ${caseId} tué.`)
          } catch (error) {
            deps.warn(`[shutdown] Erreur lors du kill du case ${caseId} : ${String(error)}`)
          }
        })
      )

      const run = deps.currentRun()
      if (run && !completed) {
        try {
          deps.endRun(run, 'fail', { checkoutMayBeIntermediate: hadActiveCases, terminatedBySignal: signal })
        } catch (error) {
          deps.warn(`[shutdown] Erreur lors de la finalisation du run : ${String(error)}`)
        }
      }
      deps.warn('[shutdown] Sortie.')
      deps.exit(1)
    },
  }
}
