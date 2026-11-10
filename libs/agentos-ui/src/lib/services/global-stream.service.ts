import { DestroyRef, inject, Injectable } from '@angular/core'
import { takeUntilDestroyed } from '@angular/core/rxjs-interop'
import { Observable, Subject, timer } from 'rxjs'
import { filter, map, retry } from 'rxjs/operators'
import { CaseEvent } from '@whoz-oss/agentos-api-client'
import { GlobalCaseEventSseService, GlobalStreamMessage } from './global-case-event-sse.service'
import { TabCoordinatorService } from './tab-coordinator.service'

/** How long the owning tab waits before reopening a stream the browser gave up on. */
const RETRY_DELAY_MS = 5_000

/**
 * The one stream this browser keeps open on the server, and the only consumer of it.
 *
 * A browser allows 6 HTTP/1.1 connections per origin, shared by every tab, and the per-case
 * streams need that budget: everything that is not tied to one open case travels here instead
 * of on a stream of its own (#1616). Coday's TypeScript client had already landed on the same
 * shape — a single aggregated stream whose events carry what they are about.
 *
 * One tab owns the connection (a Web Lock held for its lifetime) and relays every message to
 * the others over the BroadcastChannel of [TabCoordinatorService], so each tab sees the same
 * messages whether it reads the stream or the relay. Where Web Locks or BroadcastChannel are
 * missing, each tab opens its own — correct, just one connection per tab.
 */
@Injectable({ providedIn: 'root' })
export class GlobalStreamService {
  private readonly sse = inject(GlobalCaseEventSseService)
  private readonly tabs = inject(TabCoordinatorService)
  private readonly destroyRef = inject(DestroyRef)

  private readonly messages = new Subject<GlobalStreamMessage>()

  /** Notifiable events across every active case the user is related to. */
  readonly caseEvents$: Observable<CaseEvent> = this.messages.pipe(
    filter((message): message is Extract<GlobalStreamMessage, { kind: 'case-event' }> => message.kind === 'case-event'),
    map((message) => message.event)
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

  /** Idempotent — called once at app startup, before the consumers subscribe. */
  start(): void {
    if (this.started) return
    this.started = true

    // Registered in every tab, the owner's included: BroadcastChannel never delivers to its own
    // sender, so the owner cannot receive its own relay back. A tab that reads the stream itself
    // ignores relays all the same, so that a tab which both reads one and hears another — a
    // failed election, a browser without Web Locks — never handles a message twice.
    this.tabs.onGlobalMessage((message) => {
      if (!this.streamingLocally) this.messages.next(message)
    })

    if (!this.tabs.coordinated) {
      this.connect()
      return
    }

    void this.tabs.whenStreamOwner().then(() => this.connect())
  }

  /**
   * The relay goes out before the message is published here: a throw in a consumer would
   * otherwise drop that message for every other tab, with no sign of it there.
   *
   * The stream only errors once the browser has given up for good — a restarting backend, an
   * expired session — and this tab holds it for the whole browser, so it reconnects on its own
   * instead of leaving every tab deaf until someone reloads this one.
   */
  private connect(): void {
    this.streamingLocally = true
    this.sse
      .connect()
      .pipe(
        retry({
          delay: (error, attempt) => {
            console.warn(`[GlobalStream] stream dropped, retrying (attempt ${attempt})`, error)
            return timer(RETRY_DELAY_MS)
          },
        }),
        takeUntilDestroyed(this.destroyRef)
      )
      .subscribe((message) => {
        this.tabs.broadcastGlobalMessage(message)
        this.messages.next(message)
      })
  }
}
