import { ChangeDetectionStrategy, Component, computed, effect, inject, OnDestroy, signal } from '@angular/core'
import { toSignal } from '@angular/core/rxjs-interop'
import { ActivatedRoute, Router } from '@angular/router'
import { switchMap, map } from 'rxjs'
import { CaseControllerService, CaseStatusEnum, Configuration } from '@whoz-oss/agentos-api-client'
import { EpicCockpitComponent } from './epic-cockpit/epic-cockpit.component'
import { ForgeRibbonComponent } from './forge-ribbon/forge-ribbon.component'
import { StoryRunComponent } from './story-run/story-run.component'
import { ForgeActivityPanelComponent } from './forge-activity-panel/forge-activity-panel.component'
import { FactoryApiService, FactoryForgeRun, WorkstreamEntry } from '../../services/factory-api.service'
import { ForgeActivityService } from '../../services/forge-activity.service'
import {
  DecisionKind,
  EpicClosure,
  EpicRun,
  RunState,
  StepKey,
  StoryRun,
  US_STEPS,
  Workstream,
  headOf,
  plural,
  stateOf,
  toneOf,
} from './forge.model'
import { FactoryForgeStateService } from '../../services/factory-forge-state.service'

type Screen = 'streams' | 'workstream' | 'epic' | 'story'
type Tab = 'epics' | 'docs'

/**
 * Coquille de la console Forge : barre d'application, fil d'ariane,
 * et les quatre écrans — Workstreams → Workstream → Epic → US.
 */
@Component({
  selector: 'agentos-factory-forge-runs',
  imports: [ForgeRibbonComponent, EpicCockpitComponent, StoryRunComponent, ForgeActivityPanelComponent],
  templateUrl: './factory-forge-runs.component.html',
  styleUrl: './factory-forge-runs.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'agentos-factory-forge-runs' },
})
export class FactoryForgeRunsComponent implements OnDestroy {
  /** Service réel — gardé injecté, sera activé quand le backend le supporte. */
  protected readonly state = inject(FactoryForgeStateService)

  private readonly route = inject(ActivatedRoute)
  private readonly router = inject(Router)
  private readonly caseController = inject(CaseControllerService)
  private readonly factoryApi = inject(FactoryApiService)
  private readonly config = inject(Configuration)
  protected readonly activityService = inject(ForgeActivityService)

  private readonly queryParamMap = toSignal(this.route.queryParamMap, {
    initialValue: this.route.snapshot.queryParamMap,
  })

  /** Namespace courant lu depuis ?ns= — même source que FactoryRunsComponent. */
  readonly currentNamespaceId = computed(() => this.queryParamMap().get('ns') ?? null)

  readonly agentosBasePath = computed(() => this.config.basePath ?? '')

  // Workstreams chargés depuis le serveur Factory
  readonly workstreamsSignal = signal<Workstream[]>([])
  readonly workstreamsLoading = signal(false)
  readonly workstreamsError = signal<string | null>(null)
  /** Entrées brutes conservées pour un accès direct au slug sans parsing. */
  protected workstreamEntries: WorkstreamEntry[] = []

  // Getter compatible avec le reste du code qui utilise this.workstreams
  get workstreams(): readonly Workstream[] {
    return this.workstreamsSignal()
  }

  readonly screen = computed((): Screen => {
    if (this.queryParamMap().get('story')) return 'story'
    if (this.queryParamMap().get('epic')) return 'epic'
    if (this.queryParamMap().get('ws')) return 'workstream'
    return 'streams'
  })
  readonly tab = signal<Tab>('epics')
  readonly wsSlug = computed(() => this.queryParamMap().get('ws') ?? null)
  readonly wsIndex = computed(() => {
    const slug = this.wsSlug()
    if (!slug) return 0
    const idx = this.workstreamEntries.findIndex((e) => e.slug === slug)
    return idx >= 0 ? idx : 0
  })
  // stepKey reste local (pas dans l'URL)
  readonly stepKey = signal<StepKey | null>(null)

  /** Décisions prises en session, par US. La donnée source n'est pas mutée. */
  private readonly overridesByStory = signal<Record<string, Partial<Record<StepKey, RunState>>>>({})
  private readonly answeredStories = signal<readonly string[]>([])

  /** Formulaire de création de workstream. */
  readonly showNewWorkstreamForm = signal(false)
  readonly newWsName = signal('')
  readonly newWsSlug = signal('')
  readonly newWsStatus = signal('discovery')
  readonly creatingWorkstream = signal(false)
  readonly newWsError = signal<string | null>(null)

