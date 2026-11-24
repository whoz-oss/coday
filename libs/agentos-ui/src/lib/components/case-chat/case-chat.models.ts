import { SafeHtml } from '@angular/platform-browser'
import {
  AnswerEvent,
  EnrichmentPhaseTrace,
  MessageEvent as CaseMessageEvent,
  QuestionEvent,
  ToolResponseEvent,
} from '@whoz-oss/agentos-api-client'
import { DelegationPresentation } from '../delegation/models/delegation.models'
import { TurnMetrics } from './turn-metrics'

export interface ToolCall {
  requestId: string
  toolName: string
  args: string | null
  /** ISO timestamp from the ToolRequestEvent. */
  timestamp?: string
  /** undefined = pending, defined = done */
  response?: ToolResponseEvent
  /** Enrichment phase traces from multi-step parameter generation (null when no enrichment). */
  enrichmentPhases?: EnrichmentPhaseTrace[] | null
}

/** A technical event, displayed only when the technical logs preference is enabled. */
export interface TechnicalItem {
  label: string
  detail?: string
  /** ISO timestamp of the source event. */
  timestamp?: string
}

export interface ExecutionNotice {
  severity: 'warning' | 'error' | 'terminal'
  title: string
  detail?: string
}

export type TimelineItem =
  | {
      kind: 'message'
      event: CaseMessageEvent
      html: SafeHtml
      isFirstInGroup: boolean
      isLastInGroup: boolean
      /**
       * What the agent turn this message closes cost, when the turn reported any usage.
       *
       * Set on the last agent message before the turn's `AgentFinishedEvent`, which is where the
       * reader looks for it: the answer is on screen, the question "what did that cost" is about
       * that answer.
       */
      metrics?: TurnMetrics
    }
  | { kind: 'tool'; call: ToolCall }
  /** A run of consecutive tool calls, collapsed into one card (see `groupToolCalls`). */
  | { kind: 'toolGroup'; groupId: string; calls: ToolCall[] }
  | { kind: 'streaming' }
  | { kind: 'notice'; notice: ExecutionNotice; eventId: string }
  | { kind: 'technical'; item: TechnicalItem; eventId: string }
  | { kind: 'question'; event: QuestionEvent; answer?: AnswerEvent }
  | { kind: 'delegation'; delegation: DelegationPresentation }
