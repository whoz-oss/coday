import { computed, inject, Injectable, signal } from '@angular/core'
import { Params } from '@angular/router'
import { NamespaceControllerService } from '@whoz-oss/agentos-api-client'
import { NamespaceStateService } from '@whoz-oss/agentos-dataflow'

interface CaseLocation {
  readonly namespaceId: string
  readonly caseId: string
}

/** The namespace the "Back to …" link of the settings pages leads to. */
export interface BackTarget {
  readonly id: string
  readonly name: string
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
  private readonly namespaceApi = inject(NamespaceControllerService)

  private readonly lastCase = signal<CaseLocation | null>(null)
  private readonly namespaceNames = signal<Readonly<Record<string, string>>>({})
  private readonly defaultNamespaceId = signal<string | null>(null)
  private backTargetRequested = false

  /**
   * Where "Back to …" leads: the active namespace, or — when none was visited yet — the one the cases view would pick
   * by itself (the first of the list). Null while it is not resolved, or when the user has no namespace at all: the
   * link is then not shown rather than labelled with something meaningless.
   */
  readonly backTarget = computed<BackTarget | null>(() => {
    const id = this.namespaceState.activeNamespaceId() ?? this.defaultNamespaceId()
    const name = id ? this.namespaceNames()[id] : undefined
    return id && name ? { id, name } : null
  })

  /** Remember the display names of namespaces, so any page can name the namespace it leads back to. */
  rememberNamespaceNames(namespaces: readonly { id?: string; name: string }[]): void {
    const known = this.namespaceNames()
    if (namespaces.every((ns) => !ns.id || known[ns.id] === ns.name)) return
    const next = { ...known }
    for (const ns of namespaces) {
      if (ns.id) next[ns.id] = ns.name
    }
    this.namespaceNames.set(next)
  }

  /**
   * Make sure `backTarget` gets resolved when nothing was visited yet (a settings page opened directly, or reached
   * before the cases view): load the namespaces once, name them all and take the first as default, like the cases
   * view does. No-op when a target is already known or a request is in flight.
   */
  loadBackTarget(): void {
    if (this.backTarget() || this.backTargetRequested) return
    this.backTargetRequested = true
    this.namespaceApi.listAllNamespace().subscribe({
      next: (namespaces) => {
        this.rememberNamespaceNames(namespaces)
        this.defaultNamespaceId.set(namespaces[0]?.id ?? null)
      },
      // Let a later page try again (AgentOS may have been booting).
      error: () => (this.backTargetRequested = false),
    })
  }

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
