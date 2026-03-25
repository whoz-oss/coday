import { Component, ElementRef, EventEmitter, inject, Input, OnDestroy, OnInit, Output, ViewChild } from '@angular/core'
import { Router } from '@angular/router'
import { FormsModule } from '@angular/forms'
import { Subject } from 'rxjs'
import { map, takeUntil } from 'rxjs/operators'
import { MatSidenavModule } from '@angular/material/sidenav'
import { MatIconModule } from '@angular/material/icon'
import { MatButtonModule } from '@angular/material/button'
import { MatDividerModule } from '@angular/material/divider'
import { MatProgressBarModule } from '@angular/material/progress-bar'
import { MatDialog } from '@angular/material/dialog'
import { ConfigApiService } from '../../core/services/config-api.service'
import { PreferencesService } from '../../services/preferences.service'
import { OptionsPanelComponent } from '../options-panel'
import { ThreadSelectorComponent } from '../thread-selector/thread-selector.component'
import { PreviewPanelComponent } from '../preview-panel/preview-panel.component'
import { JsonEditorComponent, JsonEditorData } from '../json-editor/json-editor.component'
import { PromptManagerComponent } from '../prompt-manager/prompt-manager.component'
import { SchedulerManagerComponent } from '../scheduler-manager/scheduler-manager.component'
import { AgentManagerComponent } from '../agent-manager/agent-manager.component'
import { ProjectStateService } from '../../core/services/project-state.service'
import { toSignal } from '@angular/core/rxjs-interop'
import { ProjectApiService } from '../../core/services/project-api.service'
import { ThreadStateService } from '../../core/services/thread-state.service'

@Component({
  selector: 'app-sidenav',
  standalone: true,
  imports: [
    FormsModule,
    MatSidenavModule,
    MatIconModule,
    MatButtonModule,
    MatDividerModule,
    MatProgressBarModule,
    OptionsPanelComponent,
    ThreadSelectorComponent,
    PreviewPanelComponent,
  ],
  templateUrl: './sidenav.component.html',
  styleUrl: './sidenav.component.scss',
})
export class SidenavComponent implements OnInit, OnDestroy {
  private destroy$ = new Subject<void>()
  isOpen = true

  /** Emits true when the sidenav opens, false when it closes. */
  @Output() sidenavStateChange = new EventEmitter<boolean>()

  /** When true, hides the floating FABs on mobile (right drawer is open). */
  @Input() drawerOpen = false

  get screenWidth(): number {
    return window.innerWidth
  }

  // Role-based access control
  isAdmin = false

  // User feedback messages
  configErrorMessage = ''

  // Section expansion state — threads is always open, not tracked here
  expandedSections: Record<string, boolean> = {
    config: false,
    preview: false,
    settings: false,
  }

  // Thread search state
  isThreadSearchActive = false
  threadSearchQuery = ''

  @ViewChild('threadSearchInput') threadSearchInput?: ElementRef<HTMLInputElement>

  private readonly dialog = inject(MatDialog)
  private readonly configApi = inject(ConfigApiService)
  private readonly projectStateService = inject(ProjectStateService)
  private readonly projectApi = inject(ProjectApiService)
  private readonly router = inject(Router)
  private readonly preferences = inject(PreferencesService)
  private readonly threadStateService = inject(ThreadStateService)

  projects = toSignal(this.projectStateService.projectList$)
  selectedProject = toSignal(this.projectStateService.selectedProject$)
  selectedProjectName = toSignal(this.projectStateService.selectedProject$.pipe(map((project) => project?.name)))
  isProjectVolatile = toSignal(
    this.projectStateService.selectedProject$.pipe(map((project) => project?.config?.volatile || false))
  )
  forcedProject = toSignal(this.projectStateService.forcedProject$)
  hasPreviewConfig = toSignal(
    this.projectStateService.selectedProject$.pipe(map((project) => !!project?.config?.['preview']?.['command'])),
    { initialValue: false }
  )