  /** Dialog de lancement d'un run Forge. */
  readonly showLaunchPanel = signal(false)
  readonly launchTicketId = signal('')
  readonly launching = signal(false)
  readonly launchWorkstreamSlug = signal<string>('')

  /** caseId du run en cours — en mémoire uniquement, réinitialisé à chaque chargement. */
  readonly activeCaseId = signal<string | null>(null)
  /** ticketId (Story) associé au run en cours. */
  readonly activeTicketId = signal<string | null>(null)
  /** epicKey Jira du ticket parent — résolu après chargement Jira. */
  readonly activeEpicKey = signal<string | null>(null)
  readonly activeEpicSummary = signal<string | null>(null)

  /** StoryRun synthétique quand le ledger n'existe pas encore mais qu'un run est actif. */
  readonly activeStoryRun = computed((): StoryRun | null => {
    const ticketId = this.activeTicketId()
    if (!ticketId || this.epicRunsForWorkstream().length > 0) return null
    const info = this.activityService.ticketInfo()
    return {
      key: ticketId,
      title: info?.summary ?? ticketId,
      ticket: ticketId,
      pr: '—',
      updatedAt: '?',
      head: 'g1',
      states: {
        discovery: 'na',
        grooming: 'done',
        g1: 'running',
        spec: 'pending',
        g2: 'pending',
        code: 'pending',
        g3: 'pending',
        deploy: 'na',
        g4: 'pending',
        merge: 'pending',
      },
    }
  })
  readonly workstream = computed(() => {
    const list = this.workstreamsSignal()
    if (!list.length) return undefined
    const idx = Math.min(this.wsIndex(), list.length - 1)
    return list[idx]
  })
  /** Forge cockpit rows backed only by the independent JSONL run APIs. */
  readonly epicRunsForWorkstream = computed((): EpicRun[] => {
    const all = this.state.runs().map((run) => this.mapForgeRunToEpicRun(run))

    // Lire les signals ICI pour qu'Angular les enregistre comme dépendances
    const activeCaseEpicKey = this.activeEpicKey()
    const activeTicketId = this.activeTicketId()
    const activeCaseId = this.activeCaseId()

    // Priorité d'affichage :
    //   0 — en cours (le run actif correspond à cet epic)
    //   1 — commencé (au moins une étape non-pending, non-na, non-done)
    //   2 — pending (aucune activité)
    //   3 — passé / terminé
    const priority = (e: EpicRun): number => {
      // Un case actif sans epic résolu encore : matcher sur le ticketId seul
      const isActive =
        !!activeCaseId &&
        (e.key === activeCaseEpicKey || e.key === activeTicketId || e.stories.some((s) => s.key === activeTicketId))
      if (isActive) return 0
      if (e.closure === 'passed') return 3
      const hasStarted = e.stories.some((s) => Object.values(s.states).some((v) => v && v !== 'pending' && v !== 'na'))
      return hasStarted ? 1 : 2
    }

    return [...all].sort((a, b) => priority(a) - priority(b))
  })

  /** Clé de l'epic sélectionné — lue depuis l'URL, stable même quand le tri change. */
  readonly epicKey = computed(() => this.queryParamMap().get('epic') ?? null)

  /** Clé de la story sélectionnée — lue depuis l'URL. */
  readonly storyKey = computed(() => this.queryParamMap().get('story') ?? null)

  readonly epic = computed(() => {
    const key = this.epicKey()
    const runs = this.epicRunsForWorkstream()
    if (!key) return runs[0] ?? undefined
    return runs.find((e) => e.key === key) ?? runs[0] ?? undefined
  })
  readonly story = computed(() => {
    const e = this.epic()
    if (!e) return this.activeStoryRun() ?? undefined
    const key = this.storyKey()
    return (key ? e.stories.find((s) => s.key === key) : null) ?? e.stories[0]
  })

  /** Run Forge JSONL correspondant à l'épic sélectionné. */
  readonly currentForgeRun = computed(() => {
    const key = this.epicKey()
    return this.state.runs().find((r) => r.workItem.id === key) ?? null
  })

  readonly forgeRunIdForEpic = computed(() => this.currentForgeRun()?.runId ?? null)

