import { ChangeDetectionStrategy, Component, computed, inject, input, output, signal } from '@angular/core'
import { ForgeRibbonComponent } from '../forge-ribbon/forge-ribbon.component'
import { EpicRun, RunState, StepKey, StoryRun, US_STEPS, headOf, plural, stateOf, toneOf } from '../forge.model'
import { FactoryApiService } from '../../../services/factory-api.service'

interface Flag {
  readonly label: string
  readonly bg: string
  readonly ink: string
}

interface StoryRowView {
  readonly story: StoryRun
  readonly key: string
  readonly title: string
  /** L'US attend une réponse de l'humain : badge « question ». */
  readonly waiting: boolean
  readonly head: StepKey
  readonly states: Partial<Record<StepKey, RunState>>
}

/**
 * Écran 02 — le cockpit d'une Epic : le ruban agrégé (combien d'US
 * par étape) au-dessus d'une ligne par US. Deux échelles du même
 * diagramme, donc directement comparables.
 */
@Component({
  selector: 'agentos-epic-cockpit',
  imports: [ForgeRibbonComponent],
  templateUrl: './epic-cockpit.component.html',
  styleUrl: './epic-cockpit.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'agentos-epic-cockpit' },
})
export class EpicCockpitComponent {
  readonly epic = input.required<EpicRun>()
  readonly overrides = input<Record<string, Partial<Record<StepKey, RunState>>>>({})
  readonly answered = input<readonly string[]>([])

  /** Identifiant du run Forge brut — requis pour l'approbation G1. */
  readonly runId = input<string | null>(null)
  /** Vrai quand G1 est en attente d'approbation humaine. */
  readonly g1WaitingHuman = input<boolean>(false)
  /** Hash de preuve du ledger G1 — transmis au body de la décision. */
  readonly g1EvidenceSetHash = input<string | null>(null)
  /** Namespace courant — requis pour le paramètre de l'URL de décision. */
  readonly namespaceId = input<string | null>(null)

  /** Lien profond : l'US ET l'étape à ouvrir. */
  readonly storyPicked = output<{ story: string; step: StepKey }>()
  /** Émis après une approbation G1 réussie pour demander au parent de recharger. */
  readonly g1Approved = output<void>()

  readonly steps = US_STEPS

  readonly rows = computed<readonly StoryRowView[]>(() =>
    this.epic().stories.map((story) => {
      const overrides = this.overrides()[story.key]
      return {
        story,
        key: story.key,
        title: story.title,
        waiting: !!story.query && !this.answered().includes(story.key),
        head: headOf(story, overrides),
        states: Object.fromEntries(US_STEPS.map((s) => [s.key, stateOf(story, s.key, overrides)])),
      }
    })
  )

  /** Combien d'US dans chaque étape — jamais dans une étape `na`. */
  readonly counts = computed<Partial<Record<StepKey, number>>>(() => {
    const tally: Partial<Record<StepKey, number>> = {}
    for (const row of this.rows()) {
      tally[row.head] = (tally[row.head] ?? 0) + 1
    }
    return tally
  })

  readonly flags = computed<readonly Flag[]>(() => {
    const rows = this.rows()
    const proven = rows.filter((r) => r.states['g3'] === 'done').length
    const blocked = rows.filter((r) => Object.values(r.states).some((v) => v === 'blocked' || v === 'failed')).length
    const waiting = rows.filter((r) => r.waiting || Object.values(r.states).includes('human')).length

    const list: Flag[] = [
      {
        label: `${proven}/${rows.length} G3 passées`,
        bg: proven ? toneOf('done').bg : toneOf('pending').bg,
        ink: proven ? toneOf('done').ink : toneOf('pending').ink,
      },
    ]
    if (blocked) list.push({ label: plural(blocked, 'US bloquée', 'US bloquées'), ...this.tone('blocked') })
    if (waiting) list.push({ label: `${waiting} attente humaine`, ...this.tone('human') })
    return list
  })

  /** La pastille de clôture se colore d'après son propre mot. */
  readonly closureTone = computed(() => {
    const closure = this.epic().closure
    if (closure === 'passed') return this.tone('done')
    if (closure === 'partial') return this.tone('human')
    if (closure === 'blocked') return this.tone('blocked')
    if (closure === 'failed') return this.tone('failed')
    return this.tone('pending')
  })

  readonly closureRows = computed(() =>
    this.rows().map((row) => ({
      key: row.key,
      verdict: row.states['g3'] === 'done' ? 'preuve acquise' : toneOf(row.states['g3'] ?? 'pending').word,
    }))
  )

  private readonly factoryApi = inject(FactoryApiService)
  protected readonly approving = signal(false)
  protected readonly approvalError = signal<string | null>(null)

  protected approveG1(): void {
    const runId = this.runId()
    const hash = this.g1EvidenceSetHash()
    const nsId = this.namespaceId()
    if (!runId || !hash || !nsId || this.approving()) return

    this.approving.set(true)
    this.approvalError.set(null)
    this.factoryApi.approveG1(runId, hash, nsId).subscribe({
      next: () => {
        this.approving.set(false)
        this.g1Approved.emit()
      },
      error: (err: Error) => {
        this.approving.set(false)
        this.approvalError.set(`Erreur : ${err.message ?? "impossible d'approuver G1"}`)
      },
    })
  }

  private tone(state: RunState): { bg: string; ink: string } {
    const { bg, ink } = toneOf(state)
    return { bg, ink }
  }

  open(story: string, step: StepKey): void {
    this.storyPicked.emit({ story, step })
  }

  trackRow = (_: number, row: StoryRowView) => row.key
}
