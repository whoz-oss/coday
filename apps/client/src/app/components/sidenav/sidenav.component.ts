import {
  Component,
  computed,
  ElementRef,
  EventEmitter,
  inject,
  Input,
  OnDestroy,
  OnInit,
  Output,
  signal,
  ViewChild,
} from '@angular/core'
import { Router } from '@angular/router'
import { FormsModule } from '@angular/forms'
import { Subject } from 'rxjs'
import { map, takeUntil } from 'rxjs/operators'
import { toSignal } from '@angular/core/rxjs-interop'
import { BreakpointObserver } from '@angular/cdk/layout'
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
import { ProjectStateService } from '../../core/services/project-state.service'
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

  /** Emits true when the sidenav opens, false when it closes. */
  @Output() sidenavStateChange = new EventEmitter<boolean>()

  /** When true, hides the floating FABs on mobile (right drawer is open). */
  @Input() drawerOpen = false

  private readonly breakpointObserver = inject(BreakpointObserver)

  /** True when viewport is >= 1400px. Used to hide the floating FABs (the inline close button is shown instead). */
  protected readonly isDesktop = toSignal(
    this.breakpointObserver.observe('(min-width: 1400px)').pipe(map((state) => state.matches)),
    { initialValue: false }
  )

  /** User's open/close preference — drives the sidenav state on all screen sizes. */
  private readonly userOpenPreference = signal(true)

  /** Effective open state — always driven by user preference, never locked. */
  protected readonly isOpen = computed(() => this.userOpenPreference())

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
  hasPreviewConfig = toSignal(
    this.projectStateService.selectedProject$.pipe(
      map((project) => {
        const preview = project?.config?.['preview']
        return Array.isArray(preview) && !!preview.length
      })
    ),
    { initialValue: false }
  )

  ngOnInit(): void {
    // Default: open on wide screens (>= 1024px), closed on smaller ones.
    // Overridden by the user's saved preference if it exists.
    const defaultOpen = window.innerWidth >= 1024
    const savedState = this.preferences.getPreference<boolean>('sidenavOpen', defaultOpen)
    this.userOpenPreference.set(savedState ?? defaultOpen)

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
    const next = !this.userOpenPreference()
    this.userOpenPreference.set(next)
    this.saveSidenavState(next)
    this.sidenavStateChange.emit(next)
  }

  close(): void {
    if (!this.userOpenPreference()) return
    this.userOpenPreference.set(false)
    this.saveSidenavState(false)
    this.sidenavStateChange.emit(false)
  }

  /**
   * Save sidenav state to preferences (mobile only)
   */
  private saveSidenavState(value: boolean): void {
    this.preferences.setPreference('sidenavOpen', value)
  }

  /**
   * Open user configuration editor
   */
  openTokenUsage(): void {
    this.configErrorMessage = ''
    void this.router.navigate(['/token-usage'])
  }

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
        },
        error: (error) => {
          console.error('[SIDENAV] Error saving project config:', error)
          this.configErrorMessage = error?.error?.error || 'Failed to save project configuration'
        },
      })
  }

  /**
   * Check if a project is selected and show error if not
   * @returns true if project is selected, false otherwise
   */
  private requireProjectSelection(context: string): boolean {
    const projectName = this.selectedProjectName()
    if (!projectName) {
      console.error(`[SIDENAV] No project selected, cannot ${context}`)
      this.configErrorMessage = 'Please select a project first'
      return false
    }
    return true
  }

  /**
   * Navigate to the agent list page
   */
  openAgents(): void {
    if (!this.requireProjectSelection('open agents')) {
      return
    }
    const projectName = this.selectedProjectName()
    this.router.navigate(['project', projectName, 'agents'])
  }

  /**
   * Navigate to the prompt list page
   */
  openPrompts(): void {
    if (!this.requireProjectSelection('open prompts')) {
      return
    }
    const projectName = this.selectedProjectName()
    this.router.navigate(['project', projectName, 'prompts'])
  }

  /**
   * Navigate to the scheduler list page
   */
  openSchedulers(): void {
    if (!this.requireProjectSelection('open schedulers')) {
      return
    }
    const projectName = this.selectedProjectName()
    this.router.navigate(['project', projectName, 'schedulers'])
  }

  /**
   * Toggle section expansion.
   *
   * - 'settings' is the top-level Control Center toggle.
   * - 'config' and 'preview' are sub-sections inside the settings group and
   *   follow accordion behaviour (only one open at a time within the group).
   */
  toggleSection(section: string): void {
    const wasExpanded = this.expandedSections[section]

    if (section === 'settings') {
      // Top-level: just toggle independently
      this.expandedSections[section] = !wasExpanded
    } else {
      // Sub-section inside settings group: accordion — close all siblings first
      const settingsSubSections = ['config', 'preview']
      settingsSubSections.forEach((key) => {
        this.expandedSections[key] = false
      })
      this.expandedSections[section] = !wasExpanded
    }
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
   * Close sidenav when a thread is selected on mobile.
   * On desktop (>= 1024 px) the sidenav stays open.
   */
  onThreadSelectedOnMobile(): void {
    if (!this.isDesktop()) {
      this.close()
    }
    // On desktop the sidenav stays open after thread selection (user preference preserved)
  }

  /**
   * Toggle thread search mode
   */
  toggleThreadSearch(event: Event): void {
    event.stopPropagation()
    this.isThreadSearchActive = true
    this.threadSearchQuery = ''

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
  }

  /**
   * Handle search input changes
   */
  onThreadSearchInput(): void {}

  /**
   * Handle search mode change from thread selector
   */
  onThreadSearchModeChange(isActive: boolean): void {
    if (!isActive) {
      this.closeThreadSearch()
    }
  }
}
