import { Component, computed, inject, OnInit, signal } from '@angular/core'
import { FormsModule } from '@angular/forms'
import { MatDialogModule, MatDialogRef } from '@angular/material/dialog'
import { MatButtonModule } from '@angular/material/button'
import { MatFormFieldModule } from '@angular/material/form-field'
import { MatInputModule } from '@angular/material/input'
import { MatAutocompleteModule, MatAutocompleteTrigger } from '@angular/material/autocomplete'
import { MatSelectModule } from '@angular/material/select'
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner'
import { MatIconModule } from '@angular/material/icon'
import { ProjectApiService } from '../../core/services/project-api.service'
import { AgentApiService } from '../../core/services/agent-api.service'

@Component({
  selector: 'app-new-task-dialog',
  standalone: true,
  // Default change detection (intentionally omitted): this dialog runs in a CDK overlay
  // created outside Angular's zone. OnPush + overlay is a known footgun — signal updates
  // and HTTP callbacks are not reliably detected without manual detectChanges() / ngZone
  // workarounds on every code path. Default lets zone.js handle everything automatically.
  imports: [
    FormsModule,
    MatDialogModule,
    MatButtonModule,
    MatFormFieldModule,
    MatInputModule,
    MatAutocompleteModule,
    MatSelectModule,
    MatProgressSpinnerModule,
    MatIconModule,
  ],
  templateUrl: './new-task-dialog.component.html',
  styleUrl: './new-task-dialog.component.scss',
})
export class NewTaskDialogComponent implements OnInit {
  private readonly projectApi = inject(ProjectApiService)
  private readonly agentApi = inject(AgentApiService)
  private readonly dialogRef = inject(MatDialogRef<NewTaskDialogComponent>)

  // Form state
  protected readonly selectedProject = signal<string>('')
  protected readonly projectSearch = signal<string>('')
  protected readonly selectedAgent = signal<string>('')
  protected readonly agentSearch = signal<string>('')
  protected readonly mode = signal<'local' | 'worktree'>('local')
  protected readonly selectedBranch = signal<string>('')
  protected readonly branchSearch = signal<string>('')
  protected readonly issueNumber = signal<string>('')
  protected readonly branchType = signal<string>('feature')
  protected readonly task = signal<string>('')

  /** Whether the selected project has git integration (branches available) */
  protected readonly hasGit = signal(false)

  protected readonly branchTypes = ['feature', 'fix', 'refactor', 'chore', 'docs', 'build']

  // Data
  protected readonly projects = signal<string[]>([])
  protected readonly agents = signal<{ name: string; description: string }[]>([])
  protected readonly branches = signal<string[]>([])

  protected readonly filteredProjects = computed(() => {
    const search = this.projectSearch().toLowerCase()
    if (!search) return this.projects()
    return this.projects().filter((p) => p.toLowerCase().includes(search))
  })

  protected readonly filteredAgents = computed(() => {
    const search = this.agentSearch().toLowerCase()
    if (!search) return this.agents()
    return this.agents().filter(
      (a) => a.name.toLowerCase().includes(search) || a.description?.toLowerCase().includes(search)
    )
  })

  protected readonly filteredBranches = computed(() => {
    const search = this.branchSearch().toLowerCase()
    if (!search) return this.branches()
    return this.branches().filter((b) => b.toLowerCase().includes(search))
  })

  // UI state
  protected readonly isLoadingAgents = signal(false)
  protected readonly isLoadingBranches = signal(false)
  protected readonly isSubmitting = signal(false)

  protected readonly isWorktree = computed(() => this.mode() === 'worktree' && this.hasGit())

  protected readonly isValid = computed(() => {
    const projectValid = this.projects().includes(this.selectedProject())
    const agentValid = this.agents().some((a) => a.name === this.selectedAgent())
    const hasBase = projectValid && agentValid && !!this.task().trim()
    if (this.isWorktree())
      return hasBase && !!this.selectedBranch() && !!this.issueNumber().trim() && !!this.branchType()
    return hasBase
  })