  readonly activeEpicIndex = computed(() => {
    const ticketId = this.activeTicketId()
    const epicKey = this.activeEpicKey()
    if (!this.activeCaseId() || (!ticketId && !epicKey)) return -1
    return this.epicRunsForWorkstream().findIndex(
      (e) => e.key === epicKey || e.key === ticketId || e.stories.some((s) => s.key === ticketId)
    )
  })

  /**
   * Vrai si le run actif n'est PAS déjà représenté dans la liste des epics.
   * Sert à afficher la row "en cours" dans la vue workstream.
   */
  readonly activeRunHasNoEpicRow = computed(() => {
    const ticketId = this.activeTicketId()
    const epicKey = this.activeEpicKey()
    if (!this.activeCaseId() || (!ticketId && !epicKey)) return false
    const epics = this.epicRunsForWorkstream()
    if (!epics.length) return true
    return !epics.some((e) => e.key === ticketId || e.key === epicKey || e.stories.some((s) => s.key === ticketId))
  })

  /**
   * Vrai uniquement si le case actif concerne l'epic actuellement affiché.
   */
  readonly isActiveCaseForCurrentEpic = computed(() => {
    const epicRun = this.epic()
    if (!epicRun) return !!this.activeCaseId()
    const ticketId = this.activeTicketId()
    const epicKey = this.activeEpicKey()
    if (!ticketId && !epicKey) return false
    if (ticketId && epicRun.stories.some((s) => s.key === ticketId)) return true
    if (epicKey && epicKey === epicRun.key) return true
    if (ticketId && ticketId === epicRun.key) return true
    return false
  })

  readonly isG1WaitingHuman = computed(() => {
    const run = this.currentForgeRun()
    const g1 = run?.gates.find((g) => g.gate === 'G1')
    return g1?.status === 'waiting_human'
  })

  readonly g1EvidenceSetHash = computed(() => {
    const run = this.currentForgeRun()
    const g1 = run?.gates.find((g) => g.gate === 'G1') as Record<string, unknown> | undefined
    return (g1?.['evidenceSetHash'] as string | undefined) ?? null
  })

  readonly overrides = computed(() => this.overridesByStory())
  readonly answered = computed(() => this.answeredStories())
  readonly storyOverrides = computed(() => this.overridesByStory()[this.story()?.key ?? ''] ?? {})
  readonly storyAnswered = computed(() => this.answeredStories().includes(this.story()?.key ?? ''))

  /** Pas dans un workstream si la liste est vide ou si on est sur l'écran streams. */
  readonly inWorkstream = computed(() => this.screen() !== 'streams' && this.workstreamsSignal().length > 0)
  readonly inEpic = computed(() => this.screen() === 'epic' || this.screen() === 'story')

  readonly docCount = computed(() => this.workstream()?.docs.reduce((n, g) => n + g.items.length, 0) ?? 0)

  /** Cartes de l'écran 00 : un sujet, ce qui l'attend, ses volumes. */
  readonly streamCards = computed(() => {
    const epicRuns = this.epicRunsForWorkstream()
    const stories = epicRuns.flatMap((e) => e.stories)

    return this.workstreams.map((ws, index) => {
      const overrides = this.overridesByStory()
      const blocked = stories.filter((s) =>
        US_STEPS.some((step) => {
          const value = stateOf(s, step.key, overrides[s.key])
          return value === 'blocked' || value === 'failed'
        })
      ).length
      const waiting = stories.filter(
        (s) =>
          (!!s.query && !this.answeredStories().includes(s.key)) ||
          US_STEPS.some((step) => stateOf(s, step.key, overrides[s.key]) === 'human')
      ).length

      const flags = []
      if (blocked) flags.push({ label: plural(blocked, 'US bloquée', 'US bloquées'), ...this.tone('blocked') })
      if (waiting) flags.push({ label: `${waiting} attente humaine`, ...this.tone('human') })
      if (!flags.length) flags.push({ label: 'rien en attente', ...this.tone('done') })

      const docs = ws.docs.reduce((n, g) => n + g.items.length, 0)

      return {
        index,
        name: ws.name,
        subject: ws.subject,
        lead: ws.lead,
        branch: ws.branch,
        flags,
        counts: [
          plural(epicRuns.length + (this.activeRunHasNoEpicRow() ? 1 : 0), 'Epic', 'Epics'),
          `${stories.length + (this.activeRunHasNoEpicRow() ? 1 : 0)} US`,
          plural(docs, 'document', 'documents'),
        ].join(' · '),
      }
    })
  })

