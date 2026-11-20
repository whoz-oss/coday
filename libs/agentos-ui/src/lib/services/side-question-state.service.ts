import { HttpErrorResponse } from '@angular/common/http'
import { computed, inject, Injectable, OnDestroy, signal } from '@angular/core'
import { Subscription } from 'rxjs'
import { SideQuestionApiService } from './side-question-api.service'

/** The longest question the server accepts, once trimmed (`CaseSideQuestionController.MAX_QUESTION_LENGTH`). */
export const SIDE_QUESTION_MAX_LENGTH = 4000

/** How much of a question too long to be asked the aside shows, in characters (code points). */
const TOO_LONG_PREVIEW_LENGTH = 280

/** Added to every error: the composer keeps the question until it is answered. */
export const QUESTION_KEPT = 'Your question is still in the composer.'

/** What the user is told when a side question fails, by HTTP status. The server's own message is never shown. */
export function sideQuestionErrorMessage(err: unknown): string {
  const status = err instanceof HttpErrorResponse ? err.status : undefined
  switch (status) {
    case 400:
      return 'This question cannot be asked: it is empty or too long.'
    case 403:
      return 'You can read this case but not ask side questions on it: they spend its budget.'
    case 404:
      return 'This case is no longer available.'
    case 409:
      return (
        'This question cannot be answered now: no AI model is available, ' +
        'or this case is stopped, waiting for a cost confirmation or at its run cost limit.'
      )
    case 502:
      return 'The model could not answer. Try again.'
    default:
      return 'The side question failed. Try again.'
  }
}

/**
 * SideQuestionStateService — the state of the aside panel of the /btw side questions: the question asked, its
 * answer or its error, and whether it is still pending. At most one side question at a time: a new one replaces
 * the previous, and an answer that arrives after it was replaced or dismissed is dropped.
 *
 * Provided by the chat component (NOT root), so each chat has its own, and its pending request is cancelled
 * with it. Nothing is persisted: the answer lives here until it is dismissed.
 */
@Injectable()
export class SideQuestionStateService implements OnDestroy {
  private readonly api = inject(SideQuestionApiService)

  /** The question being answered or answered, null when no aside is shown. */
  readonly question = signal<string | null>(null)
  readonly pending = signal(false)
  readonly answer = signal<string | null>(null)
  readonly error = signal<string | null>(null)
  /** The aside panel is shown. */
  readonly visible = computed(() => this.question() !== null)

  private subscription: Subscription | null = null
  /** Bumped by every [ask] and [dismiss]: a response of an older one is stale. */
  private generation = 0

  /**
   * Asks [question] about the case [caseId], replacing the aside that was shown. A blank question is ignored; one
   * longer than [SIDE_QUESTION_MAX_LENGTH] is not sent, the aside says why. The same question asked again while it
   * is pending is ignored: no second request. [onAnswered] is called when the answer arrives, and never on a failure
   * or once the aside was replaced or dismissed.
   */
  ask(caseId: string, question: string, onAnswered?: () => void): void {
    const text = question.trim()
    if (!text) return
    if (this.pending() && this.question() === text) return
    this.cancelRequest()
    const generation = ++this.generation
    this.answer.set(null)
    if (text.length > SIDE_QUESTION_MAX_LENGTH) {
      this.question.set(preview(text))
      this.pending.set(false)
      this.error.set(
        `This question is too long (${text.length} characters): a side question has at most ` +
          `${SIDE_QUESTION_MAX_LENGTH} characters. ${QUESTION_KEPT} Shorten it there and ask again.`
      )
      return
    }
    this.question.set(text)
    this.pending.set(true)
    this.error.set(null)
    this.subscription = this.api.ask(caseId, text).subscribe({
      next: (response) => {
        if (generation !== this.generation) return
        this.answer.set(response.answer)
        this.pending.set(false)
        onAnswered?.()
      },
      error: (err: unknown) => {
        if (generation !== this.generation) return
        this.error.set(`${sideQuestionErrorMessage(err)} ${QUESTION_KEPT}`)
        this.pending.set(false)
      },
    })
  }

  /** Closes the aside. A request still in flight is cancelled, its answer would have nowhere to go. */
  dismiss(): void {
    this.generation++
    this.cancelRequest()
    this.question.set(null)
    this.pending.set(false)
    this.answer.set(null)
    this.error.set(null)
  }

  ngOnDestroy(): void {
    this.generation++
    this.cancelRequest()
  }

  private cancelRequest(): void {
    this.subscription?.unsubscribe()
    this.subscription = null
  }
}

/** The start of a question too long to be shown whole, never cutting a character (code point) in two. */
function preview(text: string): string {
  const characters = Array.from(text)
  return characters.length > TOO_LONG_PREVIEW_LENGTH
    ? `${characters.slice(0, TOO_LONG_PREVIEW_LENGTH).join('')}…`
    : text
}
