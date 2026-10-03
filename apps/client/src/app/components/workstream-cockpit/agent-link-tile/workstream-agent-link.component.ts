import { ChangeDetectionStrategy, Component, Input } from '@angular/core'
import { MatButtonModule } from '@angular/material/button'
import { MatIconModule } from '@angular/material/icon'

/**
 * Placeholder tile linking to the Workstream Agent chat / case thread.
 *
 * The Workstream Agent is a supervision/produce agent (Phase 0 doc §5): this
 * tile is the visual entry point to its conversation thread. It is intentionally
 * inert in the scaffold.
 */
@Component({
  selector: 'app-workstream-agent-link',
  standalone: true,
  imports: [MatButtonModule, MatIconModule],
  templateUrl: './workstream-agent-link.component.html',
  changeDetection: ChangeDetectionStrategy.Eager,
  styleUrl: './workstream-agent-link.component.scss',
})
export class WorkstreamAgentLinkComponent {
  @Input() workstreamId: string | null = null
  @Input() projectName: string | null = null

  // TODO(Phase 6): resolve the real Workstream Agent case/thread id for this workstream and
  // navigate to the existing thread route (['project', projectName, 'thread', threadId]).
  protected onOpenAgentThread(): void {
    console.log('[WORKSTREAM-COCKPIT] Workstream Agent thread link is a Phase 6 placeholder', {
      workstreamId: this.workstreamId,
      projectName: this.projectName,
    })
  }
}