  /** Barre condensée d'une Epic : mêmes colonnes que le cockpit. */
  readonly epicRows = computed(() =>
    this.epicRunsForWorkstream().map((epicRun, index) => {
      const overrides = this.overridesByStory()
      const tally: Partial<Record<StepKey, number>> = {}
      for (const story of epicRun.stories) {
        const h = headOf(story, overrides[story.key])
        tally[h] = (tally[h] ?? 0) + 1
      }

      const proven = epicRun.stories.filter((s) => stateOf(s, 'g3', overrides[s.key]) === 'done').length
      const blocked = epicRun.stories.filter((s) =>
        US_STEPS.some((step) => {
          const value = stateOf(s, step.key, overrides[s.key])
          return value === 'blocked' || value === 'failed'
        })
      ).length

      const flags = [
        { label: `${proven}/${epicRun.stories.length} G3 passées`, ...this.tone(proven ? 'done' : 'pending') },
      ]
      if (blocked) flags.push({ label: plural(blocked, 'US bloquée', 'US bloquées'), ...this.tone('blocked') })

      return { index, key: epicRun.key, title: epicRun.title, counts: tally, flags }
    })
  )

  private tone(state: RunState): { bg: string; ink: string } {
    const { bg, ink } = toneOf(state)
    return { bg, ink }
  }

  /* ── Mapping FactoryForgeRun → EpicRun ──────────────────────── */

  private mapForgeRunToEpicRun(run: FactoryForgeRun): EpicRun {
    const g1 = run.gates.find((g) => g.gate === 'G1')
    const g3Summary = run.stories.map((s) => {
      const latest = s.oracleCampaigns.at(-1)
      return latest?.status ?? 'not_started'
    })

    const closure: EpicClosure =
      run.status === 'approved'
        ? g3Summary.every((s) => s === 'passed')
          ? 'passed'
          : 'pending'
        : run.status === 'waiting_human'
          ? 'pending'
          : run.status === 'blocked'
            ? 'blocked'
            : 'pending'

    // G1 est un gate Epic — son statut est transmis à chaque story du run.
    const g1Status = g1?.status ?? 'not_started'

    return {
      key: run.workItem.id,
      title: `${run.workItem.id} — ${run.workflow}`,
      closure,
      closureSummary: `G1: ${g1Status} · ${run.stories.length} US`,
      note: `Run ${run.runId.slice(0, 8)} · démarré ${run.startedAt ? new Date(run.startedAt).toLocaleDateString() : '?'}`,
      stories: run.stories.map((story) => this.mapStoryToStoryRun(story, g1Status)),
    }
  }

  private mapStoryToStoryRun(story: FactoryForgeRun['stories'][number], epicG1Status: string): StoryRun {
    const latestExecution = story.executions.at(-1) as Record<string, unknown> | undefined
    const latestEdit = story.edits.at(-1) as Record<string, unknown> | undefined
    const latestCampaign = story.oracleCampaigns.at(-1)

    // G1 est un gate Epic, pas Story : son état vient du run parent.
    const g1State: RunState =
      epicG1Status === 'approved' ? 'done' : epicG1Status === 'waiting_human' ? 'human' : 'pending'

    const specState: RunState =
      latestExecution?.['status'] === 'finished'
        ? 'done'
        : latestExecution?.['status'] === 'failed'
          ? 'failed'
          : latestExecution
            ? 'running'
            : 'pending'
    const codeState: RunState =
      latestEdit?.['status'] === 'finished'
        ? 'done'
        : latestEdit?.['status'] === 'failed'
          ? 'failed'
          : latestEdit
            ? 'running'
            : 'pending'
    const g3State: RunState =
      latestCampaign?.status === 'passed'
        ? 'done'
        : latestCampaign?.status === 'blocked'
          ? 'blocked'
          : latestCampaign?.status === 'failed'
            ? 'failed'
            : latestCampaign
              ? 'running'
              : 'pending'

    const analysisValidation = latestExecution?.['analysisValidation'] as { status?: string } | undefined

    // G2 ne peut être validé que si G1 est approuvé.
    const g2State: RunState =
      g1State !== 'done' ? 'pending' : analysisValidation?.status === 'valid' ? 'done' : 'pending'

    return {
      key: story.workItem.id,
      title: story.workItem.id,
      ticket: story.workItem.id,
      pr: '—',
      updatedAt:
        (latestExecution?.['observedAt'] as string | undefined) ??
        (latestExecution?.['startedAt'] as string | undefined) ??
        '?',
      // Tête de la story : bloquée sur G1 tant que le gate Epic n'est pas approuvé.
      head:
        epicG1Status !== 'approved'
          ? 'g1'
          : latestCampaign
            ? 'g3'
            : latestEdit
              ? 'code'
              : latestExecution
                ? 'spec'
                : 'g1',
      states: {
        discovery: 'na',
        grooming: 'done',
        g1: g1State,
        spec: specState,
        g2: g2State,
        code: codeState,
        g3: g3State,
        deploy: 'na',
        g4: 'pending',
        merge: 'pending',
      },
    }
  }

