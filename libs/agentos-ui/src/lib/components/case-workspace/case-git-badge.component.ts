import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core'
import { pullRequestIndicator, WorkspaceView } from '../../services/case-workspace.service'

@Component({
  selector: 'agentos-case-git-badge',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `@if (indicator(); as state) {
    <span
      class="git-state"
      [title]="state.label"
      [attr.aria-label]="state.label"
      role="img"
      [style.color]="state.color"
    >
      <span class="material-icons" aria-hidden="true">{{ state.icon }}</span>
    </span>
  }`,
  styles: [
    ':host{display:inline-flex;flex-shrink:0}.git-state{display:inline-flex;align-items:center;gap:2px}.material-icons{font-size:16px}',
  ],
})
export class CaseGitBadgeComponent {
  readonly view = input<WorkspaceView>()
  readonly indicator = computed(() => pullRequestIndicator(this.view()))
}
