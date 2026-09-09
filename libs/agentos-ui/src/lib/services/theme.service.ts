import { inject, Injectable, InjectionToken, OnDestroy, signal, Signal } from '@angular/core'

/** Supported theme variants — visual identity / skin. */
export const THEME_VARIANTS = ['industry', 'terminal'] as const
export type ThemeVariant = (typeof THEME_VARIANTS)[number]

/** Supported color modes — light / dark / follow OS. */
export const THEME_MODES = ['light', 'dark', 'system'] as const
export type ThemeMode = (typeof THEME_MODES)[number]

/**
 * Full theme state: variant (skin) × mode (light/dark/system).
 * Maps to a `data-theme` attribute value: `"industry"` (light default),
 * `"industry-dark"`, `"terminal-light"`, `"terminal-dark"`.
 */
export interface ThemeState {
  variant: ThemeVariant
  mode: ThemeMode
}

/**
 * Abstraction over theme state. agentos-ui components consume this port — never a concrete host
 * service — so the lib keeps no dependency on app-level services from apps/client.
 */
export interface ThemePort {
  readonly theme: Signal<ThemeState>
  setTheme(state: ThemeState): void
}

const STORAGE_KEY_VARIANT = 'agentos.theme.variant'
const STORAGE_KEY_MODE = 'agentos.theme.mode'
// Legacy key — migrated on first read.
const STORAGE_KEY_LEGACY = 'agentos.theme'

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
  private readonly _theme = signal<ThemeState>(this.readStoredTheme())
  readonly theme = this._theme.asReadonly()

  private readonly media =
    typeof window !== 'undefined' && window.matchMedia ? window.matchMedia('(prefers-color-scheme: dark)') : null

  private readonly onSystemChange = (): void => {
    if (this._theme().mode === 'system') {
      this.applyTheme(this._theme())
    }
  }

  constructor() {
    this.applyTheme(this._theme())
    this.media?.addEventListener('change', this.onSystemChange)
  }

  ngOnDestroy(): void {
    this.media?.removeEventListener('change', this.onSystemChange)
  }

  setTheme(state: ThemeState): void {
    this._theme.set(state)
    try {
      localStorage.setItem(STORAGE_KEY_VARIANT, state.variant)
      localStorage.setItem(STORAGE_KEY_MODE, state.mode)
    } catch {
      // localStorage may be unavailable; the theme still applies for the session.
    }
    this.applyTheme(state)
  }

  private applyTheme(state: ThemeState): void {
    if (typeof document === 'undefined' || !document.documentElement) return
    const resolvedMode = state.mode === 'system' ? this.resolveSystem() : state.mode
    const attr =
      state.variant === 'industry' && resolvedMode === 'light'
        ? null // :root default = industry-light (no attribute)
        : `${state.variant}-${resolvedMode}` // e.g. "industry-dark", "terminal-light"
    if (attr) {
      document.documentElement.setAttribute('data-theme', attr)
    } else {
      document.documentElement.removeAttribute('data-theme')
    }
  }

  private resolveSystem(): 'light' | 'dark' {
    return this.media?.matches ? 'dark' : 'light'
  }

  private readStoredTheme(): ThemeState {
    try {
      // Migrate legacy single-key storage (light/dark/system → industry + mode)
      const legacy = localStorage.getItem(STORAGE_KEY_LEGACY)
      if (legacy && !localStorage.getItem(STORAGE_KEY_MODE)) {
        const mode = (THEME_MODES as readonly string[]).includes(legacy) ? (legacy as ThemeMode) : 'system'
        localStorage.setItem(STORAGE_KEY_MODE, mode)
        localStorage.setItem(STORAGE_KEY_VARIANT, 'industry')
        localStorage.removeItem(STORAGE_KEY_LEGACY)
        return { variant: 'industry', mode }
      }

      const storedVariant = localStorage.getItem(STORAGE_KEY_VARIANT)
      const storedMode = localStorage.getItem(STORAGE_KEY_MODE)
      const variant = (THEME_VARIANTS as readonly string[]).includes(storedVariant ?? '')
        ? (storedVariant as ThemeVariant)
        : 'industry'
      const mode = (THEME_MODES as readonly string[]).includes(storedMode ?? '') ? (storedMode as ThemeMode) : 'system'
      return { variant, mode }
    } catch {
      return { variant: 'industry', mode: 'system' }
    }
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
