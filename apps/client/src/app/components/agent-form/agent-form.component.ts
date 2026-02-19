import { Component, inject, OnInit } from '@angular/core'
import { CommonModule } from '@angular/common'
import { FormsModule } from '@angular/forms'
import { MatDialogModule, MatDialogRef, MAT_DIALOG_DATA } from '@angular/material/dialog'
import { MatButtonModule } from '@angular/material/button'
import { MatInputModule } from '@angular/material/input'
import { MatFormFieldModule } from '@angular/material/form-field'
import { MatIconModule } from '@angular/material/icon'
import { MatSelectModule } from '@angular/material/select'
import { MatSnackBarModule } from '@angular/material/snack-bar'
import {
  AgentCrudApiService,
  AgentDefinition,
  AgentLocation,
  AgentWithMeta,
} from '../../core/services/agent-crud-api.service'
import { AgentApiService } from '../../core/services/agent-api.service'

export interface AgentFormData {
  mode: 'create' | 'edit'
  agent?: AgentWithMeta
}

@Component({
  selector: 'app-agent-form',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    MatDialogModule,
    MatButtonModule,
    MatInputModule,
    MatFormFieldModule,
    MatIconModule,
    MatSelectModule,
    MatSnackBarModule,
  ],
  templateUrl: './agent-form.component.html',
  styleUrls: ['./agent-form.component.scss'],
})
export class AgentFormComponent implements OnInit {
  private readonly agentCrudApi = inject(AgentCrudApiService)
  private readonly agentApi = inject(AgentApiService)
  private readonly dialogRef = inject(MatDialogRef<AgentFormComponent>)
  readonly data = inject<AgentFormData>(MAT_DIALOG_DATA)

  // Identity
  name = ''
  description = ''
  instructions = ''

  // AI configuration
  aiProvider = ''
  modelName = ''
  temperature: number | null = null
  maxOutputTokens: number | null = null
  openaiAssistantId = ''

  // Integrations: list of { integration, tools } rows
  integrationRows: Array<{ integration: string; tools: string }> = []

  // Location (creation only)
  location: AgentLocation = 'project'

  // UI state
  isSaving = false
  errorMessage = ''
  isEditMode = false

  ngOnInit(): void {
    this.isEditMode = this.data.mode === 'edit'

    if (this.isEditMode && this.data.agent) {
      const def = this.data.agent.definition
      this.name = def.name
      this.description = def.description
      this.instructions = def.instructions ?? ''
      this.aiProvider = def.aiProvider ?? ''
      this.modelName = def.modelName ?? ''
      this.temperature = def.temperature ?? null
      this.maxOutputTokens = def.maxOutputTokens ?? null
      this.openaiAssistantId = def.openaiAssistantId ?? ''
      this.location = this.data.agent.source

      if (def.integrations) {
        this.integrationRows = Object.entries(def.integrations).map(([integration, tools]) => ({
          integration,
          tools: tools?.join(', ') ?? '',
        }))
      }
    }
  }

  addIntegrationRow(): void {
    this.integrationRows.push({ integration: '', tools: '' })
  }

  removeIntegrationRow(index: number): void {
    this.integrationRows.splice(index, 1)
  }

  trackByIndex(index: number): number {
    return index
  }

  private validateForm(): boolean {
    if (!this.name.trim()) {
      this.errorMessage = 'Name is required'
      return false
    }
    if (!/^[a-zA-Z0-9][a-zA-Z0-9_-]*$/.test(this.name.trim())) {
      this.errorMessage = 'Name must start with alphanumeric and contain only letters, digits, hyphens or underscores'
      return false
    }
    if (!this.description.trim()) {
      this.errorMessage = 'Description is required'
      return false
    }
    for (const row of this.integrationRows) {
      if (!row.integration.trim()) {
        this.errorMessage = 'Integration name cannot be empty'
        return false
      }
    }
    return true
  }

  private buildDefinition(): AgentDefinition {
    const integrations: Record<string, string[]> | undefined =
      this.integrationRows.length > 0
        ? Object.fromEntries(
            this.integrationRows
              .filter((row) => row.integration.trim())
              .map((row) => [
                row.integration.trim(),
                row.tools
                  .split(',')
                  .map((t) => t.trim())
                  .filter(Boolean),
              ])
          )
        : undefined

    return {
      name: this.name.trim(),
      description: this.description.trim(),
      instructions: this.instructions.trim() || undefined,
      aiProvider: this.aiProvider.trim() || undefined,
      modelName: this.modelName.trim() || undefined,
      temperature: this.temperature ?? undefined,
      maxOutputTokens: this.maxOutputTokens ?? undefined,
      openaiAssistantId: this.openaiAssistantId.trim() || undefined,
      integrations,
    }
  }

  save(): void {
    this.errorMessage = ''
    if (!this.validateForm()) return

    const definition = this.buildDefinition()
    this.isSaving = true

    const operation = this.isEditMode
      ? this.agentCrudApi.updateAgent(this.name.trim(), definition)
      : this.agentCrudApi.createAgent(definition, this.location)

    operation.subscribe({
      next: () => {
        this.isSaving = false
        // Invalidate autocomplete cache so the new/updated agent appears in suggestions
        this.agentApi.clearAllCache()
        this.dialogRef.close(true)
      },
      error: (error) => {
        this.isSaving = false
        this.errorMessage = error?.error?.error || error?.message || 'Failed to save agent'
      },
    })
  }

  cancel(): void {
    this.dialogRef.close(false)
  }
}
