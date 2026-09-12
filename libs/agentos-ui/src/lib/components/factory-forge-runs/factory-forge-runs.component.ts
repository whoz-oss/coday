import { ChangeDetectionStrategy, Component, computed, effect, inject, signal } from '@angular/core'
import { toSignal } from '@angular/core/rxjs-interop'
import { ActivatedRoute, Router } from '@angular/router'
import { switchMap, map } from 'rxjs'
import { CaseControllerService, CaseStatusEnum } from '@whoz-oss/agentos-api-client'
import { EpicCockpitComponent } from './epic-cockpit/epic-cockpit.component'
import { ForgeRibbonComponent } from './forge-ribbon/forge-ribbon.component'
import { StoryRunComponent } from './story-run/story-run.component'
import { FactoryApiService, FactoryForgeRun, WorkstreamEntry } from '../../services/factory-api.service'
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
  imports: [ForgeRibbonComponent, EpicCockpitComponent, StoryRunComponent],
  templateUrl: './factory-forge-runs.component.html',
  styleUrl: './factory-forge-runs.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'agentos-factory-forge-runs' },
})
export class FactoryForgeRunsComponent {
  /** Service réel — gardé injecté, sera activé quand le backend le supporte. */
  protected readonly state = inject(FactoryForgeStateService)

  private readonly route = inject(ActivatedRoute)
  private readonly router = inject(Router)
  private readonly caseController = inject(CaseControllerService)
  private readonly factoryApi = inject(FactoryApiService)

  private readonly queryParamMap = toSignal(this.route.queryParamMap, {
    initialValue: this.route.snapshot.queryParamMap,
  })

  /** Namespace courant lu depuis ?ns= — même source que FactoryRunsComponent. */
  readonly currentNamespaceId = computed(() => this.queryParamMap().get('ns') ?? null)

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

  readonly screen = signal<Screen>('streams')
  readonly tab = signal<Tab>('epics')
  readonly wsIndex = signal(0)
  readonly epicIndex = signal(0)
  readonly storyKey = signal<string | null>(null)
  readonly stepKey = signal<StepKey | null>(null)

  /** Décisions prises en session, par US. La donnée source n'est pas mutée. */
  private readonly overridesByStory = signal<Record<string, Partial<Record<StepKey, RunState>>>>({})
  private readonly answeredStories = signal<readonly string[]>([])

  /** Dialog de lancement d'un run Forge. */
  readonly showLaunchPanel = signal(false)
  readonly launchTicketId = signal('')
  readonly launching = signal(false)
  readonly launchWorkstreamSlug = signal<string>('')

  /** wsIndex borné à la taille effective de la liste. */
  readonly workstream = computed(() => {
    const list = this.workstreamsSignal()
    if (!list.length) return undefined
    const idx = Math.min(this.wsIndex(), list.length - 1)
    return list[idx]
  })
  /** Runs réels mappés en EpicRun pour le workstream affiché. */
  readonly epicRunsForWorkstream = computed((): EpicRun[] =>
    this.state.runs().map((run) => this.mapForgeRunToEpicRun(run))
  )

  readonly epic = computed(() => this.epicRunsForWorkstream()[this.epicIndex()])
  readonly story = computed(() => {
    const e = this.epic()
    if (!e) return undefined
    return e.stories.find((s) => s.key === this.storyKey()) ?? e.stories[0]
  })

  /** Run Forge brut correspondant à l'épic sélectionné. */
  readonly currentForgeRun = computed(() => {
    const runs = this.state.runs()
    const idx = this.epicIndex()
    return runs[idx] ?? null
  })

  readonly forgeRunIdForEpic = computed(() => this.currentForgeRun()?.runId ?? null)

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
    const runsCount = this.state.runs().length
    const epicRuns = this.epicRunsForWorkstream()

    return this.workstreams.map((ws, index) => {
      // Les stories viennent des runs réels si disponibles, sinon des données mock
      const stories = runsCount > 0 ? epicRuns.flatMap((e) => e.stories) : ws.epics.flatMap((e) => e.stories)
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
          runsCount > 0 ? plural(runsCount, 'run Forge', 'runs Forge') : plural(ws.epics.length, 'Epic', 'Epics'),
          `${stories.length} US`,
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
        this.loadWorkstreams()
      }
    })
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
    this.screen.set('streams')
  }

  openWorkstream(index: number): void {
    this.wsIndex.set(index)
    this.epicIndex.set(0)
    this.storyKey.set(null)
    this.tab.set('epics')
    this.screen.set('workstream')
  }

  goWorkstream(): void {
    this.screen.set('workstream')
  }

  openEpic(index: number): void {
    this.epicIndex.set(index)
    this.storyKey.set(null)
    this.screen.set('epic')
  }

  goEpic(): void {
    this.screen.set('epic')
  }

  /** Lien profond : l'US s'ouvre directement sur l'étape cliquée. */
  openStory(event: { story: string; step: StepKey }): void {
    this.storyKey.set(event.story)
    this.stepKey.set(event.step)
    this.screen.set('story')
  }

  setTab(tab: Tab): void {
    this.tab.set(tab)
  }

  /* ── Décisions ──────────────────────────────────────────────────────── */

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
          this.router.navigate(['/agentos/home'], {
            queryParams: { ns: namespaceId, case: createdCase.id },
          })
        },
        error: (err) => {
          console.error('[ForgeRun] Failed to launch forge run:', err)
          this.launching.set(false)
        },
      })
  }
}
