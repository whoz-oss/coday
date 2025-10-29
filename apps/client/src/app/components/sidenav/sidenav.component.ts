import { Component, ElementRef, inject, OnDestroy, OnInit, ViewChild } from '@angular/core'
import { Router } from '@angular/router'
import { FormsModule } from '@angular/forms'
import { Subject } from 'rxjs'
import { map, takeUntil } from 'rxjs/operators'
import { MatSidenavModule } from '@angular/material/sidenav'
import { MatIconModule } from '@angular/material/icon'
import { MatButtonModule } from '@angular/material/button'
import { MatDividerModule } from '@angular/material/divider'
import { MatProgressBarModule } from '@angular/material/progress-bar'
import { ConfigApiService } from '../../core/services/config-api.service'
import { PreferencesService } from '../../services/preferences.service'
import { OptionsPanelComponent } from '../options-panel'
import { ThreadSelectorComponent } from '../thread-selector/thread-selector.component'
import { JsonEditorComponent } from '../json-editor/json-editor.component'
import { WebhookManagerComponent } from '../webhook-manager/webhook-manager.component'
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
    JsonEditorComponent,
    WebhookManagerComponent,
  ],
  templateUrl: './sidenav.component.html',
  styleUrl: './sidenav.component.scss',
})
export class SidenavComponent implements OnInit, OnDestroy {
  private destroy$ = new Subject<void>()
  isOpen = true
  isUserConfigOpen = false
  isProjectConfigOpen = false
  isWebhooksOpen = false

  // Role-based access control
  isAdmin = false

  // Configuration data
  userConfigJson = ''
  projectConfigJson = ''
  isLoadingUserConfig = false
  isLoadingProjectConfig = false
  isSavingUserConfig = false
  isSavingProjectConfig = false

  // User feedback messages
  configSuccessMessage = ''
  configErrorMessage = ''

  // Section expansion state
  expandedSections: Record<string, boolean> = {
    threads: true,
    config: false,
  }

  // Thread search state
  isThreadSearchActive = false
  threadSearchQuery = ''

  // ViewChild for search input
  @ViewChild('threadSearchInput') threadSearchInput?: ElementRef<HTMLInputElement>

  // Modern Angular dependency injection
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

