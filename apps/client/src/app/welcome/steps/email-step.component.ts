import { ChangeDetectionStrategy, Component, inject } from '@angular/core'
import { SetupStateService } from '../setup-state.service'

/** Step 1: the email becomes the user's external ID (and the AgentOS bootstrap admin). */
@Component({
  selector: 'app-email-step',
  templateUrl: './email-step.component.html',
  styleUrl: './step.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class EmailStepComponent {
  protected readonly state = inject(SetupStateService)

  protected onInput(event: Event): void {
    this.state.email.set((event.target as HTMLInputElement).value)
  }

  protected submit(event: Event): void {
    event.preventDefault()
    void this.state.submitEmail()
  }
}
