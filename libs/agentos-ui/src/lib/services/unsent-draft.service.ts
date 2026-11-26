import { DestroyRef, inject, Injectable, Signal } from '@angular/core'
import { DOCUMENT } from '@angular/common'

export const UNSENT_DRAFT_MESSAGE = 'You have a prompt that has not been sent. Leave this page and discard it?'

/**
 * UnsentDraftService — knows whether a composer holds a prompt the user typed and did not send (#1788), so that
 * leaving the screen asks first: the browser dialog on a tab close or reload, [confirmDiscard] on a navigation.
 *
 * The composers (home and case chat) register a "has a draft" signal for their own lifetime: the shell and the route
 * guard need not know them.
 */
@Injectable({ providedIn: 'root' })
export class UnsentDraftService {
  private readonly drafts = new Set<Signal<boolean>>()

  constructor() {
    const view = inject(DOCUMENT).defaultView
    if (!view) return
    const onBeforeUnload = (event: BeforeUnloadEvent): void => {
      if (!this.hasUnsentDraft()) return
      event.preventDefault()
      // Legacy requirement of Chromium and Electron to show the dialog. The browser's own text is shown.
      event.returnValue = ''
    }
    view.addEventListener('beforeunload', onBeforeUnload)
    inject(DestroyRef).onDestroy(() => view.removeEventListener('beforeunload', onBeforeUnload))
  }

  /** Registers the "has an unsent draft" signal of a composer for the lifetime of [destroyRef]. */
  register(hasDraft: Signal<boolean>, destroyRef: DestroyRef): void {
    this.drafts.add(hasDraft)
    destroyRef.onDestroy(() => this.drafts.delete(hasDraft))
  }

  hasUnsentDraft(): boolean {
    for (const hasDraft of this.drafts) {
      if (hasDraft()) return true
    }
    return false
  }

  /** True when leaving may proceed: there is no draft, or the user confirmed discarding it. */
  confirmDiscard(): boolean {
    return !this.hasUnsentDraft() || window.confirm(UNSENT_DRAFT_MESSAGE)
  }
}
