import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core'
import { RunState, StepKey, WorkflowStep, US_STEPS, toneOf } from '../forge.model'

/** Une cellule du ruban, prête à peindre — aucune règle métier ici. */
interface RibbonCell {
  readonly key: StepKey
  readonly name: string
  readonly short: string
  readonly weight: number
  readonly face: string
  readonly faceInk: string
  readonly bg: string
  readonly ink: string
  readonly word: string
  readonly title: string
  readonly count: string
  readonly selected: boolean
  readonly current: boolean
}

export type RibbonMode =
  /** Distribution : combien d'US se trouvent dans chaque étape. */
  | 'distribution'
  /** Ligne de grille : l'état d'une US, étape par étape. */
  | 'row'
  /** Ruban détaillé d'une US, avec le nom de chaque étape. */
  | 'detail'

/**
 * Le même diagramme à trois échelles — c'est ce qui permet de
 * descendre du workstream à l'US sans changer de vocabulaire.
 *
 * `distribution` prend des comptes, `row` et `detail` prennent des
 * états. Discovery est exclue : elle est de niveau Epic.
 */
@Component({
  selector: 'agentos-forge-ribbon',
  templateUrl: './forge-ribbon.component.html',
  styleUrl: './forge-ribbon.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'agentos-forge-ribbon' },
})
export class ForgeRibbonComponent {
  readonly mode = input<RibbonMode>('detail')

  /** Mode distribution : nombre d'US par étape. */
  readonly counts = input<Partial<Record<StepKey, number>>>({})

  /** Modes row et detail : état de l'US par étape. */
  readonly states = input<Partial<Record<StepKey, RunState>>>({})

  /** Étape mise en avant par un anneau : la position courante de l'US. */
  readonly current = input<StepKey | null>(null)

  /** Étape ouverte dans le volet de détail. */
  readonly selected = input<StepKey | null>(null)

  readonly labels = input(true)

  readonly stepPicked = output<StepKey>()

  readonly steps: readonly WorkflowStep[] = US_STEPS

  readonly cells = computed<readonly RibbonCell[]>(() => {
    const mode = this.mode()
    const counts = this.counts()
    const states = this.states()

    return this.steps.map((step) => {
      const state: RunState = states[step.key] ?? 'pending'
      const tone = toneOf(state)
      const count = counts[step.key] ?? 0
      const empty = mode === 'distribution' && count === 0

      return {
        key: step.key,
        name: step.name,
        short: step.short,
        weight: step.weight,
        face: empty ? 'var(--sf-empty)' : step.face,
        faceInk: empty ? 'transparent' : step.faceInk,
        bg: tone.bg,
        ink: tone.ink,
        word: state === 'pending' ? '' : state === 'na' ? 'n/a' : tone.word,
        title: mode === 'distribution' ? `${step.name} \u2014 ${count} US` : `${step.name} \u2014 ${tone.word}`,
        count: count ? String(count) : '',
        selected: this.selected() === step.key,
        current: this.current() === step.key,
      }
    })
  })

  pick(key: StepKey): void {
    this.stepPicked.emit(key)
  }

  trackCell = (_: number, cell: RibbonCell) => cell.key
}