  protected openPanel(trigger: MatAutocompleteTrigger): void {
    trigger.openPanel()
  }

  protected onProjectSearchInput(value: string): void {
    this.projectSearch.set(value)
    if (this.selectedProject() && value !== this.selectedProject()) {
      this.selectedProject.set('')
      this.selectedAgent.set('')
      this.agentSearch.set('')
      this.agents.set([])
    }
  }

  protected selectProject(name: string): void {
    this.selectedProject.set(name)
    this.projectSearch.set(name)
    this.selectedAgent.set('')
    this.agentSearch.set('')
    this.agents.set([])
    this.branches.set([])
    this.selectedBranch.set('')
    this.branchSearch.set('')
    this.hasGit.set(false)
    this.mode.set('local')

    this.isLoadingAgents.set(true)
    this.agentApi.getAgents(name).subscribe({
      next: (agents) => {
        this.agents.set(agents)
        this.isLoadingAgents.set(false)
      },
      error: () => {
        this.isLoadingAgents.set(false)
      },
    })

    // Check if project has git integration
    this.projectApi.getGitBranches(name).subscribe({
      next: ({ branches }) => {
        this.hasGit.set(branches.length > 0)
      },
      error: () => {
        this.hasGit.set(false)
      },
    })
  }

  protected onAgentSearchInput(value: string): void {
    this.agentSearch.set(value)
    if (this.selectedAgent() && value !== this.selectedAgent()) {
      this.selectedAgent.set('')
    }
  }

  protected selectAgent(name: string): void {
    this.selectedAgent.set(name)
    this.agentSearch.set(name)
  }

  protected onBranchSearchInput(value: string): void {
    this.branchSearch.set(value)
    if (this.selectedBranch() && value !== this.selectedBranch()) {
      this.selectedBranch.set('')
    }
  }

  protected selectBranch(name: string): void {
    this.selectedBranch.set(name)
    this.branchSearch.set(name)
  }

  ngOnInit(): void {
    this.projectApi.listProjects().subscribe({
      next: ({ projects }) => {
        const names = projects.map((p) => p.name)
        this.projects.set(names)
        if (names.length === 1 && names[0]) {
          this.selectProject(names[0])
        }
      },
    })
  }

  protected onProjectChange(projectName: string): void {
    this.selectProject(projectName)
  }

  protected onModeChange(mode: 'local' | 'worktree'): void {
    this.mode.set(mode)
    if (mode === 'worktree' && this.selectedProject() && this.branches().length === 0) {
      this.loadBranches()
    }
  }

  private loadBranches(): void {
    this.isLoadingBranches.set(true)
    this.projectApi.getGitBranches(this.selectedProject()).subscribe({
      next: ({ branches }) => {
        this.branches.set(branches)
        this.isLoadingBranches.set(false)
      },
      error: () => {
        this.isLoadingBranches.set(false)
      },
    })
  }

  protected submit(): void {
    if (!this.isValid() || this.isSubmitting()) return

    this.isSubmitting.set(true)

    const fullTask =
      this.isWorktree() && this.issueNumber().trim()
        ? `${this.issueNumber().trim()} ${this.task().trim()}`
        : this.task().trim()

    this.projectApi
      .createTask(
        this.selectedProject(),
        this.selectedAgent(),
        fullTask,
        this.isWorktree() ? 'worktree' : 'local',
        this.isWorktree() ? this.selectedBranch() : undefined,
        this.isWorktree() ? this.issueNumber().trim() : undefined,
        this.isWorktree() ? this.branchType() : undefined
      )
      .subscribe({
        next: ({ threadId, projectId }) => {
          this.dialogRef.close({ threadId, projectId })
        },
        error: () => {
          this.isSubmitting.set(false)
        },
      })
  }

  protected cancel(): void {
    this.dialogRef.close(null)
  }
}
