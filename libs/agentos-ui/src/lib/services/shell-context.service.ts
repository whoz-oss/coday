import { inject, Injectable, signal } from '@angular/core'
import { Params } from '@angular/router'
import { NamespaceStateService } from '@whoz-oss/agentos-dataflow'

interface CaseLocation {
  readonly namespaceId: string
  readonly caseId: string
}

/**
 * ShellContextService — remembers where the user was working so that leaving for the settings pages
 * (namespace, administration, profile) never costs them their place.
 *
 * CaseShellComponent records the open case; the settings layouts read it back to build the
 * "return to my work" link. The case is only restored for the namespace it belongs to.
 */
@Injectable({ providedIn: 'root' })
export class ShellContextService {
  private readonly namespaceState = inject(NamespaceStateService)

  private readonly lastCase = signal<CaseLocation | null>(null)

  /** Record the case currently open, or forget it (no case open, or none selected yet). */
  rememberCase(namespaceId: string | null, caseId: string | null): void {
    this.lastCase.set(namespaceId && caseId ? { namespaceId, caseId } : null)
  }

  /**
   * Query params of the cases view to return to: `?ns=` plus `&case=` when the remembered case belongs to that
   * namespace. Empty when no namespace is known — the cases view then picks the first one itself.
   * Reads signals: call it from a `computed()` or a template to stay up to date.
   */
  workQueryParams(namespaceId: string | null = this.namespaceState.activeNamespaceId()): Params {
    if (!namespaceId) return {}
    const last = this.lastCase()
    return last?.namespaceId === namespaceId ? { ns: namespaceId, case: last.caseId } : { ns: namespaceId }
  }
}
