import { ChangeDetectionStrategy, Component, computed, inject, OnInit, signal } from '@angular/core'
import { FormsModule } from '@angular/forms'
import { MatAutocompleteModule } from '@angular/material/autocomplete'
import { MatButtonModule } from '@angular/material/button'
import { MatCheckboxModule } from '@angular/material/checkbox'
import { MatDialogModule, MatDialogRef, MAT_DIALOG_DATA } from '@angular/material/dialog'
import { MatFormFieldModule } from '@angular/material/form-field'
import { MatIconModule } from '@angular/material/icon'
import { MatInputModule } from '@angular/material/input'
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner'
import { MatSelectModule } from '@angular/material/select'
import { AgentApiService } from '../../core/services/agent-api.service'
import { ProjectApiService } from '../../core/services/project-api.service'
import { IntervalSchedule, SchedulerApiService } from '../../core/services/scheduler-api.service'

export interface QuickSchedulerDialogData {
  mode: 'create' | 'edit'
  projectName?: string
  scheduler?: {
    id: string
    name: string
    agentName?: string
    instruction?: string
    enabled: boolean
    schedule: IntervalSchedule
  }
}

/**
 * QuickSchedulerDialogComponent — simplified scheduler creation from the home page.
 *
 * Instead of selecting a pre-defined prompt, the user picks:
 * 1. A project
 * 2. An agent from that project
 * 3. A free-text instruction
 *
 * Plus the standard scheduling options (interval, days of week, end condition).
 * This creates a Scheduler with agentName + instruction instead of promptId.
 */
@Component({
  selector: 'app-quick-scheduler-dialog',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    FormsModule,
    MatDialogModule,
    MatButtonModule,
    MatFormFieldModule,
    MatInputModule,
    MatAutocompleteModule,
    MatSelectModule,
    MatCheckboxModule,
    MatProgressSpinnerModule,
    MatIconModule,
  ],
  templateUrl: './quick-scheduler-dialog.component.html',
  styleUrl: './quick-scheduler-dialog.component.scss',
})
export class QuickSchedulerDialogComponent implements OnInit {
  private readonly projectApi = inject(ProjectApiService)
  private readonly agentApi = inject(AgentApiService)
  private readonly schedulerApi = inject(SchedulerApiService)
  private readonly dialogRef = inject(MatDialogRef<QuickSchedulerDialogComponent>)
  private readonly dialogData = inject<QuickSchedulerDialogData | null>(MAT_DIALOG_DATA, { optional: true })

  protected readonly isEditMode = this.dialogData?.mode === 'edit'

  // ── Data ──────────────────────────────────────────────────────────────────
  protected readonly projects = signal<string[]>([])
  protected readonly agents = signal<{ name: string; description: string }[]>([])

  // ── Form fields ───────────────────────────────────────────────────────────
  protected readonly name = signal<string>('')
  protected readonly selectedProject = signal<string>('')
  protected readonly selectedAgent = signal<string>('')
  protected readonly instruction = signal<string>('')

  // Scheduling
  protected readonly intervalValue = signal<number>(1)
  protected readonly intervalUnit = signal<'min' | 'h' | 'd' | 'M'>('d')
  protected readonly startTimestamp = signal<string>('')
  protected readonly daysOfWeek = signal<number[]>([])
  protected readonly endConditionType = signal<'none' | 'occurrences' | 'endTimestamp'>('none')
  protected readonly endOccurrences = signal<number>(10)
  protected readonly endTimestamp = signal<string>('')
  protected readonly enabled = signal<boolean>(true)

  // ── UI state ──────────────────────────────────────────────────────────────
  protected readonly isLoadingAgents = signal<boolean>(false)
  protected readonly isSaving = signal<boolean>(false)
  protected readonly errorMessage = signal<string>('')

  protected readonly daysOfWeekOptions = [
    { value: 1, label: 'Mon' },
    { value: 2, label: 'Tue' },
    { value: 3, label: 'Wed' },
    { value: 4, label: 'Thu' },
    { value: 5, label: 'Fri' },
    { value: 6, label: 'Sat' },
    { value: 0, label: 'Sun' },
  ]

  protected readonly filteredProjects = computed(() => {
    const q = this.selectedProject().toLowerCase()
    return this.projects().filter((p) => p.toLowerCase().includes(q))
  })

  protected readonly filteredAgents = computed(() => {
    const q = this.selectedAgent().toLowerCase()
    return this.agents().filter((a) => a.name.toLowerCase().includes(q))
  })

  protected readonly isValid = computed(() => {
    const projectOk = this.projects().includes(this.selectedProject())
    const agentOk = this.agents().some((a) => a.name === this.selectedAgent())
    const instructionOk = this.instruction().trim().length > 0
    const intervalOk = this.intervalValue() >= 1
    return projectOk && agentOk && instructionOk && intervalOk
  })

