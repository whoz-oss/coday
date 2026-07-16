import { ChangeDetectionStrategy, Component, inject } from '@angular/core'
import { Router } from '@angular/router'
import { ActionCardComponent } from '../action-card/action-card.component'

@Component({
  selector: 'agentos-admin-home',
  imports: [ActionCardComponent],
  templateUrl: './admin-home.component.html',
  styleUrl: './admin-home.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AdminHomeComponent {
  private readonly router = inject(Router)

  protected readonly sections = [
    {
      key: 'users',
      mark: '\u{1F464}',
      name: 'Users',
      description: 'Manage all platform users and their admin rights.',
      path: '/agentos/admin/users',
    },
    {
      key: 'agents',
      mark: '\u{1F916}',
      name: 'Platform agents',
      description: 'Manage agent configs shared across all namespaces.',
      path: '/agentos/admin/agent-configs',
    },
    {
      key: 'integrations',
      mark: '\u{1F517}',
      name: 'Platform integrations',
      description: 'Manage integration configs shared across all namespaces.',
      path: '/agentos/admin/integration-configs',
    },
    {
      key: 'ai-providers',
      mark: '\u{1F9E0}',
      name: 'Platform AI providers',
      description: 'Manage AI provider configs shared across all namespaces.',
      path: '/agentos/admin/ai-providers',
    },
    {
      key: 'ai-models',
      mark: '\u2728',
      name: 'Platform AI models',
      description: 'Manage AI model configs shared across all namespaces.',
      path: '/agentos/admin/ai-models',
    },
    {
      key: 'prompts',
      mark: '\u{1F4C4}',
      name: 'Platform prompts',
      description: 'Manage prompts shared across all namespaces.',
      path: '/agentos/admin/prompts',
    },
  ] as const

  protected navigateTo(path: string): void {
    this.router.navigate([path])
  }
}
