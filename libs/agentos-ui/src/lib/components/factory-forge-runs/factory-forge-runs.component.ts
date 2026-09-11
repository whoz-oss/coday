import { ChangeDetectionStrategy, Component, inject, OnInit, signal } from '@angular/core'
import { FactoryForgeOracleResult, FactoryForgeRun } from '../../services/factory-api.service'
import { FactoryForgeStateService } from '../../services/factory-forge-state.service'

/* ------------------------------------------------------------------ *
 * Statuts — la couleur ne fait que renforcer un mot toujours écrit.
 * `blocked` n'est ni un échec fonctionnel ni un succès.
 * ------------------------------------------------------------------ */

export type StatusTone = 'ok' | 'active' | 'wait' | 'fail' | 'idle'

const STATUS_TONE: Record<string, StatusTone> = {
  approved: 'ok',
  passed: 'ok',
  finished: 'ok',
  conformant: 'ok',
  valid: 'ok',
  success: 'ok',
  running: 'active',
  started: 'active',
  pending: 'wait',
  blocked: 'wait',
  waiting: 'wait',
  waiting_human: 'wait',
  failed: 'fail',
  fail: 'fail',
  error: 'fail',
  invalid: 'fail',
  rejected: 'fail',
  not_started: 'idle',
  open: 'idle',
  skipped: 'idle',
}

const STATUS_LABEL: Record<string, string> = {
  approved: 'approved',
  passed: 'passed',
  finished: 'finished',
  conformant: 'conforme',
  running: 'running',
  pending: 'pending',
  blocked: 'blocked',
  failed: 'failed',
  invalid: 'preuve invalide',
  not_started: 'not started',
  open: 'open',
  skipped: 'skipped',
}

function tone(status: string | undefined): StatusTone {
  return (
    STATUS_TONE[
      String(status ?? 'not_started')
        .toLowerCase()
        .replace(/[^a-z0-9]+/g, '_')
    ] ?? 'idle'
  )
}

function label(status: string | undefined): string {
  const key = String(status ?? 'not_started')
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '_')
  return STATUS_LABEL[key] ?? String(status ?? 'not started').replace(/_/g, ' ')
}

type ForgeRecord = Record<string, unknown>
type ForgeStory = FactoryForgeRun['stories'][number]

/* ------------------------------------------------------------------ *
 * Types de vue — projections depuis FactoryForgeRun
 * ------------------------------------------------------------------ */

export interface StepView {
  key: string
  label: string
  caption: string
  lane: 'agents' | 'factory'
  tone: StatusTone
  statusLabel: string
  metric: string
  first: boolean
  /** Décrochement agents → Factory : dessiné en coude. */
  laneBreak: boolean
  /** Une étape terminale interrompt la chaîne visuelle. */
  breaks: boolean
}

export interface StoryView {
  id: string
  workItemKey: string
  tone: StatusTone
  statusLabel: string
  gateTone: StatusTone
  gateStatusLabel: string
  agentSteps: StepView[]
  factorySteps: StepView[]
  summary: string
  raw: ForgeStory
}

const TERMINAL = new Set(['failed', 'fail', 'error', 'invalid', 'rejected'])

@Component({
  selector: 'agentos-factory-forge-runs',
  templateUrl: './factory-forge-runs.component.html',
  styleUrl: './factory-forge-runs.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [],
})
export class FactoryForgeRunsComponent implements OnInit {
  protected readonly state = inject(FactoryForgeStateService)

  private readonly openEvidence = signal<ReadonlySet<string>>(new Set<string>())

  ngOnInit(): void {
    this.state.load()
  }

  protected refresh(): void {
    this.state.load()
  }

  /* ── Projection ── */

