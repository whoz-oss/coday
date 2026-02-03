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
import { MatDialog } from '@angular/material/dialog'
import { ConfigApiService } from '../../core/services/config-api.service'
import { PreferencesService } from '../../services/preferences.service'
import { OptionsPanelComponent } from '../options-panel'
import { ThreadSelectorComponent } from '../thread-selector/thread-selector.component'
import { JsonEditorComponent, JsonEditorData } from '../json-editor/json-editor.component'
import { WebhookManagerComponent } from '../webhook-manager/webhook-manager.component'
import { TriggerManagerComponent } from '../trigger-manager/trigger-manager.component'
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
  ],
  templateUrl: './sidenav.component.html',
  styleUrl: './sidenav.component.scss',
})
export class SidenavComponent implements OnInit, OnDestroy {
  private destroy$ = new Subject<void>()
  isOpen = true

  // Role-based access control
  isAdmin = false

  // User feedback messages
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
          // Check if user has CODAY_ADMIN role in groups
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
    this.configErrorMessage = ''

    this.configApi
      .getUserConfig()
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (config) => {
          console.log('[SIDENAV] User config loaded, opening dialog')
          const dialogRef = this.dialog.open<JsonEditorComponent, JsonEditorData, any>(JsonEditorComponent, {
            data: {
              configType: 'user',
              initialContent: JSON.stringify(config, null, 2),
            },
          })

          dialogRef.afterClosed().subscribe((result) => {
            if (result) {
              this.saveUserConfig(result)
            }
          })
        },
        error: (error) => {
          console.error('[SIDENAV] Error loading user config:', error)
          this.configErrorMessage = error?.error?.error || 'Failed to load user configuration'
        },
      })
  }

  /**
   * Save user configuration
   */
  private saveUserConfig(parsedConfig: any): void {
    this.configApi
      .updateUserConfig(parsedConfig)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (_) => {
          console.log('[SIDENAV] User config saved successfully')
          // Could show a success notification here
        },
        error: (error) => {
          console.error('[SIDENAV] Error saving user config:', error)
          this.configErrorMessage = error?.error?.error || 'Failed to save user configuration'
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

    this.configErrorMessage = ''

    this.projectApi
      .getProjectConfig(projectName)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (config) => {
          console.log('[SIDENAV] Project config loaded, opening dialog')
          const dialogRef = this.dialog.open<JsonEditorComponent, JsonEditorData, any>(JsonEditorComponent, {
            data: {
              configType: 'project',
              projectName,
              initialContent: JSON.stringify(config, null, 2),
            },
          })

          dialogRef.afterClosed().subscribe((result) => {
            if (result) {
              this.saveProjectConfig(projectName, result)
            }
          })
        },
        error: (error) => {
          console.error('[SIDENAV] Error loading project config:', error)
          this.configErrorMessage = error?.error?.error || 'Failed to load project configuration'
        },
      })
  }

  /**
   * Save project configuration
   */
  private saveProjectConfig(projectName: string, parsedConfig: any): void {
    this.projectApi
      .updateProjectConfig(projectName, parsedConfig)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (_) => {
          console.log('[SIDENAV] Project config saved successfully')
          // Could show a success notification here
        },
        error: (error) => {
          console.error('[SIDENAV] Error saving project config:', error)
          this.configErrorMessage = error?.error?.error || 'Failed to save project configuration'
        },
      })
  }

  /**
   * Open webhook manager dialog
   */
  openWebhooks(): void {
    console.log('[SIDENAV] Opening webhook manager dialog')
    this.dialog.open(WebhookManagerComponent)
  }

  /**
   * Open trigger manager dialog (Scheduler)
   * Available for all users if a project is selected
   */
  openTriggers(): void {
    const projectName = this.selectedProjectName()
    if (!projectName) {
      console.error('[SIDENAV] No project selected, cannot open triggers')
      this.configErrorMessage = 'Please select a project first'
      return
    }

    console.log('[SIDENAV] Opening trigger manager dialog for project:', projectName)
    this.dialog.open(TriggerManagerComponent, {
      width: '90vw',
      maxWidth: '1200px',
      height: '90vh',
      maxHeight: '900px',
    })
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