  ngOnInit(): void {
    // En mode édition, pré-remplir les champs
    if (this.isEditMode && this.dialogData?.scheduler) {
      const s = this.dialogData.scheduler
      this.name.set(s.name)
      this.selectedAgent.set(s.agentName ?? '')
      this.instruction.set(s.instruction ?? '')
      this.enabled.set(s.enabled)

      const match = s.schedule.interval.match(/^(\d+)(min|h|d|M)$/)
      if (match && match[1] && match[2]) {
        this.intervalValue.set(parseInt(match[1], 10))
        this.intervalUnit.set(match[2] as 'min' | 'h' | 'd' | 'M')
      }
      if (s.schedule.startTimestamp) {
        this.startTimestamp.set(this.toDatetimeLocal(new Date(s.schedule.startTimestamp)))
      }
      this.daysOfWeek.set(s.schedule.daysOfWeek ?? [])
      if (s.schedule.endCondition) {
        this.endConditionType.set(s.schedule.endCondition.type)
        if (s.schedule.endCondition.type === 'occurrences') {
          this.endOccurrences.set(s.schedule.endCondition.value as number)
        } else if (s.schedule.endCondition.type === 'endTimestamp') {
          this.endTimestamp.set(this.toDatetimeLocal(new Date(s.schedule.endCondition.value as string)))
        }
      }
    } else {
      // En mode création, démarrer maintenant par défaut
      this.startTimestamp.set(this.toDatetimeLocal(new Date()))
    }

    this.projectApi.listProjects().subscribe({
      next: ({ projects }) => {
        const names = projects.map((p) => p.name).filter((n) => !n.includes('__'))
        this.projects.set(names)

        // En mode édition, sélectionner directement le projet
        const projectName = this.dialogData?.projectName
        if (this.isEditMode && projectName) {
          this.onProjectSelected(projectName)
        } else if (names.length === 1 && names[0]) {
          this.onProjectSelected(names[0])
        }
      },
    })
  }

  protected onProjectSelected(name: string): void {
    this.selectedProject.set(name)
    if (!this.isEditMode) {
      this.agents.set([])
      this.selectedAgent.set('')
    }

    if (!this.projects().includes(name)) return

    this.isLoadingAgents.set(true)
    this.agentApi.getAgents(name).subscribe({
      next: (agents) => {
        this.agents.set(agents)
        this.isLoadingAgents.set(false)
        // En mode création avec un seul agent, le sélectionner automatiquement
        if (!this.isEditMode && agents.length === 1 && agents[0]) {
          this.selectedAgent.set(agents[0].name)
        }
      },
      error: () => this.isLoadingAgents.set(false),
    })
  }

  protected toggleDay(day: number): void {
    const current = this.daysOfWeek()
    const idx = current.indexOf(day)
    if (idx > -1) {
      this.daysOfWeek.set(current.filter((d) => d !== day))
    } else {
      this.daysOfWeek.set([...current, day].sort())
    }
  }

  protected isDaySelected(day: number): boolean {
    return this.daysOfWeek().includes(day)
  }

  private toDatetimeLocal(date: Date): string {
    const pad = (n: number) => String(n).padStart(2, '0')
    return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`
  }

  protected submit(): void {
    if (!this.isValid() || this.isSaving()) return

    this.errorMessage.set('')
    this.isSaving.set(true)

    const startRaw = this.startTimestamp()
    const schedule: IntervalSchedule = {
      startTimestamp: startRaw ? new Date(startRaw).toISOString() : new Date().toISOString(),
      interval: `${this.intervalValue()}${this.intervalUnit()}`,
    }

    const days = this.daysOfWeek()
    if (days.length > 0) schedule.daysOfWeek = days

    const endType = this.endConditionType()
    if (endType === 'occurrences') {
      schedule.endCondition = { type: 'occurrences', value: this.endOccurrences() }
    } else if (endType === 'endTimestamp' && this.endTimestamp()) {
      schedule.endCondition = { type: 'endTimestamp', value: new Date(this.endTimestamp()).toISOString() }
    }

    const instr = this.instruction().trim()
    const shortInstruction = instr.length > 40 ? instr.slice(0, 40) + '…' : instr
    const name = this.name().trim() || `${this.selectedAgent()} — ${shortInstruction}`

    if (this.isEditMode && this.dialogData?.scheduler && this.dialogData.projectName) {
      this.schedulerApi
        .updateSchedulerForProject(this.dialogData.projectName, this.dialogData.scheduler.id, {
          name,
          agentName: this.selectedAgent(),
          instruction: instr,
          schedule,
          enabled: this.enabled(),
        })
        .subscribe({
          next: () => {
            this.isSaving.set(false)
            this.dialogRef.close(true)
          },
          error: (err) => {
            this.errorMessage.set(err?.error?.error || 'Failed to update scheduler')
            this.isSaving.set(false)
          },
        })
    } else {
      this.schedulerApi
        .createSchedulerForProject(this.selectedProject(), {
          name,
          agentName: this.selectedAgent(),
          instruction: instr,
          schedule,
          enabled: this.enabled(),
        })
        .subscribe({
          next: () => {
            this.isSaving.set(false)
            this.dialogRef.close(true)
          },
          error: (err) => {
            this.errorMessage.set(err?.error?.error || 'Failed to create scheduler')
            this.isSaving.set(false)
          },
        })
    }
  }

  protected cancel(): void {
    this.dialogRef.close(false)
  }
}
