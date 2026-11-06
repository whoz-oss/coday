import { ChangeDetectionStrategy, Component, inject } from '@angular/core'
import { SetupStateService } from '../setup-state.service'

const MIN_KEY_LENGTH = 8

/**
 * Managed mode only: shown on `/welcome` instead of the loading page when the platform offers AI providers the user has no
 * global key for. Each key typed is saved as a user-global provider of the same name (it overlays the platform one);
 * the ones left empty are not set. Skippable: the keys can be added later from the AI providers page.
 */
@Component({
  selector: 'app-user-keys-step',
  templateUrl: './user-keys-step.component.html',
  styleUrls: ['./step.scss', './user-keys-step.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class UserKeysStepComponent {
  protected readonly state = inject(SetupStateService)

  protected onKey(name: string, event: Event): void {
    this.state.setUserKey(name, (event.target as HTMLInputElement).value)
  }

  protected keyTooShort(apiKey: string): boolean {
    return apiKey.length > 0 && apiKey.trim().length < MIN_KEY_LENGTH
  }
}
