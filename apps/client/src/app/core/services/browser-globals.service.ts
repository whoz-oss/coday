import { Injectable, inject } from '@angular/core'
import { DOCUMENT } from '@angular/common'
import { WINDOW, BrowserWindow } from '../tokens/window'

/**
 * Service that provides access to browser global objects (window and document).
 *
 * This service consolidates the injection of both WINDOW and DOCUMENT tokens,
 *
 */

@Injectable({
  providedIn: 'root',
})
export class BrowserGlobalsService {
  /**
   * The global Window object with extended browser APIs.
   * Provides access to browser-specific features like AudioContext, matchMedia, etc.
   */
  readonly window: BrowserWindow = inject(WINDOW)

  /**
   * The global Document object.
   * Provides access to the DOM and document-level operations.
   */
  readonly document: Document = inject(DOCUMENT)

  /**
   * Check if the document currently has focus.
   * Useful for notification logic and user interaction detection.
   */
  get hasFocus(): boolean {
    return this.document?.hasFocus?.() ?? false
  }

  /**
   * Get the current document element (usually <html>).
   * Useful for theme application and document-level attribute manipulation.
   */
  get documentElement(): HTMLElement | null {
    return this.document?.documentElement ?? null
  }
}
