import { Component, inject, OnInit } from '@angular/core'
import { CommonModule } from '@angular/common'
import { FormsModule } from '@angular/forms'
import { MatIconModule } from '@angular/material/icon'
import { MatInputModule } from '@angular/material/input'
import { MatFormFieldModule } from '@angular/material/form-field'
import { MatDialog } from '@angular/material/dialog'
import { AgentCrudApiService, AgentSummaryWithMeta } from '../../core/services/agent-crud-api.service'
import { AgentFormComponent, AgentFormData } from '../agent-form/agent-form.component'

@Component({
  selector: 'app-agent-manager',
  standalone: true,
  imports: [CommonModule, FormsModule, MatIconModule, MatInputModule, MatFormFieldModule],
  templateUrl: './agent-manager.component.html',
  styleUrls: ['./agent-manager.component.scss'],
})
export class AgentManagerComponent implements OnInit {
  private readonly agentCrudApi = inject(AgentCrudApiService)
  private readonly dialog = inject(MatDialog)

  agents: AgentSummaryWithMeta[] = []
  filteredAgents: AgentSummaryWithMeta[] = []
  searchQuery = ''
  isLoading = false
  errorMessage = ''

  ngOnInit(): void {
    this.loadAgents()
  }

  private loadAgents(): void {
    this.isLoading = true
    this.errorMessage = ''
    this.agentCrudApi.listEditableAgents().subscribe({
      next: (agents) => {
        this.agents = agents
        this.applyFilter()
        this.isLoading = false
      },
      error: (error) => {
        console.error('Error loading agents:', error)
        this.errorMessage = 'Failed to load agents'
        this.isLoading = false
      },
    })
  }

  applyFilter(): void {
    const query = this.searchQuery.toLowerCase().trim()
    this.filteredAgents = query
      ? this.agents.filter((a) => a.name.toLowerCase().includes(query) || a.description.toLowerCase().includes(query))
      : [...this.agents]
  }

  onSearchChange(): void {
    this.applyFilter()
  }

  createAgent(): void {
    const dialogRef = this.dialog.open<AgentFormComponent, AgentFormData, boolean>(AgentFormComponent, {
      width: '700px',
      maxHeight: '90vh',
      data: { mode: 'create' },
    })
    dialogRef.afterClosed().subscribe((result) => {
      if (result) this.loadAgents()
    })
  }

  editAgent(agent: AgentSummaryWithMeta): void {
    this.agentCrudApi.getAgent(agent.name).subscribe({
      next: (fullAgent) => {
        const dialogRef = this.dialog.open<AgentFormComponent, AgentFormData, boolean>(AgentFormComponent, {
          width: '700px',
          maxHeight: '90vh',
          data: { mode: 'edit', agent: fullAgent },
        })
        dialogRef.afterClosed().subscribe((result) => {
          if (result) this.loadAgents()
        })
      },
      error: (error) => {
        console.error('Error loading agent:', error)
        const errorMessage = error?.error?.error ?? error?.message ?? 'Failed to load agent'
        alert(`Failed to load agent: ${errorMessage}`)
      },
    })
  }

  deleteAgent(agent: AgentSummaryWithMeta): void {
    if (!confirm(`Are you sure you want to delete agent "${agent.name}"?`)) return

    this.agentCrudApi.deleteAgent(agent.name).subscribe({
      next: () => this.loadAgents(),
      error: (error) => {
        console.error('Error deleting agent:', error)
        const errorMessage = error?.error?.error ?? error?.message ?? 'Failed to delete agent'
        alert(`Failed to delete agent: ${errorMessage}`)
      },
    })
  }
}