  ngOnInit(): void {
    // Load saved sidenav state from preferences
    const savedState = this.preferences.getPreference<boolean>('sidenavOpen', true)
    this.isOpen = savedState ?? true
    console.log('[SIDENAV] Loaded sidenav state from preferences:', this.isOpen)

    // Load user config to check roles
    this.configApi
      .getUserConfig()
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (config: any) => {
          // Check if user has CODAY_ADMIN role in temp_groups
          this.isAdmin = config.temp_groups?.includes('CODAY_ADMIN') ?? false
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
  }

  close(): void {
    this.isOpen = false
    this.saveSidenavState()
  }

  /**
   * Save sidenav state to preferences
   */
  private saveSidenavState(): void {
    this.preferences.setPreference('sidenavOpen', this.isOpen)
    console.log('[SIDENAV] Saved sidenav state:', this.isOpen)
  }

  /**
   * Open user configuration editor
   */
  openUserConfig(): void {
    console.log('[SIDENAV] openUserConfig called')
    this.isLoadingUserConfig = true
    this.configSuccessMessage = ''
    this.configErrorMessage = ''

    this.configApi
      .getUserConfig()
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (config) => {
          console.log('[SIDENAV] User config loaded, opening modal')
          // Format JSON with 2-space indentation
          this.userConfigJson = JSON.stringify(config, null, 2)
          this.isLoadingUserConfig = false
          this.isUserConfigOpen = true
          console.log('[SIDENAV] isUserConfigOpen set to:', this.isUserConfigOpen)
        },
        error: (error) => {
          console.error('[SIDENAV] Error loading user config:', error)
          this.isLoadingUserConfig = false
          this.configErrorMessage = error?.error?.error || 'Failed to load user configuration'
        },
      })
  }

  /**
   * Open project configuration editor
   */
  openProjectConfig(): void {
    const projectName = this.selectedProjectName()
    if (!projectName) {
      console.error('[SIDENAV] No project selected')
      this.configErrorMessage = 'No project selected. Please select a project first.'
      return
    }

    this.isLoadingProjectConfig = true
    this.configSuccessMessage = ''
    this.configErrorMessage = ''

    this.projectApi
      .getProjectConfig(projectName)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (config) => {
          // Format JSON with 2-space indentation
          this.projectConfigJson = JSON.stringify(config, null, 2)
          this.isLoadingProjectConfig = false
          this.isProjectConfigOpen = true
        },
        error: (error) => {
          console.error('[SIDENAV] Error loading project config:', error)
          this.isLoadingProjectConfig = false
          this.configErrorMessage = error?.error?.error || 'Failed to load project configuration'
        },
      })
  }

  /**
   * Handle user config save
   */
  onUserConfigSave(parsedConfig: any): void {
    this.isSavingUserConfig = true
    this.configErrorMessage = ''

    this.configApi
      .updateUserConfig(parsedConfig)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (response) => {
          console.log('[SIDENAV] User config saved successfully')
          this.isSavingUserConfig = false
          this.configSuccessMessage = response.message ?? 'Configuration saved successfully'

          // Close modal after short delay
          setTimeout(() => {
            this.isUserConfigOpen = false
            this.configSuccessMessage = ''
          }, 1500)
        },
        error: (error) => {
          console.error('[SIDENAV] Error saving user config:', error)
          this.isSavingUserConfig = false
          this.configErrorMessage = error?.error?.error || 'Failed to save user configuration'
        },
      })
  }

  /**
   * Handle project config save
   */
  onProjectConfigSave(parsedConfig: any): void {
    const projectName = this.selectedProjectName()
    if (!projectName) {
      console.error('[SIDENAV] No project selected')
      this.configErrorMessage = 'No project selected. Cannot save configuration.'
      return
    }

    this.isSavingProjectConfig = true
    this.configErrorMessage = ''

    this.projectApi
      .updateProjectConfig(projectName, parsedConfig)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (response) => {
          console.log('[SIDENAV] Project config saved successfully')
          this.isSavingProjectConfig = false
          this.configSuccessMessage = response.message ?? 'Configuration saved successfully'

          // Close modal after short delay
          setTimeout(() => {
            this.isProjectConfigOpen = false
            this.configSuccessMessage = ''
          }, 1500)
        },
        error: (error) => {
          console.error('[SIDENAV] Error saving project config:', error)
          this.isSavingProjectConfig = false
          this.configErrorMessage = error?.error?.error || 'Failed to save project configuration'
        },
      })
  }

  /**
   * Handle user config cancel
   */
  onUserConfigCancel(): void {
    this.isUserConfigOpen = false
    this.configSuccessMessage = ''
    this.configErrorMessage = ''
  }

  /**
   * Handle project config cancel
   */
  onProjectConfigCancel(): void {
    this.isProjectConfigOpen = false
    this.configSuccessMessage = ''
    this.configErrorMessage = ''
  }

  /**
   * Open webhook manager
   */
  openWebhooks(): void {
    this.isWebhooksOpen = true
  }

  /**
   * Get available projects for webhook form
   */
  getAvailableProjects(): string[] {
    // TODO: Get from session state when project list is available
    return []
  }

  /**
   * Toggle section expansion (accordion behavior - closes others)
   */
  toggleSection(section: string): void {
    const wasExpanded = this.expandedSections[section]

    // Close all sections first (accordion behavior)
    Object.keys(this.expandedSections).forEach((key) => {
      this.expandedSections[key] = false
    })

    // Toggle the clicked section
    this.expandedSections[section] = !wasExpanded
  }

  /**
   * Navigate to home (project selection)
   * Only allowed if no forced project
   */
  navigateToHome(): void {
    // Don't allow navigation if there's a forced project
    if (this.forcedProject()) {
      console.log('[SIDENAV] Cannot navigate to home - forced project active')
      return
    }

    console.log('[SIDENAV] Navigating to home (project selection)')
    // Clear project selection and navigate to home
    this.projectStateService.clearSelection()
    this.router.navigate(['/'])
  }

  /**
   * Create a new thread - Navigate to welcome view
   */
  createNewThread(): void {
    const projectName = this.selectedProjectName()
    if (!projectName) {
      console.error('[SIDENAV] No project selected, cannot create new thread')
      this.configErrorMessage = 'Please select a project first'
      return
    }

    console.log('[SIDENAV] Navigating to welcome view for new thread')
    // Clear the selected thread so the sidenav doesn't show any thread as selected
    this.threadStateService.clearSelection()
    // Navigate to project route without threadId to show welcome view
    this.router.navigate(['project', projectName])
  }

  /**
   * Toggle thread search mode
   */
  toggleThreadSearch(event: Event): void {
    event.stopPropagation()
    this.isThreadSearchActive = true
    this.threadSearchQuery = ''
    console.log('[SIDENAV] Thread search active:', this.isThreadSearchActive)

    // Focus the input after the view updates
    setTimeout(() => {
      this.threadSearchInput?.nativeElement.focus()
    }, 0)
  }

  /**
   * Close thread search mode
   */
  closeThreadSearch(): void {
    this.isThreadSearchActive = false
    this.threadSearchQuery = ''
    console.log('[SIDENAV] Thread search closed')
  }

  /**
   * Handle search input changes
   */
  onThreadSearchInput(): void {
    // Trigger change detection
    console.log('[SIDENAV] Search query:', this.threadSearchQuery)
  }

  /**
   * Handle search mode change from thread selector
   */
  onThreadSearchModeChange(isActive: boolean): void {
    if (!isActive) {
      this.closeThreadSearch()
    }
  }
}
