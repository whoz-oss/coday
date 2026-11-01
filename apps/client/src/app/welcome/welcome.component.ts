import { ChangeDetectionStrategy, Component, inject, OnInit } from '@angular/core'
import { SetupStateService, WIZARD_STEPS, WizardStep } from './setup-state.service'
import { EmailStepComponent } from './steps/email-step.component'
import { FolderStepComponent } from './steps/folder-step.component'
import { ProgressStepComponent } from './steps/progress-step.component'
import { ProvidersStepComponent } from './steps/providers-step.component'

const STEP_LABELS: Record<WizardStep, string> = {
  email: 'You',
  providers: 'Models',
  folder: 'Files',
  progress: 'Launch',
}

/**
 * `/welcome`: the first-run wizard. A thin shell (header, stepper, step outlet): every step is its own
 * component and all the logic lives in SetupStateService.
 */
@Component({
  selector: 'app-welcome',
  imports: [EmailStepComponent, ProvidersStepComponent, FolderStepComponent, ProgressStepComponent],
  templateUrl: './welcome.component.html',
  styleUrl: './welcome.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class WelcomeComponent implements OnInit {
  protected readonly state = inject(SetupStateService)
  protected readonly steps = WIZARD_STEPS.map((id) => ({ id, label: STEP_LABELS[id] }))

  ngOnInit(): void {
    void this.state.init()
  }
}
