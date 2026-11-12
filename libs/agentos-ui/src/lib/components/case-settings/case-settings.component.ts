import { DOCUMENT } from '@angular/common'
import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  ElementRef,
  inject,
  input,
  output,
  viewChild,
} from '@angular/core'
import { ReasoningEffort } from '@whoz-oss/agentos-api-client'
import { ComposerAutocompleteService } from '../composer-autocomplete/composer-autocomplete.service'
import { EffortOption, effortLabel } from '../composer-autocomplete/effort-command'
import { ModelChoice, modelLabel } from '../composer-autocomplete/model-command'
import { EffortAutocompleteComponent } from '../effort-autocomplete/effort-autocomplete.component'
import { ModelAutocompleteComponent } from '../model-autocomplete/model-autocomplete.component'

/**
 * CaseSettingsComponent — the `model:` and `effort:` chips of a composer, with the pickers they open.
 *
 * Shared by the case chat (where a choice is saved on the case) and the case home (where it is held until the
 * case is created): the host owns the sink, this component owns what the user sees. It is presentational apart
 * from one thing: it reads the suggestions and opens the pickers through the host's [ComposerAutocompleteService],
 * the same instance that turns `/model` and `/effort` into suggestions, so the command and the chip open the very
 * same picker.
 *
 * Place it in the actions row of a composer: the host element generates no box, so the chips take part in the
 * row, and the pickers are positioned against the composer (the nearest positioned ancestor), above it. The
 * picker components are exposed so that the host can hand them to `ComposerAutocompleteService.onKeydown`.
 *
 * Clearing a choice is an entry of the pickers (`agent` for the model, `default` for the effort), reached from
 * the chip as from the command.
 *
 * Dismissal: while a picker is open, a pointerdown outside this component (its chips and pickers are inside)
 * closes it, and so does Escape anywhere — the textarea delegation of [ComposerAutocompleteService.onKeydown]
 * only sees the keys typed in the textarea, not those typed with the focus on a chip. The document listeners are
 * registered only while a picker is open, in the capture phase so that the Escape that closes a picker does not
 * also reach a surrounding drawer. Clicking the chip of the open picker closes it, and ArrowUp/ArrowDown/Enter on
 * a chip drive its picker. Up to 600px wide the picker is a bottom sheet over a backdrop (see the stylesheet).
 */
@Component({
  selector: 'agentos-case-settings',
  imports: [EffortAutocompleteComponent, ModelAutocompleteComponent],
  templateUrl: './case-settings.component.html',
  styleUrl: './case-settings.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CaseSettingsComponent {
  protected readonly autocomplete = inject(ComposerAutocompleteService)
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef)
  private readonly document = inject(DOCUMENT)

  /** The model in use (an alias or an API model name); null when each agent uses its own. */
  readonly model = input<string | null>(null)

  /** The reasoning effort in use; null when each agent uses the effort of its model. */
  readonly effort = input<ReasoningEffort | null>(null)

  /** True when nothing can be changed now (case ended, case already created): the chips stay, inert. */
  readonly disabled = input(false)

  /** The entry picked in the model picker; null model = back to each agent's own. */
  readonly modelSelected = output<ModelChoice>()

  /** The entry picked in the effort picker; null effort = back to each agent's model. */
  readonly effortSelected = output<EffortOption>()

  readonly modelPicker = viewChild(ModelAutocompleteComponent)
  readonly effortPicker = viewChild(EffortAutocompleteComponent)

  private readonly modelChip = viewChild<ElementRef<HTMLButtonElement>>('modelChip')
  private readonly effortChip = viewChild<ElementRef<HTMLButtonElement>>('effortChip')

  protected readonly modelOpen = computed(() => this.autocomplete.modelSuggestions().length > 0)
  protected readonly effortOpen = computed(() => this.autocomplete.effortSuggestions().length > 0)
  protected readonly anyOpen = computed(() => this.modelOpen() || this.effortOpen())

  protected readonly modelText = computed(() => modelLabel(this.model()))
  protected readonly effortText = computed(() => effortLabel(this.effort()))

  constructor() {
    // Listen on the document only while a picker is open.
    effect((onCleanup) => {
      if (!this.anyOpen()) return
      const doc = this.document
      const onPointerDown = (event: Event) => this.onDocumentPointerDown(event)
      const onKeydown = (event: KeyboardEvent) => this.onDocumentKeydown(event)
      doc.addEventListener('pointerdown', onPointerDown, true)
      doc.addEventListener('keydown', onKeydown, true)
      onCleanup(() => {
        doc.removeEventListener('pointerdown', onPointerDown, true)
        doc.removeEventListener('keydown', onKeydown, true)
      })
    })
  }

  /** Opens the model picker, or closes it when it is already open. */
  protected toggleModelPicker(): void {
    if (this.modelOpen()) this.closeModelPicker()
    else this.autocomplete.showModelChoices()
  }

  /** Opens the effort picker, or closes it when it is already open. */
  protected toggleEffortPicker(): void {
    if (this.effortOpen()) this.closeEffortPicker()
    else this.autocomplete.showEffortLevels()
  }

  /** Keyboard on a chip: while its picker is open, the arrows and Enter drive it like a listbox trigger. */
  protected onChipKeydown(event: KeyboardEvent, kind: 'model' | 'effort'): void {
    if (!(kind === 'model' ? this.modelOpen() : this.effortOpen())) return
    if (event.key !== 'ArrowUp' && event.key !== 'ArrowDown' && event.key !== 'Enter') return
    // Enter must not also click the chip, which would toggle the picker closed.
    event.preventDefault()
    const picker = kind === 'model' ? this.modelPicker() : this.effortPicker()
    picker?.navigate(event.key)
  }

  /** Closes both pickers, e.g. from the backdrop of the mobile sheet. */
  protected closePickers(): void {
    this.closeModelPicker()
    this.closeEffortPicker()
  }

  /** A pointerdown outside the component dismisses the open picker. */
  private onDocumentPointerDown(event: Event): void {
    const target = event.target
    if (target instanceof Node && this.host.nativeElement.contains(target)) return
    this.closePickers()
  }

  /** Escape anywhere dismisses the open picker, and gives the focus back to the chip that opened it. */
  private onDocumentKeydown(event: KeyboardEvent): void {
    if (event.key !== 'Escape') return
    event.preventDefault()
    event.stopPropagation()
    const chip = this.modelOpen() ? this.modelChip() : this.effortChip()
    const focusInside = this.host.nativeElement.contains(this.document.activeElement)
    this.closePickers()
    if (focusInside) chip?.nativeElement.focus()
  }

  protected closeModelPicker(): void {
    this.autocomplete.modelSuggestions.set([])
  }

  protected closeEffortPicker(): void {
    this.autocomplete.effortSuggestions.set([])
  }
}