  protected stories(run: FactoryForgeRun): StoryView[] {
    return run.stories.map((story) => {
      const latestCampaign = story.oracleCampaigns.at(-1)
      const oracles = latestCampaign?.results ?? []
      const latestAnalysis = story.executions.at(-1)
      const latestEdit = story.edits.at(-1)

      const agentRaw: Array<{ key: string; lbl: string; caption: string; status: string; metric: string }> = [
        {
          key: 'analysis',
          lbl: 'Analysis',
          caption: 'agent lecture seule · plan borné',
          status: String(latestAnalysis?.['status'] ?? 'not_started'),
          metric: latestAnalysis
            ? `${story.executions.length} enregistrée${story.executions.length > 1 ? 's' : ''}`
            : '—',
        },
        {
          key: 'edit',
          lbl: 'Edit',
          caption: 'applique le plan, dans le scope US',
          status: String(latestEdit?.['status'] ?? 'not_started'),
          metric: latestEdit
            ? `${this.count(latestEdit['filesModified']) + this.count(latestEdit['filesCreated'])} fichier(s) · diff ${this.field(latestEdit['diffValidation'])}`
            : '—',
        },
      ]

      const factoryRaw: Array<{ key: string; lbl: string; caption: string; status: string; metric: string }> =
        oracles.map((o) => ({
          key: o.name,
          lbl: o.name,
          caption:
            o.code === 'ORACLE_NO_TEST_TARGET'
              ? 'aucune target de tests sur le projet propriétaire'
              : 'oracle déterministe Factory',
          status: o.code === 'ORACLE_NO_TEST_TARGET' ? 'skipped' : o.status,
          metric: o.durationMs != null ? this.duration(o.durationMs) : '—',
        }))

      const allRaw = [...agentRaw, ...factoryRaw]
      const steps: StepView[] = allRaw.map((s, i) => {
        const currLane: 'agents' | 'factory' = i < agentRaw.length ? 'agents' : 'factory'
        const prevLane: 'agents' | 'factory' = i > 0 ? (i - 1 < agentRaw.length ? 'agents' : 'factory') : 'agents'
        return {
          key: s.key,
          label: s.lbl,
          caption: s.caption,
          lane: currLane,
          tone: tone(s.status),
          statusLabel: label(s.status),
          metric: s.metric,
          first: i === 0,
          laneBreak: i > 0 && prevLane !== currLane,
          breaks: TERMINAL.has(s.status.toLowerCase()),
        }
      })

      const g3Status = latestCampaign?.status ?? 'not_started'
      const oracleCount = oracles.length
      const passedOracles = oracles.filter((o) => o.code !== 'ORACLE_NO_TEST_TARGET' && o.status === 'passed').length

      return {
        id: story.runId,
        workItemKey: story.workItem.id,
        tone: tone(story.status),
        statusLabel: label(story.status),
        gateTone: tone(g3Status),
        gateStatusLabel: label(g3Status),
        agentSteps: steps.filter((s) => s.lane === 'agents'),
        factorySteps: steps.filter((s) => s.lane === 'factory'),
        summary: `${oracleCount} oracle${oracleCount !== 1 ? 's' : ''} · ${passedOracles}/${oracleCount} passé${passedOracles !== 1 ? 's' : ''}`,
        raw: story,
      }
    })
  }

  protected epicTone(run: FactoryForgeRun): StatusTone {
    return tone(run.status)
  }
  protected epicLabel(run: FactoryForgeRun): string {
    return label(run.status)
  }

  protected intentTone(run: FactoryForgeRun): StatusTone {
    return tone(run.gates.find((g) => g.gate === 'G1')?.['status'])
  }
  protected intentLabel(run: FactoryForgeRun): string {
    return label(run.gates.find((g) => g.gate === 'G1')?.['status'])
  }

  protected specTone(run: FactoryForgeRun): StatusTone {
    return tone(run.gates.find((g) => g.gate === 'G2')?.['status'])
  }
  protected specLabel(run: FactoryForgeRun): string {
    return label(run.gates.find((g) => g.gate === 'G2')?.['status'])
  }

  protected gate3Breakdown(run: FactoryForgeRun): Array<{ count: number; label: string; tone: StatusTone }> {
    const tally = new Map<string, number>()
    for (const story of run.stories) {
      const s = story.oracleCampaigns.at(-1)?.status ?? 'not_started'
      tally.set(s, (tally.get(s) ?? 0) + 1)
    }
    return [...tally.entries()].map(([status, count]) => ({
      count,
      label: label(status),
      tone: tone(status),
    }))
  }

  protected counters(run: FactoryForgeRun): string {
    const n = run.stories.length
    return `1 spec Epic · ${n} StoryRun${n !== 1 ? 's' : ''}`
  }

  protected shortRepository(path: string | undefined): string {
    if (!path) return 'Repository unavailable'
    return path.split('/').filter(Boolean).at(-1) ?? path
  }

  protected isEvidenceOpen(id: string): boolean {
    return this.openEvidence().has(id)
  }

  protected toggleEvidence(id: string): void {
    this.openEvidence.update((current) => {
      const next = new Set(current)
      next.has(id) ? next.delete(id) : next.add(id)
      return next
    })
  }

  protected breaksChain(step: StepView): boolean {
    return step.breaks
  }

  protected trackRun(_: number, run: FactoryForgeRun): string {
    return run.runId
  }
  protected trackStory(_: number, view: StoryView): string {
    return view.id
  }
  protected trackStep(_: number, step: StepView): string {
    return step.key
  }
  protected trackOracle(_: number, oracle: FactoryForgeOracleResult): string {
    return `${oracle.name}-${oracle.commandHash ?? ''}`
  }

  protected field(value: unknown): string {
    if (value == null || value === '') return '—'
    if (typeof value === 'object') {
      return (
        Object.values(value as Record<string, unknown>)
          .filter(Boolean)
          .map(String)
          .join(' · ') || '—'
      )
    }
    return String(value)
  }

  protected list(value: unknown): string {
    return Array.isArray(value) && value.length > 0 ? value.map(String).join(', ') : '—'
  }

  protected count(value: unknown): number {
    return Array.isArray(value) ? value.length : 0
  }

  protected duration(value: number | undefined): string {
    if (value == null) return '—'
    return value < 1000 ? `${value} ms` : `${(value / 1000).toFixed(1)} s`
  }

  protected date(value: string): string {
    return new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value))
  }

  protected g1Gate(run: FactoryForgeRun): ForgeRecord {
    return (run.gates.find((g) => g.gate === 'G1') ?? {}) as ForgeRecord
  }

  protected g2Gate(run: FactoryForgeRun): ForgeRecord {
    return (run.gates.find((g) => g.gate === 'G2') ?? {}) as ForgeRecord
  }
}
