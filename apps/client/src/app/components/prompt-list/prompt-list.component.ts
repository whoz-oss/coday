import { Component, computed, inject, OnInit, signal } from '@angular/core'
import { Router } from '@angular/router'
import { MatButtonModule } from '@angular/material/button'
import { MatIconModule } from '@angular/material/icon'
import { MatSlideToggleModule } from '@angular/material/slide-toggle'
import { MatDialog } from '@angular/material/dialog'
import { FormsModule } from '@angular/forms'
import { CardActionsDirective, EntityListComponent, EntityListItem } from '@whoz-oss/design-system'
import { EntityCardComponent } from '@whoz-oss/design-system'
import { PromptApiService, PromptInfo } from '../../core/services/prompt-api.service'
import { ConfigApiService } from '../../core/services/config-api.service'
import { ProjectStateService } from '../../core/services/project-state.service'
import { PromptFormComponent, PromptFormData } from '../prompt-form/prompt-form.component'

const SYSTEM_OWNER = 'system'

/**
 * Full-page prompt list for a project.
 *
 * Route: /project/:projectName/prompts
 *
 * Prompts are grouped by owner (createdBy), current user first, system last.
 * The "Mine" toggle filters to the current user's prompts only.
 *
 * Race condition note: `showOnlyMine` starts as `false` so the list is
 * visible immediately. Once `currentUsername` resolves, the toggle becomes
 * meaningful and the user can enable it.
 */
@Component({
  selector: 'app-prompt-list',
  standalone: true,
  imports: [
    EntityListComponent,
    EntityCardComponent,
    CardActionsDirective,
    MatButtonModule,
    MatIconModule,
    MatSlideToggleModule,
    FormsModule,
  ],
  templateUrl: './prompt-list.component.html',
  styleUrl: './prompt-list.component.scss',
})
export class PromptListComponent implements OnInit {
  private readonly promptApi = inject(PromptApiService)
  private readonly configApi = inject(ConfigApiService)
  private readonly projectState = inject(ProjectStateService)
  private readonly dialog = inject(MatDialog)
  private readonly router = inject(Router)

  protected readonly prompts = signal<PromptInfo[]>([])
  protected readonly isLoading = signal(false)
  protected readonly errorMessage = signal<string | null>(null)
  // Starts true but the computed guard `!this.currentUsername()` prevents
  // filtering until the username resolves — no race condition.
  protected readonly showOnlyMine = signal(true)
  protected readonly isAdmin = signal(false)
  protected readonly currentUsername = signal('')

  /**
   * Prompts filtered by the "Mine" toggle, then mapped to EntityListItem
   * with groupKey = createdBy.
   *
   * Groups order: current user first (alphabetical), others alphabetical,
   * system always last.
   */
  protected readonly promptItems = computed<EntityListItem[]>(() => {
    const all = this.prompts()
    const username = this.currentUsername()
    const onlyMine = this.showOnlyMine()

    const visible = onlyMine && username ? all.filter((p) => this.isMyPrompt(p)) : all

    // Collect distinct owners in display order
    const ownerSet = new Set(visible.map((p) => p.createdBy))
    const owners = [...ownerSet].sort((a, b) => {
      // Current user first
      if (a === username && b !== username) return -1
      if (b === username && a !== username) return 1
      // System last
      if (a === SYSTEM_OWNER) return 1
      if (b === SYSTEM_OWNER) return -1
      return a.localeCompare(b)
    })

    const items: EntityListItem[] = []
    for (const owner of owners) {
      const label = owner === username ? `${owner} (you)` : owner
      for (const p of visible.filter((x) => x.createdBy === owner)) {
        items.push({
          id: p.id,
          name: p.name,
          description: p.description,
          badges: this.buildBadges(p),
          groupKey: owner,
          groupLabel: label,
        })
      }
    }
    return items
  })

  ngOnInit(): void {
    this.configApi.getUserConfig().subscribe({
      next: (config: any) => {
        this.isAdmin.set(config.groups?.includes('CODAY_ADMIN') ?? false)
        this.currentUsername.set(config.username ?? '')
      },
      error: () => this.isAdmin.set(false),
    })
    this.loadPrompts()
  }

  private loadPrompts(): void {
    this.isLoading.set(true)
    this.promptApi.listPrompts().subscribe({
      next: (prompts) => {
        this.prompts.set(prompts.filter((p) => !p.id.startsWith('builtin')))
        this.isLoading.set(false)
      },
      error: (err) => {
        this.errorMessage.set(err?.error?.error ?? 'Failed to load prompts')
        this.isLoading.set(false)
      },
    })
  }

  protected onBack(): void {
    const projectName = this.projectState.getSelectedProjectId()
    this.router.navigate(projectName ? ['project', projectName] : ['/'])
  }

  protected onCreate(): void {
    const dialogRef = this.dialog.open<PromptFormComponent, PromptFormData, boolean>(PromptFormComponent, {
      width: '700px',
      maxHeight: '90vh',
      data: { mode: 'create' },
    })
    dialogRef.afterClosed().subscribe((result) => {
      if (result) this.loadPrompts()
    })
  }

  protected onEdit(promptId: string): void {
    this.promptApi.getPrompt(promptId).subscribe({
      next: (prompt) => {
        const dialogRef = this.dialog.open<PromptFormComponent, PromptFormData, boolean>(PromptFormComponent, {
          width: '700px',
          maxHeight: '90vh',
          data: { mode: 'edit', prompt },
        })
        dialogRef.afterClosed().subscribe((result) => {
          if (result) this.loadPrompts()
        })
      },
      error: (err) => this.errorMessage.set(err?.error?.error ?? 'Failed to load prompt'),
    })
  }

  protected onDelete(promptId: string): void {
    if (!confirm('Are you sure you want to delete this prompt?')) return
    this.promptApi.deletePrompt(promptId).subscribe({
      next: () => this.loadPrompts(),
      error: (err) => this.errorMessage.set(err?.error?.error ?? 'Failed to delete prompt'),
    })
  }

  protected onToggleWebhook(promptId: string): void {
    const prompt = this.prompts().find((p) => p.id === promptId)
    if (!prompt) return
    const action = prompt.webhookEnabled
      ? this.promptApi.disableWebhook(promptId)
      : this.promptApi.enableWebhook(promptId)
    action.subscribe({
      next: () => this.loadPrompts(),
      error: (err) => this.errorMessage.set(err?.error?.error ?? 'Failed to toggle webhook'),
    })
  }

  protected isWebhookEnabled(promptId: string): boolean {
    return this.prompts().find((p) => p.id === promptId)?.webhookEnabled ?? false
  }

  private isMyPrompt(prompt: PromptInfo): boolean {
    const normalize = (s: string) => s.replace(/[.\s]+/g, '_').toLowerCase()
    return normalize(prompt.createdBy) === normalize(this.currentUsername())
  }

  private buildBadges(prompt: PromptInfo) {
    const badges = []
    if (prompt.source === 'local') {
      badges.push({ label: 'local', variant: 'info' as const })
    } else {
      badges.push({ label: 'project', variant: 'success' as const })
    }
    if (prompt.webhookEnabled) {
      badges.push({ label: 'webhook', variant: 'warning' as const })
    }
    return badges
  }
}
