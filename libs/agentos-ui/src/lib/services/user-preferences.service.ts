import { computed, inject, Injectable, InjectionToken, signal, Signal } from '@angular/core'

/** Supported ENTER-key behaviors in the chat composer — single source for the type and runtime validation. */
export const ENTER_KEY_BEHAVIORS = ['send', 'newline'] as const
export type EnterKeyBehavior = (typeof ENTER_KEY_BEHAVIORS)[number]

/**
 * Abstraction over user input preferences. agentos-ui components consume this port — never a
 * concrete host service — so the lib keeps no dependency on app-level services from apps/client
 * (see GUIDELINES.md), mirroring THEME_PORT. State is exposed as reactive signals consumers read
 * directly in templates.
 *
 * A host app may override USER_PREFERENCES_PORT to back the setting with its own preferences store.
 * The Coday client persists this differently (a `useEnterToSend` boolean), so a real bridge needs a
 * small adapter rather than a bare `useExisting`; the port is kept for parity with THEME_PORT and to
 * allow that bridge.
 */
export interface UserPreferencesPort {
  /**
   * How the ENTER key behaves in the chat composer:
   * - `send`: ENTER sends the message, Shift+ENTER inserts a newline (default).
   * - `newline`: ENTER inserts a newline, Ctrl/Cmd+ENTER sends the message.
   */
  readonly enterKeyBehavior: Signal<EnterKeyBehavior>
  setEnterKeyBehavior(behavior: EnterKeyBehavior): void
  /** Whether a composer keydown should send the message: ENTER only, honoring the behavior, modifiers and IME. */
  shouldSend(event: KeyboardEvent): boolean
  /** Footer hint describing the active ENTER behavior, for the composer UI. */
  readonly composerHint: Signal<string>
}

const STORAGE_KEY = 'agentos.enter-key-behavior'
const DEFAULT_BEHAVIOR: EnterKeyBehavior = 'send'

/**
 * AgentosUserPreferencesService — the lib's self-contained default implementation of
 * UserPreferencesPort, so agentos-ui works standalone. Persists under a namespaced localStorage key.
 */
@Injectable({ providedIn: 'root' })
export class AgentosUserPreferencesService implements UserPreferencesPort {
  private readonly _enterKeyBehavior = signal<EnterKeyBehavior>(this.readStoredBehavior())
  readonly enterKeyBehavior = this._enterKeyBehavior.asReadonly()

  readonly composerHint = computed(() =>
    this._enterKeyBehavior() === 'newline'
      ? 'Enter for a new line · Ctrl/Cmd+Enter to send'
      : 'Enter to send · Shift+Enter for a new line'
  )

  setEnterKeyBehavior(behavior: EnterKeyBehavior): void {
    this._enterKeyBehavior.set(behavior)
    try {
      localStorage.setItem(STORAGE_KEY, behavior)
    } catch {
      // localStorage may be unavailable (e.g. private mode); the setting still applies for the session.
    }
  }

  shouldSend(event: KeyboardEvent): boolean {
    // Only a real ENTER press sends (ignore IME composition). In send mode any non-Shift ENTER sends
    // (Shift+ENTER inserts a newline); in newline mode only a Ctrl/Cmd modifier sends.
    const isEnter = event.key === 'Enter' && !event.isComposing
    const modifierPressed = event.ctrlKey || event.metaKey
    const sendForBehavior = this._enterKeyBehavior() === 'newline' ? modifierPressed : !event.shiftKey
    return isEnter && sendForBehavior
  }

  private readStoredBehavior(): EnterKeyBehavior {
    try {
      const stored = localStorage.getItem(STORAGE_KEY)
      return stored && (ENTER_KEY_BEHAVIORS as readonly string[]).includes(stored)
        ? (stored as EnterKeyBehavior)
        : DEFAULT_BEHAVIOR
    } catch {
      // localStorage may be unavailable; fall back to the default.
      return DEFAULT_BEHAVIOR
    }
  }
}

/**
 * User preferences port token. Defaults to the lib's AgentosUserPreferencesService (standalone
 * use); a host app can override it — e.g. `{ provide: USER_PREFERENCES_PORT, useFactory: ... }`.
 */
export const USER_PREFERENCES_PORT = new InjectionToken<UserPreferencesPort>('agentos.user-preferences-port', {
  providedIn: 'root',
  factory: () => inject(AgentosUserPreferencesService),
})
