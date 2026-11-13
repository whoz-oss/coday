import { Injectable, OnDestroy, signal } from '@angular/core'

/**
 * Theme of the apps that host the AgentOS design tokens (apps/durable-cockpit, apps/factory-cockpit): light / dark /
 * follow the OS, plus the skin (variant). It applies itself on `<html data-theme>`, where the tokens of
 * apps/client/src/app/styles/colors.scss are keyed.
 *
 * It mirrors AgentosThemeService of libs/agentos-ui on purpose: same behaviour, same localStorage keys
 * (`agentos.theme.variant` / `agentos.theme.mode`) and same `data-theme` values, so the choice made in Coday, in the
 * AgentOS UI or in a cockpit applies to the others (same origin).
 *
 * It is not imported from agentos-ui because its barrel drags the whole lazy lib (and its API client) into these small
 * apps. The tokens themselves are never copied: they come from colors.scss.
 */

export const DS_THEME_VARIANTS = [
  { id: 'industry', label: 'Industry' },
  { id: 'terminal', label: 'Terminal' },
  { id: 'whoz-oss', label: 'Whoz OSS' },
] as const

export type ThemeVariant = (typeof DS_THEME_VARIANTS)[number]['id']

export const DS_THEME_MODES = ['light', 'dark', 'system'] as const
export type ThemeMode = (typeof DS_THEME_MODES)[number]

export interface ThemeState {
  variant: ThemeVariant
  mode: ThemeMode
}

const STORAGE_KEY_VARIANT = 'agentos.theme.variant'
const STORAGE_KEY_MODE = 'agentos.theme.mode'

export function isThemeVariant(value: unknown): value is ThemeVariant {
  return DS_THEME_VARIANTS.some((v) => v.id === value)
}

export function isThemeMode(value: unknown): value is ThemeMode {
  return (DS_THEME_MODES as readonly unknown[]).includes(value)
}

/**
 * The mode after each one when cycling (light → dark → system → light). A total map rather than an index into an
 * array: with `noUncheckedIndexedAccess`, `array[i]` is `T | undefined`, a lookup in a Record over the union is not.
 */
const NEXT_MODE: Record<ThemeMode, ThemeMode> = { light: 'dark', dark: 'system', system: 'light' }

export function nextThemeMode(mode: ThemeMode): ThemeMode {
  return NEXT_MODE[mode]
}

/** `data-theme` of <html>: none for Industry light (the default), else `<variant>-<mode>`. */
export function themeAttribute(variant: ThemeVariant, resolvedMode: 'light' | 'dark'): string | null {
  return variant === 'industry' && resolvedMode === 'light' ? null : `${variant}-${resolvedMode}`
}

/** Light / dark / follow the OS, with the skin (variant). Applies itself on <html data-theme>. */
@Injectable({ providedIn: 'root' })
export class DsThemeService implements OnDestroy {
  private readonly _theme = signal<ThemeState>(this.readStored())
  readonly theme = this._theme.asReadonly()

  private readonly media =
    typeof window !== 'undefined' && typeof window.matchMedia === 'function'
      ? window.matchMedia('(prefers-color-scheme: dark)')
      : null

  private readonly onSystemChange = (): void => {
    if (this._theme().mode === 'system') this.apply(this._theme())
  }

  constructor() {
    this.apply(this._theme())
    this.media?.addEventListener('change', this.onSystemChange)
  }

  ngOnDestroy(): void {
    this.media?.removeEventListener('change', this.onSystemChange)
  }

  setVariant(variant: ThemeVariant): void {
    this.set({ ...this._theme(), variant })
  }

  setMode(mode: ThemeMode): void {
    this.set({ ...this._theme(), mode })
  }

  /** light → dark → system → light. */
  cycleMode(): void {
    this.setMode(nextThemeMode(this._theme().mode))
  }

  private set(state: ThemeState): void {
    this._theme.set(state)
    try {
      localStorage.setItem(STORAGE_KEY_VARIANT, state.variant)
      localStorage.setItem(STORAGE_KEY_MODE, state.mode)
    } catch {
      // localStorage may be unavailable: the theme still applies for the session.
    }
    this.apply(state)
  }

  private apply(state: ThemeState): void {
    if (typeof document === 'undefined' || !document.documentElement) return
    const dark = state.mode === 'dark' || (state.mode === 'system' && this.media?.matches === true)
    const attr = themeAttribute(state.variant, dark ? 'dark' : 'light')
    if (attr) document.documentElement.setAttribute('data-theme', attr)
    else document.documentElement.removeAttribute('data-theme')
  }

  private readStored(): ThemeState {
    try {
      const variant = localStorage.getItem(STORAGE_KEY_VARIANT)
      const mode = localStorage.getItem(STORAGE_KEY_MODE)
      return {
        variant: isThemeVariant(variant) ? variant : 'industry',
        mode: isThemeMode(mode) ? mode : 'system',
      }
    } catch {
      return { variant: 'industry', mode: 'system' }
    }
  }
}
