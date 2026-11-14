import { HttpErrorResponse } from '@angular/common/http'
import { computed, inject, Injectable, OnDestroy, signal } from '@angular/core'
import { Subscription } from 'rxjs'
import { SideQuestionApiService } from './side-question-api.service'

/** What the user is told when a side question fails, by HTTP status. The server's own message is never shown. */
export function sideQuestionErrorMessage(err: unknown): string {
  const status = err instanceof HttpErrorResponse ? err.status : undefined
  switch (status) {
    case 400:
      return 'This question cannot be asked: it is empty or too long.'
    case 403:
    case 404:
      return 'This case is no longer available.'
    case 409:
      return 'No AI model is available to answer: ask an administrator to configure a default model.'
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

  /** Asks [question] about the case [caseId], replacing the aside that was shown. A blank question is ignored. */
  ask(caseId: string, question: string): void {
    const text = question.trim()
    if (!text) return
    this.cancelRequest()
    const generation = ++this.generation
    this.question.set(text)
    this.pending.set(true)
    this.answer.set(null)
    this.error.set(null)
    this.subscription = this.api.ask(caseId, text).subscribe({
      next: (response) => {
        if (generation !== this.generation) return
        this.answer.set(response.answer)
        this.pending.set(false)
      },
      error: (err: unknown) => {
        if (generation !== this.generation) return
        this.error.set(sideQuestionErrorMessage(err))
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
