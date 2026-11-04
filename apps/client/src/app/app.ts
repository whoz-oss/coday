import { Component, ChangeDetectionStrategy, inject } from '@angular/core'
import { toSignal } from '@angular/core/rxjs-interop'
import {
  NavigationCancel,
  NavigationEnd,
  NavigationError,
  NavigationSkipped,
  NavigationStart,
  Router,
  RouterModule,
} from '@angular/router'
import { filter, map } from 'rxjs'

type Event_ = NavigationStart | NavigationEnd | NavigationCancel | NavigationError | NavigationSkipped

/** What the router is doing, as far as the user can tell. */
export type NavigationPhase = 'pending' | 'idle' | 'failed'

const toPhase = (event: Event_): NavigationPhase =>
  event instanceof NavigationStart ? 'pending' : event instanceof NavigationError ? 'failed' : 'idle'

@Component({
  imports: [RouterModule],
  selector: 'app-root',
  templateUrl: './app.html',
  changeDetection: ChangeDetectionStrategy.Eager,
  styleUrl: './app.scss',
})
export class App {
  protected title = 'client'

  /**
   * `pending` while a navigation is in flight (route guards, lazy chunks) and before the first one starts,
   * `failed` when it errored (e.g. a lazy chunk that cannot be loaded).
   * The router renders nothing for a first navigation that waits on a guard (e.g. AgentOS still booting) or
   * that failed, so without this the user would stare at a blank page.
   */
  protected readonly phase = toSignal(
    inject(Router).events.pipe(
      filter(
        (e): e is Event_ =>
          e instanceof NavigationStart ||
          e instanceof NavigationEnd ||
          e instanceof NavigationCancel ||
          e instanceof NavigationError ||
          e instanceof NavigationSkipped
      ),
      map(toPhase)
    ),
    { initialValue: 'pending' as NavigationPhase }
  )

  protected reload(): void {
    window.location.reload()
  }
}
