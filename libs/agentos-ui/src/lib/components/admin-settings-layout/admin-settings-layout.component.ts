import { ChangeDetectionStrategy, Component } from '@angular/core'
import { RouterOutlet } from '@angular/router'
import { SettingsLayoutComponent, SettingsNavigationGroup } from '../settings-layout/settings-layout.component'

@Component({
  selector: 'agentos-admin-settings-layout',
  imports: [RouterOutlet, SettingsLayoutComponent],
  templateUrl: './admin-settings-layout.component.html',
  styleUrl: './admin-settings-layout.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AdminSettingsLayoutComponent {
  protected readonly groups: readonly SettingsNavigationGroup[] = [
    {
      label: 'Access',
      items: [
        { label: 'Users & Groups', commands: ['users'] },
        { label: 'Auth settings', commands: ['auth-settings'] },
      ],
    },
    {
      label: 'Connections',
      items: [
        { label: 'Platform integrations', commands: ['integration-configs'] },
        { label: 'Messaging', commands: ['messaging'] },
      ],
    },
    {
      label: 'AI',
      items: [
        { label: 'Platform agents', commands: ['agent-configs'] },
        { label: 'Platform AI providers', commands: ['ai-providers'] },
        { label: 'Platform AI models', commands: ['ai-models'] },
        { label: 'Platform skills', commands: ['skills'] },
        { label: 'Platform prompts', commands: ['prompts'] },
      ],
    },
    {
      label: 'Automation',
      items: [
        { label: 'Scheduled prompts', commands: ['scheduled-prompts'] },
        { label: 'Tool invoke', commands: ['tool-invoke'] },
      ],
    },
    {
      label: 'System',
      items: [{ label: 'Configuration', commands: ['configuration'] }],
    },
  ]
}
