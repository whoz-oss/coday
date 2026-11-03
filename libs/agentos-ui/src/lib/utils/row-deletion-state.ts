import { signal } from '@angular/core'
import { Observable } from 'rxjs'

export interface RowDeletionExecution<T> {
  request$: Observable<T>
  errorMessage: string | ((error: unknown) => string)
  onSuccess?: (value: T) => void
}

export function createRowDeletionState() {
  const confirmingId = signal<string | null>(null)
  const deletingId = signal<string | null>(null)
  const error = signal<string | null>(null)

  return {
    confirmingId,
    deletingId,
    error,
    request(id: string): void {
      if (deletingId()) return
      confirmingId.set(id)
      error.set(null)
    },
    cancel(): void {
      if (deletingId()) return
      confirmingId.set(null)
      error.set(null)
    },
    execute<T>(id: string, execution: RowDeletionExecution<T>): void {
      if (deletingId() || confirmingId() !== id) return
      deletingId.set(id)
      error.set(null)
      execution.request$.subscribe({
        next: (value) => {
          confirmingId.set(null)
          deletingId.set(null)
          error.set(null)
          execution.onSuccess?.(value)
        },
        error: (cause: unknown) => {
          deletingId.set(null)
          error.set(
            typeof execution.errorMessage === 'function' ? execution.errorMessage(cause) : execution.errorMessage
          )
        },
      })
    },
  }
}

export type RowDeletionState = ReturnType<typeof createRowDeletionState>
