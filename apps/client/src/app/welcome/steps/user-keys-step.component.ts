import { ChangeDetectionStrategy, Component, inject } from '@angular/core'
import { SetupStateService } from '../setup-state.service'

const MIN_KEY_LENGTH = 8

/**
 * Managed mode only: shown on `/welcome` instead of the loading page when the platform offers AI providers and the user's
 * profile is not complete: no email yet, and/or no personal key for any of those providers. Coday cannot start without
 * them, so the step is mandatory (no way to skip it). Each key typed is saved as a user-global provider of the same name
 * (it overlays the platform one); one key is enough, the ones left empty are not set.
 */
@Component({
  selector: 'app-user-keys-step',
  templateUrl: './user-keys-step.component.html',
  styleUrls: ['./step.scss', './user-keys-step.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class UserKeysStepComponent {
  protected readonly state = inject(SetupStateService)

  protected onEmail(event: Event): void {
    this.state.setProfileEmail((event.target as HTMLInputElement).value)
  }

  protected onKey(name: string, event: Event): void {
    this.state.setUserKey(name, (event.target as HTMLInputElement).value)
  }

  protected keyTooShort(apiKey: string): boolean {
    return apiKey.length > 0 && apiKey.trim().length < MIN_KEY_LENGTH
  }
}
