import { InjectionToken } from '@angular/core'

/**
 * Extended Window interface with additional browser APIs that may not be in the standard Window type.
 * Note: SpeechRecognition types are accessed via `(window as any)` since they're vendor-prefixed
 * and not consistently available in TypeScript's lib.dom.d.ts
 */
export interface BrowserWindow extends Window {
  AudioContext: typeof AudioContext
  webkitAudioContext?: typeof AudioContext
}

/**
 * Injection token for the global Window object.
 *
 * Using an injection token instead of directly accessing `window` provides:
 * - Better testability (can be mocked in unit tests)
 * - SSR compatibility (can provide a mock or null in server-side rendering)
 * - Explicit dependency declaration
 *
 * Usage:
 * ```typescript
 * // In a service or component
 * private readonly window = inject(WINDOW)
 *
 * // To use safely (handles SSR/null cases)
 * if (this.window) {
 *   this.window.open(url, '_blank')
 * }
 * ```
 */
export const WINDOW = new InjectionToken<BrowserWindow>('WindowToken')

/**
 * Factory function that returns the global window object.
 * Used as the provider factory in app.config.ts
 */
export const windowFactory = (): BrowserWindow => window as BrowserWindow
