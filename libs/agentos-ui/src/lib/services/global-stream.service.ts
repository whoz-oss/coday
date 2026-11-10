import { HttpErrorResponse } from '@angular/common/http'
import { DestroyRef, inject, Injectable } from '@angular/core'
import { takeUntilDestroyed } from '@angular/core/rxjs-interop'
import { EMPTY, Observable, of, Subject, throwError, timer } from 'rxjs'
import { catchError, filter, map, retry, switchMap } from 'rxjs/operators'
import { CaseEvent } from '@whoz-oss/agentos-api-client'
import { GlobalCaseEventSseService, GlobalStreamMessage } from './global-case-event-sse.service'
import { TabCoordinatorService } from './tab-coordinator.service'
import { UserStateService } from './user-state.service'

/** First wait before reopening a stream the browser gave up on, doubled on each failure in a row. */
const RETRY_DELAY_MS = 5_000

/** Longest wait between two attempts: a backend down for an hour is knocked on once a minute. */
const MAX_RETRY_DELAY_MS = 60_000

function retryDelay(attempt: number): number {
  return Math.min(RETRY_DELAY_MS * 2 ** (attempt - 1), MAX_RETRY_DELAY_MS)
}

/** Not signed in, or no longer: no retry can help before the page is reloaded. */
function isAuthRefusal(error: unknown): boolean {
  return error instanceof HttpErrorResponse && (error.status === 401 || error.status === 403)
}

/**
 * The one stream this browser keeps open on the server, and the only consumer of it.
 *
 * A browser allows 6 HTTP/1.1 connections per origin, shared by every tab, and the per-case
 * streams need that budget: everything that is not tied to one open case travels here instead
 * of on a stream of its own (#1616). Coday's TypeScript client had already landed on the same
 * shape — a single aggregated stream whose events carry what they are about.
 *
 * One tab per user owns the connection (a Web Lock held for its lifetime) and relays every
 * message to that user's other tabs over the BroadcastChannel of [TabCoordinatorService], so each
 * tab sees the same messages whether it reads the stream or the relay. Where Web Locks or
 * BroadcastChannel are missing, each tab opens its own — correct, just one connection per tab.
 */
@Injectable({ providedIn: 'root' })
export class GlobalStreamService {
  private readonly sse = inject(GlobalCaseEventSseService)
  private readonly tabs = inject(TabCoordinatorService)
  private readonly userState = inject(UserStateService)
  private readonly destroyRef = inject(DestroyRef)

  private readonly messages = new Subject<GlobalStreamMessage>()

  /** Notifiable events across every active case the user is related to. */
  readonly caseEvents$: Observable<CaseEvent> = this.messages.pipe(
    filter((message): message is Extract<GlobalStreamMessage, { kind: 'case-event' }> => message.kind === 'case-event'),
    map((message) => message.event)
  )

  /**
   * Fires whenever the stream (re)connects, in every tab — the owner's and the ones it relays to.
   * What was published while it was down was not kept, so consumers reload instead of waiting.
   */
  readonly connected$: Observable<void> = this.messages.pipe(
    filter((message) => message.kind === 'connected'),
    map(() => undefined)
  )

  /** Ids of the namespaces whose case list went stale and should be reloaded. */
  readonly caseListInvalidations$: Observable<string> = this.messages.pipe(
    filter(
      (message): message is Extract<GlobalStreamMessage, { kind: 'case-list-invalidated' }> =>
        message.kind === 'case-list-invalidated'
    ),
    map((message) => message.namespaceId)
  )

  private started = false

  /** Whether this tab reads the stream itself, in which case it ignores the relays. */
  private streamingLocally = false

  /**
   * The user this tab was signed in as when it started: it names the ownership lock and tags the
   * relays, so that a tab never hears another user's stream.
   */
  private userId: string | null = null

  /** Idempotent — called once at app startup, before the consumers subscribe. */
  start(): void {
    if (this.started) return
    this.started = true

    // Registered in every tab, the owner's included: BroadcastChannel never delivers to its own
    // sender, so the owner cannot receive its own relay back. A tab that reads the stream itself
    // ignores relays all the same, so that a tab which both reads one and hears another — a
    // failed election, a browser without Web Locks — never handles a message twice.
    //
    // A relay of another user's stream is dropped too: the stream is authenticated once, when it
    // opens, so a tab still open from before someone else signed in on this browser keeps reading
    // the previous user's events.
    this.tabs.onGlobalMessage((message, userId) => {
      if (this.streamingLocally || userId !== this.userId) return
      this.messages.next(message)
    })

    this.signedInUser()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((userId) => {
        this.userId = userId
        if (!this.tabs.coordinated) {
          this.connect()
          return
        }
        void this.tabs.whenStreamOwner(userId).then(() => this.connect())
      })
  }

  /**
   * The id of the signed-in user, once known. Not signed in — a 401 or a 403 — completes without
   * one, since the stream would be refused just the same; any other failure, a backend still
   * booting, is retried. Usually already loaded at startup, else asked for here.
   */
  private signedInUser(): Observable<string> {
    const known = this.userState.currentUser()?.id
    if (known) return of(known)
    return this.userState.loadMe().pipe(
      map((user) => user.id),
      filter((id): id is string => !!id),
      retry({
        delay: (error, attempt) => (isAuthRefusal(error) ? throwError(() => error) : timer(retryDelay(attempt))),
      }),
      catchError((error: unknown) => {
        console.warn('[GlobalStream] not signed in: no global stream', error)
        return EMPTY
      })
    )
  }

  /**
   * The relay goes out before the message is published here: a throw in a consumer would
   * otherwise drop that message for every other tab, with no sign of it there.
   *
   * The stream only errors once the browser has given up for good — a restarting backend, an
   * expired session — and this tab holds it for the whole browser, so it reconnects on its own
   * instead of leaving every tab deaf until someone reloads this one. The wait doubles on each
   * failure in a row, and starts over once a stream opens again: the 'connected' message every
   * opening begins with resets the count.
   */
  private connect(): void {
    this.streamingLocally = true
    this.sse
      .connect()
      .pipe(
        retry({
          resetOnSuccess: true,
          delay: (error, attempt) => {
            console.warn(`[GlobalStream] stream dropped, retrying (attempt ${attempt})`, error)
            return timer(retryDelay(attempt)).pipe(switchMap(() => this.stillSignedIn()))
          },
        }),
        takeUntilDestroyed(this.destroyRef)
      )
      .subscribe({
        next: (message) => {
          if (this.userId) this.tabs.broadcastGlobalMessage(message, this.userId)
          this.messages.next(message)
        },
        error: (error: unknown) => console.warn('[GlobalStream] stopped until the page is reloaded', error),
      })
  }

  /**
   * EventSource reports no status, so a refused stream does not say why. Asking who is signed in
   * does: a 401 or 403 stops the retries for good, instead of knocking every few seconds for as
   * long as the tab stays open, and so does a different user — this tab's lock and relays are
   * the previous one's, and the new user's tabs elect their own owner. Anything else, a backend
   * still down, lets the next attempt go ahead.
   */
  private stillSignedIn(): Observable<unknown> {
    return this.userState.loadMe().pipe(
      catchError((error: unknown) => (isAuthRefusal(error) ? throwError(() => error) : of(null))),
      map((user) => {
        if (user?.id && user.id !== this.userId) throw new Error('Signed in as another user since this tab started')
        return user
      })
    )
  }
}
