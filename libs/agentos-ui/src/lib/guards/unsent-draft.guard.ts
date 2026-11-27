import { inject } from '@angular/core'
import { CanDeactivateFn } from '@angular/router'
import { UnsentDraftService } from '../services/unsent-draft.service'

/** Asks before a navigation drops a prompt that was not sent. */
export const unsentDraftGuard: CanDeactivateFn<unknown> = () => inject(UnsentDraftService).confirmDiscard()
