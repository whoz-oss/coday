import { inject, Injectable, InjectionToken, OnDestroy, signal, Signal } from '@angular/core'

/** What a variant can actually render: light or dark (`system` is only a way to pick one of them). */
export type ResolvedThemeMode = 'light' | 'dark'

/** Supported color modes — light / dark / follow OS. */
export const THEME_MODES = ['light', 'dark', 'system'] as const
export type ThemeMode = (typeof THEME_MODES)[number]

/** One entry of the variant table. */
export interface ThemeVariantDefinition {
  readonly id: string
  readonly label: string
  readonly description: string
  /** Modes this variant can render. The first one is the fallback when the requested mode is not supported. */
  readonly modes: readonly ResolvedThemeMode[]
}

/**
 * Single source of truth for the theme variants (visual identity / skin). The `ThemeVariant` type, the list of ids,
 * the skin selector of the profile page and the mode locking all derive from this table. Industry stays first: it is
 * the default and the fallback for an unknown stored id.
 */
export const THEME_VARIANT_DEFINITIONS = [
  { id: 'industry', label: 'Industry', description: 'Bleu acier, Barlow', modes: ['light', 'dark'] },
  { id: 'terminal', label: 'Terminal', description: 'Ambre CRT, JetBrains Mono', modes: ['light', 'dark'] },
  { id: 'whoz-oss', label: 'Whoz OSS', description: 'Bleu franc, Roboto, formes arrondies', modes: ['light', 'dark'] },
] as const satisfies readonly ThemeVariantDefinition[]

export type ThemeVariant = (typeof THEME_VARIANT_DEFINITIONS)[number]['id']

/** Ids of the supported variants, derived from the table. */
export const THEME_VARIANTS: readonly ThemeVariant[] = THEME_VARIANT_DEFINITIONS.map((variant) => variant.id)

/**
 * Full theme state: variant (skin) × mode (light/dark/system) — what the user chose.
 * Maps to a `data-theme` attribute value through `resolveEffectiveMode`: no attribute for industry light (default),
 * `"industry-dark"`, `"terminal-light"`, `"terminal-dark"`, `"whoz-oss-light"`, `"whoz-oss-dark"`.
 */
export interface ThemeState {
  variant: ThemeVariant
  mode: ThemeMode
}

export function isThemeVariant(value: unknown): value is ThemeVariant {
  return THEME_VARIANT_DEFINITIONS.some((variant) => variant.id === value)
}

/** Definition of a variant; an unknown id falls back to industry. */
export function getThemeVariantDefinition(variant: string): ThemeVariantDefinition {
  return THEME_VARIANT_DEFINITIONS.find((definition) => definition.id === variant) ?? THEME_VARIANT_DEFINITIONS[0]
}

/** Whether the variant renders both light and dark, i.e. whether a light/dark switch makes sense for it. */
export function supportsModeSwitch(variant: string): boolean {
  return getThemeVariantDefinition(variant).modes.length > 1
}

/** Whether the variant honours this mode. `system` only makes sense for a variant that has both light and dark. */
export function isModeSupported(variant: string, mode: ThemeMode): boolean {
  const { modes } = getThemeVariantDefinition(variant)
  return mode === 'system' ? modes.length > 1 : modes.includes(mode)
}

/**
 * The mode actually rendered: the chosen mode (`system` resolved against the OS preference) when the variant supports
 * it, otherwise the first mode the variant supports. Pure: it never changes what the user chose.
 */
export function resolveEffectiveMode(variant: string, mode: ThemeMode, systemPrefersDark: boolean): ResolvedThemeMode {
  return resolveSupportedMode(getThemeVariantDefinition(variant).modes, mode, systemPrefersDark)
}

/**
 * The fallback rule behind `resolveEffectiveMode`, on an explicit list of supported modes, so that the guard stays
 * testable (and reusable) without a light-only variant in the table: the wanted mode when supported, otherwise the
 * first supported one.
 */
export function resolveSupportedMode(
  modes: readonly ResolvedThemeMode[],
  mode: ThemeMode,
  systemPrefersDark: boolean
): ResolvedThemeMode {
  const wanted: ResolvedThemeMode = mode === 'dark' || (mode === 'system' && systemPrefersDark) ? 'dark' : 'light'
  return modes.includes(wanted) ? wanted : (modes[0] ?? 'light')
}

/** Whether the OS currently prefers a dark color scheme. */
export function prefersDarkScheme(): boolean {
  return (
    typeof window !== 'undefined' &&
    typeof window.matchMedia === 'function' &&
    window.matchMedia('(prefers-color-scheme: dark)').matches
  )
}

/** `data-theme` value for a variant rendered in a mode; null for industry light, the attribute-less default. */
function themeAttribute(variant: string, mode: ResolvedThemeMode): string | null {
  return variant === 'industry' && mode === 'light' ? null : `${variant}-${mode}`
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
 * The stored choice is never rewritten to fit a variant: a light-only variant renders light whatever the
 * stored mode, and the user's mode comes back as soon as they switch to a variant that supports it.
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
    const effectiveMode = resolveEffectiveMode(state.variant, state.mode, this.media?.matches === true)
    const attr = themeAttribute(state.variant, effectiveMode)
    if (attr) {
      document.documentElement.setAttribute('data-theme', attr)
    } else {
      document.documentElement.removeAttribute('data-theme')
    }
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
      const variant: ThemeVariant = isThemeVariant(storedVariant) ? storedVariant : 'industry'
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
