import { ChangeDetectionStrategy, Component, computed, inject, input, output, viewChild } from '@angular/core'
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

  protected readonly modelText = computed(() => modelLabel(this.model()))
  protected readonly effortText = computed(() => effortLabel(this.effort()))

  protected openModelPicker(): void {
    this.autocomplete.showModelChoices()
  }

  protected openEffortPicker(): void {
    this.autocomplete.showEffortLevels()
  }

  protected closeModelPicker(): void {
    this.autocomplete.modelSuggestions.set([])
  }

  protected closeEffortPicker(): void {
    this.autocomplete.effortSuggestions.set([])
  }
}
