import { Component, computed, inject, OnInit, signal } from '@angular/core'
import { Router } from '@angular/router'
import { MatButtonModule } from '@angular/material/button'
import { MatIconModule } from '@angular/material/icon'
import { MatMenuModule } from '@angular/material/menu'
import { MatDialog } from '@angular/material/dialog'
import { CardActionsDirective, EntityCardComponent, EntityListComponent, EntityListItem } from '@whoz-oss/design-system'
import { AgentCrudApiService, AgentSummaryWithMeta } from '../../core/services/agent-crud-api.service'
import { ProjectStateService } from '../../core/services/project-state.service'
import { AgentFormComponent, AgentFormData } from '../agent-form/agent-form.component'

/**
 * Full-page agent list for a project.
 *
 * Route: /project/:projectName/agents
 *
 * Agents are grouped by source (project vs colocated).
 */
@Component({
  selector: 'app-agent-list',
  standalone: true,
  imports: [
    EntityListComponent,
    EntityCardComponent,
    CardActionsDirective,
    MatButtonModule,
    MatIconModule,
    MatMenuModule,
  ],
  templateUrl: './agent-list.component.html',
  styleUrl: './agent-list.component.scss',
})
export class AgentListComponent implements OnInit {
  private readonly agentCrudApi = inject(AgentCrudApiService)
  private readonly projectState = inject(ProjectStateService)
  private readonly dialog = inject(MatDialog)
  private readonly router = inject(Router)

  protected readonly agents = signal<AgentSummaryWithMeta[]>([])
  protected readonly isLoading = signal(false)
  protected readonly errorMessage = signal<string | null>(null)

  protected readonly agentItems = computed<EntityListItem[]>(() =>
    this.agents().map((agent) => ({
      id: agent.name,
      name: agent.name,
      description: agent.description,
      badges: this.buildBadges(agent),
      groupKey: agent.source,
      groupLabel: agent.source === 'project' ? 'Project (~/.coday/)' : 'Colocated (next to coday.yaml)',
    }))
  )

  ngOnInit(): void {
    this.loadAgents()
  }

  private loadAgents(): void {
    this.isLoading.set(true)
    this.errorMessage.set(null)
    this.agentCrudApi.listEditableAgents().subscribe({
      next: (agents) => {
        this.agents.set(agents)
        this.isLoading.set(false)
      },
      error: (err) => {
        this.errorMessage.set(err?.error?.error ?? 'Failed to load agents')
        this.isLoading.set(false)
      },
    })
  }

  protected onBack(): void {
    const projectName = this.projectState.getSelectedProjectId()
    this.router.navigate(projectName ? ['project', projectName] : ['/'])
  }

  protected onCreate(): void {
    const dialogRef = this.dialog.open<AgentFormComponent, AgentFormData, boolean>(AgentFormComponent, {
      width: '700px',
      maxHeight: '90vh',
      data: { mode: 'create' },
    })
    dialogRef.afterClosed().subscribe((result) => {
      if (result) this.loadAgents()
    })
  }

  protected onEdit(agentName: string): void {
    this.agentCrudApi.getAgent(agentName).subscribe({
      next: (agent) => {
        const dialogRef = this.dialog.open<AgentFormComponent, AgentFormData, boolean>(AgentFormComponent, {
          width: '700px',
          maxHeight: '90vh',
          data: { mode: 'edit', agent },
        })
        dialogRef.afterClosed().subscribe((result) => {
          if (result) this.loadAgents()
        })
      },
      error: (err) => this.errorMessage.set(err?.error?.error ?? 'Failed to load agent'),
    })
  }

  protected onDelete(agentName: string): void {
    if (!confirm(`Are you sure you want to delete agent "${agentName}"?`)) return
    this.agentCrudApi.deleteAgent(agentName).subscribe({
      next: () => this.loadAgents(),
      error: (err) => this.errorMessage.set(err?.error?.error ?? 'Failed to delete agent'),
    })
  }

  private buildBadges(agent: AgentSummaryWithMeta) {
    return [
      agent.source === 'project'
        ? { label: 'project', variant: 'info' as const }
        : { label: 'colocated', variant: 'success' as const },
    ]
  }
}