  ngOnInit(): void {
    const savedState = this.preferences.getPreference<boolean>('sidenavOpen', true)
    this.isOpen = savedState ?? true
    console.log('[SIDENAV] Loaded sidenav state from preferences:', this.isOpen)

    this.configApi
      .getUserConfig()
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (config: any) => {
          this.isAdmin = config.groups?.includes('CODAY_ADMIN') ?? false
          console.log('[SIDENAV] User admin status:', this.isAdmin)
        },
        error: (error) => {
          console.error('[SIDENAV] Error loading user config for roles:', error)
          this.isAdmin = false
        },
      })
  }

  ngOnDestroy(): void {
    this.destroy$.next()
    this.destroy$.complete()
  }

  toggle(): void {
    this.isOpen = !this.isOpen
    this.saveSidenavState()
    this.sidenavStateChange.emit(this.isOpen)
  }

  close(): void {
    if (this.isOpen) {
      this.isOpen = false
      this.saveSidenavState()
      this.sidenavStateChange.emit(false)
    }
  }

  private saveSidenavState(): void {
    this.preferences.setPreference('sidenavOpen', this.isOpen)
    console.log('[SIDENAV] Saved sidenav state:', this.isOpen)
  }

  openTokenUsage(): void {
    this.configErrorMessage = ''
    void this.router.navigate(['/token-usage'])
    this.close()
  }

  openUserConfig(): void {
    this.configErrorMessage = ''
    this.configApi
      .getUserConfig()
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (config) => {
          const dialogRef = this.dialog.open<JsonEditorComponent, JsonEditorData, any>(JsonEditorComponent, {
            data: { configType: 'user', initialContent: JSON.stringify(config, null, 2) },
          })
          dialogRef.afterClosed().subscribe((result) => {
            if (result) this.saveUserConfig(result)
          })
        },
        error: (error) => {
          console.error('[SIDENAV] Error loading user config:', error)
          this.configErrorMessage = error?.error?.error || 'Failed to load user configuration'
        },
      })
  }

  private saveUserConfig(parsedConfig: any): void {
    this.configApi
      .updateUserConfig(parsedConfig)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (_) => {},
        error: (error) => {
          console.error('[SIDENAV] Error saving user config:', error)
          this.configErrorMessage = error?.error?.error || 'Failed to save user configuration'
        },
      })
  }

  openProjectConfig(): void {
    const projectName = this.selectedProjectName()
    if (!projectName) {
      this.configErrorMessage = 'No project selected. Please select a project first.'
      return
    }
    this.configErrorMessage = ''
    this.projectApi
      .getProjectConfig(projectName)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (config) => {
          const dialogRef = this.dialog.open<JsonEditorComponent, JsonEditorData, any>(JsonEditorComponent, {
            data: { configType: 'project', projectName, initialContent: JSON.stringify(config, null, 2) },
          })
          dialogRef.afterClosed().subscribe((result) => {
            if (result) this.saveProjectConfig(projectName, result)
          })
        },
        error: (error) => {
          console.error('[SIDENAV] Error loading project config:', error)
          this.configErrorMessage = error?.error?.error || 'Failed to load project configuration'
        },
      })
  }

  private saveProjectConfig(projectName: string, parsedConfig: any): void {
    this.projectApi
      .updateProjectConfig(projectName, parsedConfig)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (_) => {},
        error: (error) => {
          console.error('[SIDENAV] Error saving project config:', error)
          this.configErrorMessage = error?.error?.error || 'Failed to save project configuration'
        },
      })
  }

  private requireProjectSelection(context: string): boolean {
    if (!this.selectedProjectName()) {
      console.error(`[SIDENAV] No project selected, cannot ${context}`)
      this.configErrorMessage = 'Please select a project first'
      return false
    }
    return true
  }

  private openManagerDialog(component: any): void {
    this.dialog.open(component, {
      width: '90vw',
      maxWidth: '1200px',
      height: '90vh',
      maxHeight: '900px',
    })
  }

  openAgents(): void {
    if (this.requireProjectSelection('open agents')) this.openManagerDialog(AgentManagerComponent)
  }

  openPrompts(): void {
    if (this.requireProjectSelection('open prompts')) this.openManagerDialog(PromptManagerComponent)
  }

  openSchedulers(): void {
    if (this.requireProjectSelection('open schedulers')) this.openManagerDialog(SchedulerManagerComponent)
  }

  /**
   * Toggle section expansion.
   * - 'settings' is the top-level Control Center toggle.
   * - 'config' and 'preview' are sub-sections with accordion behaviour.
   */
  toggleSection(section: string): void {
    const wasExpanded = this.expandedSections[section]
    if (section === 'settings') {
      this.expandedSections[section] = !wasExpanded
    } else {
      // Accordion: close siblings first
      ;['config', 'preview'].forEach((key) => (this.expandedSections[key] = false))
      this.expandedSections[section] = !wasExpanded
    }
  }

  navigateToHome(): void {
    if (this.forcedProject()) return
    this.projectStateService.clearSelection()
    this.router.navigate(['/'])
  }

  createNewThread(): void {
    const projectName = this.selectedProjectName()
    if (!projectName) {
      this.configErrorMessage = 'Please select a project first'
      return
    }
    this.threadStateService.clearSelection()
    this.router.navigate(['project', projectName])
  }

  onThreadSelectedOnMobile(): void {
    if (window.innerWidth < 1024) this.close()
  }

  toggleThreadSearch(event: Event): void {
    event.stopPropagation()
    this.isThreadSearchActive = true
    this.threadSearchQuery = ''
    setTimeout(() => this.threadSearchInput?.nativeElement.focus(), 0)
  }

  closeThreadSearch(): void {
    this.isThreadSearchActive = false
    this.threadSearchQuery = ''
  }

  onThreadSearchInput(): void {}

  onThreadSearchModeChange(isActive: boolean): void {
    if (!isActive) this.closeThreadSearch()
  }
}