  /* ── Initialisation ──────────────────────────────────────────── */

  constructor() {
    // Recharge workstreams et runs Forge à chaque changement de namespace.
    // L'effect se déclenche aussi à l'initialisation, remplaçant ngOnInit.
    effect(() => {
      const namespaceId = this.currentNamespaceId()
      if (namespaceId) {
        this.state.load(namespaceId)
        this.state.startPolling(namespaceId)
        this.loadWorkstreams()
        this.loadActiveRun(namespaceId)
      }
    })
  }

  private loadActiveRun(namespaceId: string): void {
    this.factoryApi.getActiveRun(namespaceId).subscribe({
      next: (run) => {
        this.activeCaseId.set(run?.caseId ?? null)
        this.activeTicketId.set(run?.ticketId ?? null)
        if (run?.ticketId) this.resolveEpicFromJira(run.ticketId)
      },
      error: () => {
        /* silencieux */
      },
    })
  }

  /**
   * Appelle l'API Jira pour résoudre l'epic parent du ticket actif.
   * Alimente activeEpicKey / activeEpicSummary indépendamment du panneau d'activité.
   * Silencieux en cas d'erreur (Jira indisponible, ticket sans epic).
   */
  private resolveEpicFromJira(ticketId: string): void {
    if (this.activityService.ticketInfo()?.ticketId === ticketId) {
      const info = this.activityService.ticketInfo()!
      this.activeEpicKey.set(info.epicKey)
      this.activeEpicSummary.set(info.epicSummary)
      return
    }
    this.factoryApi.getJiraTicket(ticketId).subscribe({
      next: (info) => {
        this.activeEpicKey.set(info.epicKey ?? null)
        this.activeEpicSummary.set(info.epicSummary ?? null)
        this.activityService.ticketInfo.set({
          ticketId: info.ticketId,
          summary: info.summary,
          epicKey: info.epicKey ?? null,
          epicSummary: info.epicSummary ?? null,
        })
      },
      error: () => {
        /* Jira indisponible ou ticket sans epic — silencieux */
      },
    })
  }

  closeActiveRun(): void {
    const namespaceId = this.currentNamespaceId()
    if (!namespaceId) return
    this.factoryApi.clearActiveRun(namespaceId).subscribe({
      next: () => {
        this.activeCaseId.set(null)
        this.activeTicketId.set(null)
        this.activeEpicKey.set(null)
        this.activeEpicSummary.set(null)
      },
    })
  }

  onTicketResolved(info: { epicKey: string | null; epicSummary: string | null }): void {
    this.activeEpicKey.set(info.epicKey)
    this.activeEpicSummary.set(info.epicSummary)
  }

  private loadWorkstreams(): void {
    const namespaceId = this.currentNamespaceId()
    if (!namespaceId) {
      this.workstreamsLoading.set(false)
      return
    }
    this.workstreamsLoading.set(true)
    this.factoryApi.listWorkstreams(namespaceId).subscribe({
      next: (entries) => {
        this.workstreamEntries = entries
        this.workstreamsSignal.set(
          entries.map((e) => ({
            name: e.name,
            subject: e.status,
            lead: '',
            branch: `ws/${e.slug}`,
            preview: '',
            epics: [],
            docs: [],
          }))
        )
        this.workstreamsLoading.set(false)
      },
      error: () => {
        this.workstreamsError.set('Impossible de charger les workstreams. Le serveur Factory est-il démarré ?')
        this.workstreamsLoading.set(false)
      },
    })
  }

  /* ── Navigation ─────────────────────────────────────────────────── */

  goStreams(): void {
    this.router.navigate([], {
      relativeTo: this.route,
      queryParams: { ws: null, epic: null, story: null },
      queryParamsHandling: 'merge',
    })
  }

