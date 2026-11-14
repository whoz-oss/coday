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

/** Source of the id prefix of each instance. */
let nextInstanceId = 0

/** Keys that open the picker of a closed chip. */
const OPENING_KEYS: ReadonlySet<string> = new Set(['ArrowDown', 'ArrowUp', 'Enter', ' '])

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
 * also reach a surrounding drawer. Clicking the chip of the open picker closes it. Up to 600px wide the picker is
 * a bottom sheet over a backdrop (see the stylesheet).
 *
 * Accessibility: each chip is a select-only combobox (ARIA APG), a native button given `role="combobox"`. The
 * focus stays on the chip while its picker is open; `aria-controls` names the listbox and `aria-activedescendant`
 * the active option. Keys on the chip: closed, ArrowDown / ArrowUp / Enter / Space open the picker; open,
 * ArrowUp / ArrowDown / Home / End move, Enter / Space pick, Escape closes and Tab closes WITHOUT picking, then lets
 * the focus move on (a deliberate deviation from the APG, which selects on Tab: a pick saves the case settings, and
 * an accidental change costs more than one more keystroke). A picker opened by the `/model` or `/effort` command
 * keeps the focus in the textarea, which owns the keyboard then; only a picker opened from a chip closes when the
 * focus leaves this component.
 */
@Component({
  selector: 'agentos-case-settings',
  imports: [EffortAutocompleteComponent, ModelAutocompleteComponent],
  templateUrl: './case-settings.component.html',
  styleUrl: './case-settings.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
  // Bubbles from the chips only: the textarea of the composer is outside this element.
  host: { '(focusout)': 'onFocusOut($event)' },
})
export class CaseSettingsComponent {
  protected readonly autocomplete = inject(ComposerAutocompleteService)
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef)
  private readonly document = inject(DOCUMENT)

  /** Unique per instance: a page may hold several composers (and so several of these). */
  private readonly idBase = `case-settings-${nextInstanceId++}`
  protected readonly modelIdPrefix = `${this.idBase}-model`
  protected readonly effortIdPrefix = `${this.idBase}-effort`

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

  // What the chips point at while their picker is open. The listbox id follows from the prefix, so it is known
  // before the picker is rendered; the active option is the picker's own state.
  protected readonly modelListboxId = computed(() => (this.modelOpen() ? `${this.modelIdPrefix}-listbox` : null))
  protected readonly effortListboxId = computed(() => (this.effortOpen() ? `${this.effortIdPrefix}-listbox` : null))
  protected readonly modelActiveOptionId = computed(() =>
    this.modelOpen() ? (this.modelPicker()?.activeOptionId() ?? null) : null
  )
  protected readonly effortActiveOptionId = computed(() =>
    this.effortOpen() ? (this.effortPicker()?.activeOptionId() ?? null) : null
  )

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

  /**
   * Keyboard on a chip, per the APG select-only combobox. Closed: ArrowDown, ArrowUp, Enter and Space open the
   * picker (Alt+ArrowDown too). Open: the arrows, Home and End move, Enter and Space pick, Tab closes without
   * picking (and is left to the browser, so the focus moves on). Only the keys handled are cancelled; Escape is
   * handled for the whole document, see [onDocumentKeydown].
   */
  protected onChipKeydown(event: KeyboardEvent, kind: 'model' | 'effort'): void {
    if (event.ctrlKey || event.metaKey) return
    const open = kind === 'model' ? this.modelOpen() : this.effortOpen()
    if (!open) {
      if (OPENING_KEYS.has(event.key)) {
        // Enter and Space would otherwise also click the chip.
        event.preventDefault()
        if (kind === 'model') this.autocomplete.showModelChoices()
        else this.autocomplete.showEffortLevels()
      }
      return
    }
    const picker = kind === 'model' ? this.modelPicker() : this.effortPicker()
    switch (event.key) {
      case 'ArrowUp':
      case 'ArrowDown':
      case 'Home':
      case 'End':
        event.preventDefault()
        picker?.navigate(event.key)
        break
      case 'Enter':
      case ' ':
        // Cancelled so that it does not also click the chip, which would toggle the picker closed.
        event.preventDefault()
        picker?.navigate('Enter')
        break
      case 'Tab':
        this.closePickers()
        break
    }
  }

  /** A button clicks on the release of Space: the keydown already did the work, the click must not follow. */
  protected onChipKeyup(event: KeyboardEvent): void {
    if (event.key === ' ') event.preventDefault()
  }

  /**
   * The focus left a chip for something outside this component (Tab, a click elsewhere): a picker opened from the
   * chip goes with it. Not reached for a picker opened by the command, whose focus is in the textarea, outside.
   */
  protected onFocusOut(event: FocusEvent): void {
    if (!this.anyOpen()) return
    const next = event.relatedTarget
    if (next instanceof Node && !this.host.nativeElement.contains(next)) this.closePickers()
  }

  protected onModelPicked(choice: ModelChoice): void {
    this.modelSelected.emit(choice)
    this.restoreFocus(this.modelChip())
  }

  protected onEffortPicked(option: EffortOption): void {
    this.effortSelected.emit(option)
    this.restoreFocus(this.effortChip())
  }

  /**
   * The picker is gone: the focus must not fall to the body. It stays where it is when it is elsewhere (the
   * textarea of a picker opened by the command), and goes back to the chip when it was on a chip or lost.
   */
  private restoreFocus(chip: ElementRef<HTMLButtonElement> | undefined): void {
    const active = this.document.activeElement
    if (!active || active === this.document.body || this.host.nativeElement.contains(active)) {
      chip?.nativeElement.focus()
    }
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
