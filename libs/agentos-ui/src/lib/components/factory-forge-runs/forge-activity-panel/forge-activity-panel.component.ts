import { ChangeDetectionStrategy, Component, computed, effect, inject, input, output, signal } from '@angular/core'
import {
  AgentRunningEvent,
  AgentSelectedEvent,
  IntentionGeneratedEvent,
  MessageEvent as CaseMessageEvent,
  ToolRequestEvent,
  ToolResponseEvent,
} from '@whoz-oss/agentos-api-client'
import { ForgeActivityService } from '../../../services/forge-activity.service'
import { FactoryApiService } from '../../../services/factory-api.service'

export interface FeedItem {
  id: string
  kind: 'message' | 'tool' | 'status' | 'intention'
  role?: string
  text?: string
  toolName?: string
  duration?: string | null
}

export interface TicketInfo {
  ticketId: string
  summary: string
  epicKey: string | null
  epicSummary: string | null
}

@Component({
  selector: 'agentos-forge-activity-panel',
  standalone: true,
  templateUrl: './forge-activity-panel.component.html',
  styleUrl: './forge-activity-panel.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'agentos-forge-activity-panel' },
})
export class ForgeActivityPanelComponent {
  readonly caseId = input.required<string>()
  readonly namespaceId = input.required<string>()
  readonly basePath = input.required<string>()
  /** Numéro de ticket Jira associé — optionnel, chargé dès le lancement. */
  readonly ticketId = input<string | null>(null)
  /** Émis quand l'utilisateur clique le bouton de fermeture. */
  readonly closeRequested = output<void>()
  readonly ticketResolved = output<{ epicKey: string | null; epicSummary: string | null }>()
  /** Émis au clic sur le header — permet au parent de naviguer vers la vue diagramme. */
  readonly storyRequested = output<void>()

  protected readonly activityService = inject(ForgeActivityService)
  private readonly factoryApi = inject(FactoryApiService)

  protected readonly ticketInfo = this.activityService.ticketInfo
  protected readonly ticketLoading = signal(false)

  constructor() {
    // Connexion SSE
    effect(() => {
      const caseId = this.caseId()
      const base = this.basePath()
      if (caseId && base) this.activityService.connect(caseId, base)
    })

    // Chargement Jira dès que ticketId est disponible
    effect(() => {
      const tid = this.ticketId()
      if (!tid) return
      // Ne pas recharger si on a déjà les infos pour CE ticket (survit aux navigations)
      if (this.ticketInfo()?.ticketId === tid) return
      if (this.ticketLoading()) return
      this.ticketLoading.set(true)
      this.factoryApi.getJiraTicket(tid).subscribe({
        next: (info) => {
          this.ticketInfo.set({
            ticketId: info.ticketId,
            summary: info.summary,
            epicKey: info.epicKey ?? null,
            epicSummary: info.epicSummary ?? null,
          })
          this.ticketLoading.set(false)
          this.ticketResolved.emit({ epicKey: info.epicKey ?? null, epicSummary: info.epicSummary ?? null })
        },
        error: () => this.ticketLoading.set(false),
      })
    })
  }

  protected readonly status = computed(() => this.activityService.caseStatus())
  protected readonly streamingText = computed(() => this.activityService.streamingText())
  protected readonly connected = computed(() => this.activityService.connected())

  protected readonly statusLabel = computed(() => {
    switch (this.status()) {
      case 'RUNNING':
        return 'En cours'
      case 'IDLE':
        return 'En attente'
      case 'PENDING':
        return 'Démarrage'
      case 'KILLED':
        return 'Arrêté'
      case 'ERROR':
        return 'Erreur'
      default:
        return this.status()
    }
  })

  protected readonly agentosLink = computed(() => `/agentos/home?ns=${this.namespaceId()}&case=${this.caseId()}`)

  protected readonly feedItems = computed((): FeedItem[] => {
    return this.activityService
      .events()
      .map((e): FeedItem | null => {
        if (e.type === 'MessageEvent') {
          const msg = e as CaseMessageEvent
          const text = (msg.content ?? [])
            .filter((c): c is { content: string } => 'content' in c)
            .map((c) => c.content)
            .join('')
          const truncated = text.length > 200 ? text.slice(0, 200) + '…' : text
          return {
            id: e.id,
            kind: 'message',
            role: msg.actor?.role === 'AGENT' ? (msg.actor?.displayName ?? 'Agent') : 'User',
            text: truncated,
          }
        }
        if (e.type === 'ToolRequestEvent') {
          const req = e as ToolRequestEvent
          return { id: e.id, kind: 'tool', toolName: req.toolName ?? 'tool', duration: null }
        }
        if (e.type === 'ToolResponseEvent') {
          const res = e as ToolResponseEvent
          const ms = res.durationMs
          const dur = ms != null ? (ms < 1000 ? ms + 'ms' : (ms / 1000).toFixed(1) + 's') : null
          return { id: e.id, kind: 'tool', toolName: res.toolName ?? 'tool', duration: dur }
        }
        if (e.type === 'IntentionGeneratedEvent') {
          const ie = e as IntentionGeneratedEvent
          return {
            id: e.id,
            kind: 'intention',
            text: ie.toolName + (ie.intention ? ': ' + ie.intention.slice(0, 100) : ''),
          }
        }
        if (e.type === 'AgentSelectedEvent' || e.type === 'AgentRunningEvent') {
          return {
            id: e.id,
            kind: 'status',
            text: 'Agent: ' + ((e as AgentRunningEvent | AgentSelectedEvent).agentName ?? ''),
          }
        }
        return null
      })
      .filter((item): item is FeedItem => item !== null)
  })
}