  openWorkstream(index: number): void {
    const entry = this.workstreamEntries[index]
    if (!entry) return
    this.tab.set('epics')
    this.router.navigate([], {
      relativeTo: this.route,
      queryParams: { ws: entry.slug, epic: null, story: null },
      queryParamsHandling: 'merge',
    })
  }

  goWorkstream(): void {
    this.router.navigate([], {
      relativeTo: this.route,
      queryParams: { epic: null, story: null },
      queryParamsHandling: 'merge',
    })
  }

  openEpic(index: number): void {
    const run = this.epicRunsForWorkstream()[index]
    if (!run) return
    this.router.navigate([], {
      relativeTo: this.route,
      queryParams: { epic: run.key, story: null },
      queryParamsHandling: 'merge',
    })
  }

  goEpic(): void {
    this.router.navigate([], {
      relativeTo: this.route,
      queryParams: { story: null },
      queryParamsHandling: 'merge',
    })
  }

  /** Lien profond : l'US s'ouvre directement sur l'étape cliquée. */
  openStory(event: { story: string; step: StepKey }): void {
    this.stepKey.set(event.step)
    this.router.navigate([], {
      relativeTo: this.route,
      queryParams: { story: event.story },
      queryParamsHandling: 'merge',
    })
  }

  /** Navigation vers l'epic actif depuis la row fantôme. */
  openActiveEpic(): void {
    const epicKey = this.activeEpicKey()
    const ticketId = this.activeTicketId()

    // Cas 1 : epicKey résolu via Jira — naviguer directement
    if (epicKey) {
      this.router.navigate([], {
        relativeTo: this.route,
        queryParams: { epic: epicKey, story: null },
        queryParamsHandling: 'merge',
      })
      return
    }

    // Cas 2 : Jira pas encore répondu — chercher l'epic qui contient ce ticketId
    if (ticketId) {
      const epics = this.epicRunsForWorkstream()
      const parentEpic = epics.find((e) => e.key === ticketId || e.stories.some((s) => s.key === ticketId))
      const key = parentEpic?.key ?? ticketId
      this.router.navigate([], {
        relativeTo: this.route,
        queryParams: { epic: key, story: null },
        queryParamsHandling: 'merge',
      })
    }
  }

  setTab(tab: Tab): void {
    this.tab.set(tab)
  }

  protected readonly approvingG1 = signal(false)
  protected readonly g1ApprovalError = signal<string | null>(null)

  approveG1(): void {
    const runId = this.forgeRunIdForEpic()
    const hash = this.g1EvidenceSetHash()
    const nsId = this.currentNamespaceId()
    if (!runId || !hash || !nsId || this.approvingG1()) return
    this.approvingG1.set(true)
    this.g1ApprovalError.set(null)
    this.factoryApi.approveG1(runId, hash, nsId).subscribe({
      next: () => {
        this.approvingG1.set(false)
        this.onG1Approved()
      },
      error: (err: Error) => {
        this.approvingG1.set(false)
        this.g1ApprovalError.set(`Erreur : ${err.message}`)
      },
    })
  }

  ngOnDestroy(): void {
    this.state.stopPolling()
  }

  onRefresh(): void {
    const namespaceId = this.currentNamespaceId()
    if (namespaceId) this.state.load(namespaceId)
  }

  /** Appelé par epic-cockpit après une approbation G1 réussie. Recharge les runs pour refléter le nouveau statut. */
  onG1Approved(): void {
    const namespaceId = this.currentNamespaceId()
    if (namespaceId) this.state.load(namespaceId)
  }

  onDecided({ story, kind }: { story: string; kind: DecisionKind }): void {
    if (kind === 'answer') {
      this.answeredStories.update((list) => (list.includes(story) ? list : [...list, story]))
      this.patch(story, { code: 'running' })
      return
    }

    const patches: Record<Exclude<DecisionKind, 'answer'>, Partial<Record<StepKey, RunState>>> = {
      approve: { g1: 'done', spec: 'running' },
      reject: { g1: 'failed' },
      retry: { g3: 'running', code: 'running' },
      ignore: { g3: 'done' },
      confirm: { g3: 'failed' },
    }
    this.patch(story, patches[kind])
  }

  private patch(story: string, patch: Partial<Record<StepKey, RunState>>): void {
    this.overridesByStory.update((current) => ({
      ...current,
      [story]: { ...(current[story] ?? {}), ...patch },
    }))
  }

