import { Injectable } from '@angular/core'

/**
 * Persisted association between a Factory workflow and its supervisor case.
 *
 * Keyed by `workflowId` + `namespaceId` — both are stable backend identifiers,
 * never derived from mutable display names.
 */
export interface PersistedSupervisorCase {
  /** The AgentOS case id created for this workflow. */
  caseId: string
  /** The namespace the case was created in (UUID). */
  namespaceId: string
  /** ISO timestamp of case creation (for future stale-entry cleanup). */
  createdAt: string
}

/**
 * Lookup key unambiguously identifying a supervisor case for a workflow.
 * Both fields are stable backend identifiers (UUIDs / workflow ids).
 */
export interface SupervisorCaseKey {
  workflowId: string
  namespaceId: string
}

/**
 * Thin persistence layer for supervisor case associations.
 *
 * Stores `workflowId+namespaceId → caseId` entries in `localStorage` so that
 * a page refresh does not lose the association and the user can navigate back
 * to an existing case without creating a duplicate.
 *
 * ## Scope and limitations
 * - **Browser-local**: entries are stored in the current browser's
 *   `localStorage`. A different browser, incognito context, or device will not
 *   see the same entries. This is an honest local cache, not a server-side
 *   truth.
 * - **No multi-tab deduplication guarantee**: two tabs opened simultaneously
 *   could each create a case before either persists. This is considered
 *   acceptable given the low probability and the inline idempotency guard in
 *   the component (done-state button replaced by link).
 * - **No automatic stale-entry cleanup**: entries accumulate as workflows are
 *   created. A future cleanup pass could prune entries whose `createdAt` is
 *   older than a configurable threshold.
 *
 * ## Storage failure
 * All read/write operations are wrapped in try/catch. When `localStorage` is
 * unavailable (private browsing quotas, storage disabled, quota exceeded) the
 * service degrades silently: reads return `undefined`, writes are no-ops. The
 * supervisor flow still works — it just does not survive a page refresh in that
 * context.
 */
@Injectable({ providedIn: 'root' })
export class SupervisorCaseStoreService {
  private static readonly STORAGE_KEY = 'factory-cockpit.supervisor-cases'

  /**
   * Persist the association between a workflow and its newly created supervisor
   * case. Must be called immediately after the case id is known (before the
   * initial message POST, which is best-effort and may fail).
   *
   * Silently no-ops when `localStorage` is unavailable or the quota is
   * exceeded.
   */
  save(key: SupervisorCaseKey, entry: PersistedSupervisorCase): void {
    try {
      const map = this.readMap()
      map[this.storageKey(key)] = entry
      localStorage.setItem(SupervisorCaseStoreService.STORAGE_KEY, JSON.stringify(map))
    } catch {
      // localStorage unavailable or quota exceeded — degrade silently.
    }
  }

  /**
   * Return the persisted supervisor case for this workflow, or `undefined` when
   * none has been created (or when the storage is unavailable/corrupt).
   */
  load(key: SupervisorCaseKey): PersistedSupervisorCase | undefined {
    try {
      const map = this.readMap()
      const entry = map[this.storageKey(key)]
      return isValidEntry(entry) ? entry : undefined
    } catch {
      return undefined
    }
  }

  /**
   * Remove the persisted entry for a workflow (e.g. after the workflow is
   * deleted or when the caller wants to force a new case). Silently no-ops
   * when the entry does not exist or storage is unavailable.
   */
  remove(key: SupervisorCaseKey): void {
    try {
      const map = this.readMap()
      delete map[this.storageKey(key)]
      localStorage.setItem(SupervisorCaseStoreService.STORAGE_KEY, JSON.stringify(map))
    } catch {
      // Degrade silently.
    }
  }

  /** Stable, unambiguous storage key for a workflow+namespace pair. */
  private storageKey(key: SupervisorCaseKey): string {
    // Both segments are stable backend identifiers — no display-name fallback.
    return `${key.namespaceId}::${key.workflowId}`
  }

  /** Read the full entry map, returning an empty object on any parse failure. */
  private readMap(): Record<string, unknown> {
    try {
      const raw = localStorage.getItem(SupervisorCaseStoreService.STORAGE_KEY)
      if (!raw) return {}
      const parsed: unknown = JSON.parse(raw)
      return typeof parsed === 'object' && parsed !== null && !Array.isArray(parsed)
        ? (parsed as Record<string, unknown>)
        : {}
    } catch {
      return {}
    }
  }
}

/** Type-guard: reject corrupt or partial entries without throwing. */
function isValidEntry(value: unknown): value is PersistedSupervisorCase {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) return false
  const record = value as Record<string, unknown>
  return (
    typeof record['caseId'] === 'string' &&
    record['caseId'].length > 0 &&
    typeof record['namespaceId'] === 'string' &&
    record['namespaceId'].length > 0 &&
    typeof record['createdAt'] === 'string' &&
    record['createdAt'].length > 0
  )
}
