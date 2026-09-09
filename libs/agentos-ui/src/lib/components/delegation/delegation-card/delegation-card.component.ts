import { ChangeDetectionStrategy, Component, DestroyRef, NgZone, computed, inject, input, signal } from '@angular/core'
import { Router } from '@angular/router'
import { Configuration, CaseEvent } from '@whoz-oss/agentos-api-client'
import { DelegationPresentation } from '../models/delegation.models'
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
  private readonly destroyRef = inject(DestroyRef)
  protected readonly open = signal(false)
  protected readonly activity = signal<string[]>([])
  private source: EventSource | null = null
  protected readonly statusLabel = computed(() => this.delegation().status.replace('_', ' '))
  protected toggle(): void {
    this.open.update((value) => !value)
    this.open() ? this.connect() : this.disconnect()
  }
  protected openSubCase(): void {
    this.router.navigate(['/agentos/home'], {
      queryParams: { ns: this.namespaceId(), case: this.delegation().subCaseId },
    })
  }
  private connect(): void {
    if (this.source) return
    this.source = this.zone.runOutsideAngular(
      () => new EventSource(`${this.config.basePath}/api/cases/${this.delegation().subCaseId}/events`)
    )
    this.source.addEventListener('case-event', (message: MessageEvent<string>) => {
      try {
        const event = JSON.parse(message.data) as CaseEvent
        this.zone.run(() => this.addActivity(event))
      } catch {
        /* child diagnostics remain isolated */
      }
    })
    this.destroyRef.onDestroy(() => this.disconnect())
  }
  private disconnect(): void {
    this.source?.close()
    this.source = null
  }
  private addActivity(event: CaseEvent): void {
    const label =
      event.type === 'MessageEvent'
        ? 'Agent produced a message'
        : event.type === 'ToolRequestEvent'
          ? `Tool started: ${event.toolName}`
          : event.type === 'ToolResponseEvent'
            ? `Tool completed: ${event.toolName}`
            : event.type === 'QuestionEvent'
              ? 'Waiting for input'
              : event.type === 'AgentFinishedEvent'
                ? 'Agent finished'
                : null
    if (label) this.activity.update((items) => (items.includes(label) ? items : [...items, label].slice(-6)))
  }
}
