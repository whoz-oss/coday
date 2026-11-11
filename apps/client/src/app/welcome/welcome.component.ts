import { ChangeDetectionStrategy, Component, inject, Injector, OnDestroy, OnInit } from '@angular/core'
import { ActivatedRoute } from '@angular/router'
import { parseWelcomePreview, SetupStateService, WIZARD_STEPS, WizardStep } from './setup-state.service'
import { EmailStepComponent } from './steps/email-step.component'
import { FolderStepComponent } from './steps/folder-step.component'
import { ProgressStepComponent } from './steps/progress-step.component'
import { ProvidersStepComponent } from './steps/providers-step.component'
import { UserKeysStepComponent } from './steps/user-keys-step.component'

const STEP_LABELS: Record<WizardStep, string> = {
  email: 'You',
  providers: 'Models',
  folder: 'Files',
  progress: 'Launch',
}

/**
 * `/welcome`: the first-run wizard, or, once the setup is done, a plain loading page (see WelcomeMode). A thin shell
 * (header, stepper, step outlet): every step is its own component and all the logic lives in SetupStateService.
 */
@Component({
  selector: 'app-welcome',
  imports: [
    EmailStepComponent,
    ProvidersStepComponent,
    FolderStepComponent,
    ProgressStepComponent,
    UserKeysStepComponent,
  ],
  templateUrl: './welcome.component.html',
  styleUrl: './welcome.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class WelcomeComponent implements OnInit, OnDestroy {
  protected readonly state = inject(SetupStateService)
  protected readonly steps = WIZARD_STEPS.map((id) => ({ id, label: STEP_LABELS[id] }))
  private readonly injector = inject(Injector)
  private readonly route = inject(ActivatedRoute)

  ngOnInit(): void {
    void this.applyTheme()
    void this.state.init(parseWelcomePreview(this.route.snapshot.queryParamMap.get('preview')))
  }

  /** The service is root-scoped: without this, the status polling would outlive the page, whatever way the user leaves it. */
  ngOnDestroy(): void {
    this.state.stopPolling()
  }

  /**
   * Light or dark like the app. `data-theme` on <html> is owned by the theme service of agentos-ui, which nothing
   * instantiates before the app shell, so without this the page would always be light. Resolving the THEME_PORT
   * builds it, which reads the stored choice (or the OS preference) and applies it, and keeps following the OS while
   * the mode is `system`. The lib is already loaded by the app initializer, so this costs no extra download.
   */
  private async applyTheme(): Promise<void> {
    try {
      const { THEME_PORT } = await import('@whoz-oss/agentos-ui')
      this.injector.get(THEME_PORT)
    } catch {
      // The page stays light: never worth breaking the wizard for.
    }
  }
}
