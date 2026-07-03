import { inject, Injectable, InjectionToken, OnDestroy, signal, Signal } from '@angular/core'

/** Supported theme modes — single source for both the type and runtime validation. */
export const THEME_MODES = ['light', 'dark', 'system'] as const
export type ThemeMode = (typeof THEME_MODES)[number]

/**
 * Abstraction over theme state. agentos-ui components consume this port — never a concrete host
 * service — so the lib keeps no dependency on app-level services from apps/client (see GUIDELINES.md).
 *
 * State is exposed as a single reactive `theme` signal: consumers read it directly in templates
 * (no Observable-to-signal bridging, no imperative getter).
 */
export interface ThemePort {
  readonly theme: Signal<ThemeMode>
  setTheme(mode: ThemeMode): void
}

const STORAGE_KEY = 'agentos.theme'

/**
 * AgentosThemeService — the lib's self-contained default implementation of ThemePort, so
 * agentos-ui works standalone. Persists under a namespaced localStorage key, reflects the
 * resolved theme on `document.documentElement` via `data-theme`, and tracks the OS preference
 * while in `system` mode.
 *
 * When agentos-ui is hosted inside the Coday client, the host overrides THEME_PORT with its own
 * theme service, so a single service owns `data-theme` instead of two fighting over it.
 */
@Injectable({ providedIn: 'root' })
export class AgentosThemeService implements ThemePort, OnDestroy {
  private readonly _theme = signal<ThemeMode>(this.readStoredTheme())
  readonly theme = this._theme.asReadonly()

  private readonly media =
    typeof window !== 'undefined' && window.matchMedia ? window.matchMedia('(prefers-color-scheme: dark)') : null

  private readonly onSystemChange = (): void => {
    // Only react to OS changes while following the system preference.
    if (this._theme() === 'system') {
      this.applyTheme('system')
    }
  }

  constructor() {
    this.applyTheme(this._theme())
    this.media?.addEventListener('change', this.onSystemChange)
  }

  ngOnDestroy(): void {
    this.media?.removeEventListener('change', this.onSystemChange)
  }

  setTheme(mode: ThemeMode): void {
    this._theme.set(mode)
    try {
      localStorage.setItem(STORAGE_KEY, mode)
    } catch {
      // localStorage may be unavailable (e.g. private mode); the theme still applies for the session.
    }
    this.applyTheme(mode)
  }

  private applyTheme(mode: ThemeMode): void {
    const resolved = mode === 'system' ? this.resolveSystem() : mode
    if (typeof document === 'undefined' || !document.documentElement) return
    if (resolved === 'dark') {
      document.documentElement.setAttribute('data-theme', 'dark')
    } else {
      document.documentElement.removeAttribute('data-theme')
    }
  }

  private resolveSystem(): 'light' | 'dark' {
    return this.media?.matches ? 'dark' : 'light'
  }

  private readStoredTheme(): ThemeMode {
    try {
      const stored = localStorage.getItem(STORAGE_KEY)
      if (stored && (THEME_MODES as readonly string[]).includes(stored)) {
        return stored as ThemeMode
      }
    } catch {
      // localStorage may be unavailable; fall back to following the system preference.
    }
    return 'system'
  }
}

/**
 * Theme port token. Defaults to the lib's AgentosThemeService (standalone use); a host app
 * overrides it — e.g. `{ provide: THEME_PORT, useExisting: ClientThemeService }` — so that a
 * single service owns `document.documentElement[data-theme]`.
 */
export const THEME_PORT = new InjectionToken<ThemePort>('agentos.theme-port', {
  providedIn: 'root',
  factory: () => inject(AgentosThemeService),
})
