import { ChangeDetectionStrategy, Component, computed, effect, inject, input, signal } from '@angular/core'
import { DatePipe } from '@angular/common'
import { toSignal } from '@angular/core/rxjs-interop'
import { MatButtonModule } from '@angular/material/button'
import { MatExpansionModule } from '@angular/material/expansion'
import { MatIconModule } from '@angular/material/icon'
import { ActivatedRoute } from '@angular/router'
import { map } from 'rxjs'
import { FactoryStore } from '../../core/factory.store'
import { TimelineBlock } from '../../core/models'
import { ShellState } from '../../core/shell-state'
import { DurationPipe, TokensPipe, UsdPipe } from '../../shared/pipes/format.pipes'
import { MetricChipComponent } from '../../shared/ui/metric-chip.component'
import { StatusChipComponent } from '../../shared/ui/status-chip.component'
import {
  ActionBarComponent,
  AgentQuestionAnswerIntent,
  CancelIntent,
  ReplyIntent,
  RetryIntent,
} from './action-bar.component'
import { AgentTimelineComponent } from './agent-timeline.component'
import { EventLogComponent } from './event-log.component'

@Component({
  selector: 'sf-session-page',
  imports: [
    DatePipe,
    MatButtonModule,
    MatExpansionModule,
    MatIconModule,
    StatusChipComponent,
    MetricChipComponent,
    ActionBarComponent,
    AgentTimelineComponent,
    EventLogComponent,
    UsdPipe,
    DurationPipe,
    TokensPipe,
  ],
  templateUrl: './session-page.component.html',
  styleUrl: './session-page.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SessionPageComponent {
  /** Paramètre de route :runId, éventuellement lié via withComponentInputBinding. */
  readonly runId = input<string>('')

  private readonly route = inject(ActivatedRoute)
  protected readonly store = inject(FactoryStore)
  private readonly shell = inject(ShellState)

  private readonly routeRunId = toSignal(this.route.paramMap.pipe(map((p) => p.get('runId') ?? '')), {
    initialValue: '',
  })

  protected readonly effectiveRunId = computed(() => this.runId() || this.routeRunId())
  protected readonly session = computed(() => this.store.session(this.effectiveRunId()))
  protected readonly selectedBlock = signal('')
  protected readonly selectedStepId = signal('')

  constructor() {
    effect(() => {
      const s = this.session()
      if (s?.phase.stepId && !this.selectedStepId()) this.selectedStepId.set(s.phase.stepId)
      this.shell.crumbs.set([
        { label: 'Sandboxes', link: '/sandboxes' },
        { label: s?.sandbox ?? '…', link: '/sandboxes', mono: true },
        { label: this.effectiveRunId(), mono: true },
      ])
    })
  }

  protected selectBlock(b: TimelineBlock): void {
    this.selectedBlock.set(b.label)
    this.selectedStepId.set(b.stepId ?? b.label)
  }

  protected caseLink(caseId: string): string {
    const params = new URLSearchParams()
    const namespaceId = this.currentNamespaceId()
    if (namespaceId) params.set('ns', namespaceId)
    params.set('case', caseId)
    return `/agentos/home?${params.toString()}`
  }

  protected currentNamespaceId(): string {
    const session = this.session()
    return (
      session?.agentQuestions?.find((question) => question.case?.namespaceId)?.case?.namespaceId ??
      session?.agentQuestionsError?.namespaceId ??
      ''
    )
  }

  protected onReply(intent: ReplyIntent): void {
    const id = this.effectiveRunId()
    if (!id) return
    this.store.replyInteraction(id, intent.interactionId, {
      actionId: intent.actionId,
      text: intent.text,
      expectedRevision: intent.expectedRevision,
    })
  }

  protected onAgentQuestionAnswered(intent: AgentQuestionAnswerIntent): void {
    const id = this.effectiveRunId()
    if (!id) return
    this.store.answerAgentQuestion(id, intent.question, intent.answer)
  }

  protected onRetry(intent: RetryIntent): void {
    const id = this.effectiveRunId()
    if (!id) return
    this.store.retry(id, {
      stepId: intent.stepId,
      expectedRevision: intent.expectedRevision,
      reasonCode: intent.reasonCode,
    })
  }

  protected onCancelAttempt(intent: CancelIntent): void {
    const id = this.effectiveRunId()
    if (!id) return
    this.store.cancelAttempt(id, intent.attemptId, {
      expectedRevision: intent.expectedRevision,
      reason: intent.reason,
    })
  }

  protected onContinueCost(): void {
    const id = this.effectiveRunId()
    if (!id) return
    this.store.continueCost(id)
  }

  protected onStopCost(): void {
    const id = this.effectiveRunId()
    if (!id) return
    this.store.stopCost(id)
  }
}
