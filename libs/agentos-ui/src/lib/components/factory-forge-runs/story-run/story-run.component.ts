import { ChangeDetectionStrategy, Component, computed, input, output, signal } from '@angular/core'
import { ForgeRibbonComponent } from '../forge-ribbon/forge-ribbon.component'
import { GENERIC_SUBSTEPS } from '../forge.data'
import {
  DecisionKind,
  EvidenceEntry,
  RunState,
  StepKey,
  StoryRun,
  STEP_BY_KEY,
  SubStep,
  SubStepGroup,
  US_STEPS,
  headOf,
  stateOf,
  toneOf,
} from '../forge.model'

interface SubStepView extends SubStep {
  readonly word: string
  readonly bg: string
  readonly ink: string
  readonly hasBaseline: boolean
}

interface GroupView {
  readonly title: string
  readonly note: string
  readonly steps: readonly SubStepView[]
}

/**
 * Écran 03 — le run d'une US : son ruban, l'étape ouverte, ses
 * sous-étapes, et le tiroir de preuves.
 *
 * Les décisions humaines sont émises (`decided`), jamais appliquées
 * ici : la vue ne fait pas avancer le workflow.
 */
@Component({
  selector: 'agentos-story-run',
  imports: [ForgeRibbonComponent],
  templateUrl: './story-run.component.html',
  styleUrl: './story-run.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'agentos-story-run' },
})
export class StoryRunComponent {
  readonly story = input.required<StoryRun>()

  /** Étape ouverte au montage — le lien profond de la grille d'Epic. */
  readonly step = input<StepKey | null>(null)

  /** Décisions déjà prises dans cette session. */
  readonly overrides = input<Partial<Record<StepKey, RunState>>>({})

  readonly answered = input(false)

  readonly decided = output<{ story: string; kind: DecisionKind }>()
  readonly stepChanged = output<StepKey>()

  private readonly picked = signal<StepKey | null>(null)
  private readonly openEvidence = signal<{ group: number; index: number } | null>(null)

  readonly active = computed<StepKey>(() => this.picked() ?? this.step() ?? headOf(this.story(), this.overrides()))
  readonly meta = computed(() => STEP_BY_KEY[this.active()])
  readonly state = computed<RunState>(() => stateOf(this.story(), this.active(), this.overrides()))
  readonly tone = computed(() => toneOf(this.state()))
  readonly head = computed(() => headOf(this.story(), this.overrides()))

  readonly states = computed<Partial<Record<StepKey, RunState>>>(() => {
    const story = this.story()
    const overrides = this.overrides()
    return Object.fromEntries(US_STEPS.map((s) => [s.key, stateOf(story, s.key, overrides)]))
  })

  /** Une exécution interrompue se signale en haut de l'écran. */
  readonly interrupted = computed(() => Object.values(this.states()).includes('stopped'))

  readonly counters = computed(() => this.story().counters?.[this.active()] ?? [])

  readonly groups = computed<readonly GroupView[]>(() => {
    const own = this.story().detail?.[this.active()]
    const source: readonly SubStepGroup[] = own ?? this.genericFor(this.active())

    return source.map((group) => ({
      title: group.title ?? 'Sous-étapes',
      note: group.note ?? '',
      steps: group.steps.map((sub) => {
        const tone = toneOf(sub.state ?? this.state())
        return {
          ...sub,
          metric: sub.metric ?? tone.word,
          word: tone.word,
          bg: tone.bg,
          ink: tone.ink,
          hasBaseline: !!sub.baseline,
        }
      }),
    }))
  })

  readonly decision = computed(() => {
    const panel = this.story().decisions?.[this.active()]
    const state = this.state()
    return panel && (state === 'human' || state === 'blocked') ? panel : null
  })

  readonly query = computed(() => {
    const query = this.story().query
    return query && !this.answered() && query.step === this.active() ? query : null
  })

  readonly drawer = computed<{ title: string; context: string; entries: readonly EvidenceEntry[] } | null>(() => {
    const open = this.openEvidence()
    if (!open) return null
    const group = this.groups()[open.group]
    const sub = group?.steps[open.index]
    if (!sub) return null
    return { title: sub.label, context: `${this.story().key} \u00b7 ${this.meta().name}`, entries: sub.evidence }
  })

  private genericFor(key: StepKey): readonly SubStepGroup[] {
    const state = this.state()
    return (GENERIC_SUBSTEPS[key] ?? []).map((group) => ({
      title: group.title,
      note: group.note,
      steps: group.steps.map((sub) => ({ ...sub, state })),
    }))
  }

  pickStep(key: StepKey): void {
    this.picked.set(key)
    this.openEvidence.set(null)
    this.stepChanged.emit(key)
  }

  openStepEvidence(group: number, index: number): void {
    this.openEvidence.set({ group, index })
  }

  closeDrawer(): void {
    this.openEvidence.set(null)
  }

  decide(kind: DecisionKind): void {
    this.decided.emit({ story: this.story().key, kind })
  }

  trackGroup = (index: number, group: GroupView) => group.title + index
  trackSub = (_: number, sub: SubStepView) => sub.label
  trackEntry = (_: number, entry: EvidenceEntry) => entry.label
}