  /* ── Création de workstream ────────────────────────────────────── */

  openNewWorkstreamForm(): void {
    this.showNewWorkstreamForm.set(true)
  }

  closeNewWorkstreamForm(): void {
    this.newWsName.set('')
    this.newWsSlug.set('')
    this.newWsStatus.set('discovery')
    this.creatingWorkstream.set(false)
    this.newWsError.set(null)
    this.showNewWorkstreamForm.set(false)
  }

  onNameInput(event: Event): void {
    const name = (event.target as HTMLInputElement).value
    this.newWsName.set(name)
    const slug = name
      .toLowerCase()
      .replace(/[^a-z0-9]+/g, '-')
      .replace(/^-|-$/g, '')
    this.newWsSlug.set(slug)
  }

  onSlugInput(event: Event): void {
    this.newWsSlug.set((event.target as HTMLInputElement).value)
  }

  submitNewWorkstream(): void {
    const slug = this.newWsSlug()
    const name = this.newWsName()
    const status = this.newWsStatus()
    const namespaceId = this.currentNamespaceId()

    const slugRegex = /^[a-z0-9]+(?:-[a-z0-9]+)*$/
    if (!slugRegex.test(slug)) {
      this.newWsError.set('Slug invalide : lettres minuscules, chiffres et tirets uniquement.')
      return
    }
    if (!namespaceId || this.creatingWorkstream()) return

    this.creatingWorkstream.set(true)
    this.newWsError.set(null)

    this.factoryApi.createWorkstream(namespaceId, { slug, name, status }).subscribe({
      next: () => {
        this.closeNewWorkstreamForm()
        this.loadWorkstreams()
      },
      error: (err) => {
        this.newWsError.set(err.error?.error ?? 'Erreur lors de la création du workstream')
        this.creatingWorkstream.set(false)
      },
    })
  }

  /* ── Lancement d'un run Forge ──────────────────────────────────── */

  openLaunchPanel(): void {
    this.launchTicketId.set('')
    this.launchWorkstreamSlug.set('')
    this.showLaunchPanel.set(true)
  }

  onWorkstreamChange(event: Event): void {
    this.launchWorkstreamSlug.set((event.target as HTMLSelectElement).value)
  }

  closeLaunchPanel(): void {
    this.showLaunchPanel.set(false)
  }

  onTicketInput(event: Event): void {
    this.launchTicketId.set((event.target as HTMLInputElement).value)
  }

  launchForgeRun(): void {
    const ticketId = this.launchTicketId().trim()
    const namespaceId = this.currentNamespaceId()
    if (!ticketId || !namespaceId || this.launching()) return

    this.launching.set(true)
    const wsSlug = this.launchWorkstreamSlug()
    const title = wsSlug ? `Forge run ${ticketId} [${wsSlug}]` : `Forge run ${ticketId}`
    const message = wsSlug
      ? `@ProductEngineer /forge-run ${ticketId} --workstream ${wsSlug}`
      : `@ProductEngineer /forge-run ${ticketId}`

    this.caseController
      .createCase({
        namespaceId,
        title,
        favorite: false,
        removed: false,
        status: CaseStatusEnum.PENDING,
      })
      .pipe(
        switchMap((createdCase) =>
          this.caseController.addMessageCase(createdCase.id!, { content: message }).pipe(map(() => createdCase))
        )
      )
      .subscribe({
        next: (createdCase) => {
          this.launching.set(false)
          this.closeLaunchPanel()
          const caseId = createdCase.id!
          const ticketId = this.launchTicketId().trim()
          // Persister dans forge/active-run.json du repo pour partage entre utilisateurs
          this.factoryApi.setActiveRun(namespaceId, caseId, ticketId || null).subscribe({
            next: () => {
              this.activeCaseId.set(caseId)
              this.activeTicketId.set(ticketId || null)
            },
          })
          // Navigation vers le workstream après lancement
          const firstWs = this.workstreamEntries[0]
          if (firstWs && this.screen() === 'streams') {
            this.router.navigate([], {
              relativeTo: this.route,
              queryParams: { ws: firstWs.slug, epic: null, story: null },
              queryParamsHandling: 'merge',
            })
          }
        },
        error: (err) => {
          console.error('[ForgeRun] Failed to launch forge run:', err)
          this.launching.set(false)
        },
      })
  }
}
