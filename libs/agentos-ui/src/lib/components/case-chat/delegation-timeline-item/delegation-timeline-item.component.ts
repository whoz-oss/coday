import { ChangeDetectionStrategy, Component, OnDestroy, computed, inject, input, signal } from '@angular/core'
import { Router } from '@angular/router'
import { CaseEvent, CaseEventSseService } from '@whoz-oss/agentos-api-client'
import { Subscription } from 'rxjs'
import { DelegationResultComponent } from '../delegation-result/delegation-result.component'
import { projectDelegationActivity } from './delegation-activity.projection'

export interface DelegationTimelineItemData {
  delegationId: string
  subCaseId: string
  agentName: string
  task: string
  resumed: boolean
  status: 'running' | 'success' | 'waiting_user' | 'error'
  outcome?: string
  errorType?: string
  result?: Record<string, unknown>
}

@Component({
  selector: 'agentos-delegation-timeline-item',
  imports: [DelegationResultComponent],
  templateUrl: './delegation-timeline-item.component.html',
  styleUrl: './delegation-timeline-item.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DelegationTimelineItemComponent implements OnDestroy {
  private readonly router = inject(Router)
  private readonly sse = inject(CaseEventSseService)
  private childSubscription: Subscription | null = null

  readonly delegation = input.required<DelegationTimelineItemData>()
  readonly namespaceId = input.required<string>()
  readonly showTechnical = input(false)
  protected readonly expanded = signal(false)
  protected readonly loadingActivity = signal(false)
  protected readonly activityError = signal<string | null>(null)
  private readonly childEvents = signal<CaseEvent[]>([])
  protected readonly hasStructuredResult = computed(() => this.delegation().result !== undefined)
  protected readonly activity = computed(() =>
    projectDelegationActivity(this.childEvents(), { technical: this.showTechnical() })
  )

  protected toggle(): void {
    this.expanded.update((expanded) => !expanded)
    if (this.expanded()) this.connectChildTimeline()
    else this.disconnectChildTimeline()
  }

  protected openSubCase(): void {
    void this.router.navigate(['/agentos/home'], {
      queryParams: { ns: this.namespaceId(), case: this.delegation().subCaseId },
    })
  }

  protected resultOutput(): string | null {
    const result = this.delegation().result
    return result ? JSON.stringify([result]) : null
  }

  ngOnDestroy(): void {
    this.disconnectChildTimeline()
  }

  private connectChildTimeline(): void {
    if (this.childSubscription) return
    this.loadingActivity.set(true)
    this.activityError.set(null)
    this.childEvents.set([])
    this.childSubscription = this.sse.connect(this.delegation().subCaseId).subscribe({
      next: (event) => {
        this.loadingActivity.set(false)
        this.childEvents.update((events) => (events.some(({ id }) => id === event.id) ? events : [...events, event]))
      },
      error: () => {
        this.loadingActivity.set(false)
        this.activityError.set('Unable to load sub-case activity.')
        this.childSubscription = null
      },
      complete: () => {
        this.loadingActivity.set(false)
        this.childSubscription = null
      },
    })
  }

  private disconnectChildTimeline(): void {
    this.childSubscription?.unsubscribe()
    this.childSubscription = null
    this.loadingActivity.set(false)
  }
}
