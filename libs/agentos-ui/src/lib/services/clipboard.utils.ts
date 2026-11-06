import { DestroyRef, inject, signal, Signal } from '@angular/core'

/**
 * Copy text to the clipboard, resolving to whether it worked.
 *
 * The async Clipboard API is only exposed in secure contexts (https, localhost) and can be refused by
 * the browser, so a failure falls back to the legacy hidden-textarea + `execCommand('copy')` path,
 * which still works over plain http. Never throws: callers only need to know whether to show success.
 */
export async function copyText(text: string): Promise<boolean> {
  if (typeof navigator !== 'undefined' && navigator.clipboard?.writeText) {
    try {
      await navigator.clipboard.writeText(text)
      return true
    } catch {
      // Permission denied or insecure context: try the legacy path below.
    }
  }
  return legacyCopy(text)
}

function legacyCopy(text: string): boolean {
  if (typeof document === 'undefined' || typeof document.execCommand !== 'function') return false
  // Selecting the textarea steals focus; hand it back so keyboard users keep their place.
  const previouslyFocused = document.activeElement as HTMLElement | null
  const textarea = document.createElement('textarea')
  textarea.value = text
  textarea.setAttribute('readonly', '')
  textarea.setAttribute('aria-hidden', 'true')
  textarea.style.position = 'fixed'
  textarea.style.top = '0'
  textarea.style.opacity = '0'
  textarea.style.pointerEvents = 'none'
  document.body.appendChild(textarea)
  try {
    textarea.select()
    return document.execCommand('copy')
  } catch {
    return false
  } finally {
    textarea.remove()
    previouslyFocused?.focus?.()
  }
}

/** Outcome of the last copy, shown briefly on the button that triggered it. */
export type CopyState = 'idle' | 'copied' | 'failed'

/** How long the "Copied" / "Copy failed" feedback stays before the button goes back to normal. */
export const COPY_FEEDBACK_MS = 1500

/**
 * Per-component copy feedback: `copy(text)` writes to the clipboard and flips `state` to `copied` or
 * `failed` for {@link COPY_FEEDBACK_MS}, then back to `idle`. Must be called in an injection context
 * (it clears its timer when the component is destroyed).
 */
export function injectCopyFeedback(): { state: Signal<CopyState>; copy: (text: string) => Promise<void> } {
  const state = signal<CopyState>('idle')
  let timer: ReturnType<typeof setTimeout> | undefined
  inject(DestroyRef).onDestroy(() => clearTimeout(timer))
  return {
    state,
    copy: async (text) => {
      const ok = await copyText(text)
      state.set(ok ? 'copied' : 'failed')
      clearTimeout(timer)
      timer = setTimeout(() => state.set('idle'), COPY_FEEDBACK_MS)
    },
  }
}
