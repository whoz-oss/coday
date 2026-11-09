import { Injectable, signal, Signal } from '@angular/core'

/** Whether the case list is grouped by agent. Off by default: only a stored 'true' turns it on. */
export const GROUP_BY_AGENT_STORAGE_KEY = 'agentos.case-list.groupByAgent'

/** Prefix of the per-namespace list of collapsed group keys (a JSON array of strings). */
export const COLLAPSED_GROUPS_STORAGE_KEY_PREFIX = 'agentos.case-list.collapsedGroups.'

/**
 * CaseListPreferencesService — display preferences of the case list, persisted in localStorage like the other
 * `agentos.*` UI preferences of the lib (sidebar width, tool calls, technical logs).
 *
 * - `groupByAgent`: global (a way of reading the list, not tied to a namespace).
 * - collapsed groups: per namespace, since agent names are namespace-specific.
 *
 * The collapsed keys are never pruned when a group disappears (no case at the moment, agent renamed...): the group
 * comes back collapsed if it reappears.
 */
@Injectable({ providedIn: 'root' })
export class CaseListPreferencesService {
  private readonly _groupByAgent = signal<boolean>(this.readGroupByAgent())
  readonly groupByAgent: Signal<boolean> = this._groupByAgent.asReadonly()

  /** Collapsed keys per namespace, filled by the writes of this session (reads fall back to the storage). */
  private readonly collapsedByNamespace = signal<Readonly<Record<string, readonly string[]>>>({})

  setGroupByAgent(value: boolean): void {
    this._groupByAgent.set(value)
    this.write(GROUP_BY_AGENT_STORAGE_KEY, String(value))
  }

  /** Keys of the collapsed groups of a namespace. Reads a signal: usable from a computed. */
  collapsedGroups(namespaceId: string | null): ReadonlySet<string> {
    const namespace = namespaceId ?? ''
    return new Set(this.collapsedByNamespace()[namespace] ?? this.readCollapsed(namespace))
  }

  /** Collapses an expanded group, expands a collapsed one. */
  toggleGroupCollapsed(namespaceId: string | null, groupKey: string): void {
    const namespace = namespaceId ?? ''
    const collapsed = new Set(this.collapsedGroups(namespace))
    if (collapsed.has(groupKey)) {
      collapsed.delete(groupKey)
    } else {
      collapsed.add(groupKey)
    }
    const keys = [...collapsed]
    this.collapsedByNamespace.update((all) => ({ ...all, [namespace]: keys }))
    this.write(this.collapsedStorageKey(namespace), JSON.stringify(keys))
  }

  private collapsedStorageKey(namespace: string): string {
    return COLLAPSED_GROUPS_STORAGE_KEY_PREFIX + (namespace || 'default')
  }

  private readGroupByAgent(): boolean {
    try {
      return localStorage.getItem(GROUP_BY_AGENT_STORAGE_KEY) === 'true'
    } catch {
      // localStorage may be unavailable; fall back to the default (off).
      return false
    }
  }

  private readCollapsed(namespace: string): string[] {
    try {
      const parsed: unknown = JSON.parse(localStorage.getItem(this.collapsedStorageKey(namespace)) ?? '[]')
      return Array.isArray(parsed) ? parsed.filter((key): key is string => typeof key === 'string') : []
    } catch {
      // Unavailable storage or corrupted value: nothing is collapsed.
      return []
    }
  }

  private write(key: string, value: string): void {
    try {
      localStorage.setItem(key, value)
    } catch {
      // localStorage may be unavailable (e.g. private mode); the preference still applies for the session.
    }
  }
}
