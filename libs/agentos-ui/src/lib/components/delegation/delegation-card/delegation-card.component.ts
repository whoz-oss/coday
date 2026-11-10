import {
  ChangeDetectionStrategy,
  Component,
  NgZone,
  computed,
  effect,
  inject,
  input,
  signal,
  untracked,
} from '@angular/core'
import { Router } from '@angular/router'
import { Configuration, CaseEvent } from '@whoz-oss/agentos-api-client'
import { DelegationPresentation } from '../models/delegation.models'
import { describeDelegationActivity } from '../models/delegation-activity.models'
import { DelegationResultComponent } from '../delegation-result/delegation-result.component'
@Component({
  selector: 'agentos-delegation-card',
  imports: [DelegationResultComponent],
  templateUrl: './delegation-card.component.html',
  styleUrl: './delegation-card.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DelegationCardComponent {
  readonly delegation = input.required<DelegationPresentation>()
  readonly namespaceId = input.required<string>()
  private readonly router = inject(Router)
  private readonly config = inject(Configuration)
  private readonly zone = inject(NgZone)
  protected readonly open = signal(false)
  protected readonly activity = signal<string[]>([])
  private readonly events = signal<CaseEvent[]>([])
  private readonly eventIds = new Set<string>()
  private eventsSubCaseId: string | null = null
  private source: EventSource | null = null
  protected readonly statusLabel = computed(() => this.delegation().status.replace('_', ' '))
  protected readonly lastEventSummary = computed(() => describeDelegationActivity(this.delegation(), this.events()))
  private readonly streamState = computed(
    () => ({ subCaseId: this.delegation().subCaseId, status: this.delegation().status, open: this.open() }),
    { equal: (a, b) => a.subCaseId === b.subCaseId && a.status === b.status && a.open === b.open }
  )

  constructor() {
    effect((onCleanup) => {
      const { subCaseId, status, open } = this.streamState()
      untracked(() => {
        if (this.eventsSubCaseId !== subCaseId) {
          this.eventsSubCaseId = subCaseId
          this.events.set([])
          this.eventIds.clear()
          this.activity.set([])
        }
        // Only an expanded card streams its sub-case. A browser allows 6 HTTP/1.1 connections
        // per origin, shared by every tab, and the per-case streams already need them: a parent
        // with a handful of parallel delegations would spend the lot on cards nobody opened,
        // and every request of every tab would then queue behind them (#1616).
        //
        // What a collapsed card loses is the live step label; its status, and the question or
        // confirmation a sub-case is blocked on, come from the parent's own stream.
        if (open && status === 'WORKING' && subCaseId) this.connect(subCaseId)
      })
      onCleanup(() => this.disconnect())
    })
  }

  protected toggle(): void {
    this.open.update((value) => !value)
  }
  protected openSubCase(): void {
    this.router.navigate(['/agentos/home'], {
      queryParams: { ns: this.namespaceId(), case: this.delegation().subCaseId },
    })
  }
  private connect(subCaseId: string): void {
    const source = this.zone.runOutsideAngular(
      () => new EventSource(`${this.config.basePath}/api/cases/${subCaseId}/events`)
    )
    this.source = source
    source.addEventListener('case-event', (message: MessageEvent<string>) => {
      if (this.source !== source) return
      try {
        const event = JSON.parse(message.data) as CaseEvent
        this.zone.run(() => this.addActivity(event))
      } catch {
        /* child diagnostics remain isolated */
      }
    })
  }
  private disconnect(): void {
    this.source?.close()
    this.source = null
  }
  private addActivity(event: CaseEvent): void {
    if (this.eventIds.has(event.id)) return
    this.eventIds.add(event.id)
    this.events.update((events) => [...events, event])
    const label = this.lastEventSummary()?.label
    if (label) this.activity.update((items) => (items.at(-1) === label ? items : [...items, label].slice(-6)))
  }
}
