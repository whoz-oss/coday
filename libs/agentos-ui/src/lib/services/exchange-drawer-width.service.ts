import { Injectable, signal } from '@angular/core'

/** localStorage key of the width of the files drawer, shared by the case chat and the case home. */
export const FILES_DRAWER_WIDTH_KEY = 'agentos.files-drawer.width'
export const FILES_DRAWER_DEFAULT_WIDTH = 400
export const FILES_DRAWER_MIN_WIDTH = 320
/** Ratio of the viewport the drawer may take at most (same value as the `ds-drawer` default). */
export const FILES_DRAWER_MAX_WIDTH_RATIO = 0.8

/**
 * Width (px) of the right-hand files drawer, shared by the case chat and the case home so that both pages show
 * the same size, and persisted across sessions. The drawer reports the new width once a resize ends.
 */
@Injectable({ providedIn: 'root' })
export class ExchangeDrawerWidthService {
  private readonly _width = signal(this.clamp(this.readStored()))
  readonly width = this._width.asReadonly()

  setWidth(width: number): void {
    const next = this.clamp(width)
    this._width.set(next)
    try {
      localStorage.setItem(FILES_DRAWER_WIDTH_KEY, String(next))
    } catch {
      // localStorage may be unavailable (e.g. private mode); the width still applies for the session.
    }
  }

  private readStored(): number {
    try {
      const stored = Number(localStorage.getItem(FILES_DRAWER_WIDTH_KEY))
      return Number.isFinite(stored) && stored > 0 ? stored : FILES_DRAWER_DEFAULT_WIDTH
    } catch {
      return FILES_DRAWER_DEFAULT_WIDTH
    }
  }

  /** Within [min, ratio × viewport]; the minimum wins on a viewport too narrow for both. */
  private clamp(width: number): number {
    const max = Math.max(FILES_DRAWER_MIN_WIDTH, Math.floor(window.innerWidth * FILES_DRAWER_MAX_WIDTH_RATIO))
    return Math.max(FILES_DRAWER_MIN_WIDTH, Math.min(max, Math.round(width)))
  }
}
