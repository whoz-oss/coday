import {Component, OnInit, OnDestroy, HostListener, inject} from '@angular/core'
import {CommonModule} from '@angular/common'
import {Subject} from 'rxjs'
import {takeUntil} from 'rxjs/operators'
import {SessionStateService} from '../../core/services/session-state.service'
import {ConfigApiService} from '../../core/services/config-api.service'
import {ThemeSelectorComponent} from '../theme-selector/theme-selector.component'
import {OptionsPanelComponent} from '../options-panel'
import {ProjectSelectorComponent} from '../project-selector/project-selector.component'
import {ThreadSelectorComponent} from '../thread-selector/thread-selector.component'
import {JsonEditorComponent} from '../json-editor/json-editor.component'

@Component({
  selector: 'app-floating-menu',
  standalone: true,
  imports: [CommonModule, ThemeSelectorComponent, OptionsPanelComponent, ProjectSelectorComponent, ThreadSelectorComponent, JsonEditorComponent],
  templateUrl: './floating-menu.component.html',
  styleUrl: './floating-menu.component.scss'
})
export class FloatingMenuComponent implements OnInit, OnDestroy {
  private destroy$ = new Subject<void>()
  isMenuOpen = false
  isUserConfigOpen = false
  isProjectConfigOpen = false

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

  // Modern Angular dependency injection
  private readonly sessionState = inject(SessionStateService)
  private readonly configApi = inject(ConfigApiService)

  ngOnInit(): void {
    // Log session state for debugging (ensures sessionState is used)
    console.log('[FLOATING-MENU] SessionState service injected:', !!this.sessionState)

    // Load user config to check roles
    this.configApi.getUserConfig()
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (config: any) => {
          // Check if user has CODAY_ADMIN role in temp_groups
          this.isAdmin = config.temp_groups?.includes('CODAY_ADMIN') ?? false
          console.log('[FLOATING-MENU] User admin status:', this.isAdmin)
        },
        error: (error) => {
          console.error('[FLOATING-MENU] Error loading user config for roles:', error)
          this.isAdmin = false
        }
      })
  }

  ngOnDestroy(): void {
    this.destroy$.next()
    this.destroy$.complete()
  }

  toggleMenu(): void {
    this.isMenuOpen = !this.isMenuOpen
  }

  closeMenu(): void {
    this.isMenuOpen = false
  }

  // Fermer le menu si on clique ailleurs
  @HostListener('document:click', ['$event'])
  onDocumentClick(event: Event): void {
    const target = event.target as HTMLElement
    if (!target.closest('.floating-menu-btn') && !target.closest('.floating-menu')) {
      this.closeMenu()
    }
  }

  // Fermer le menu avec Escape
  @HostListener('document:keydown.escape')
  onEscapeKey(): void {
    this.closeMenu()
  }

  /**
   * Open user configuration editor
   */
  openUserConfig(): void {
    this.closeMenu()
    this.isLoadingUserConfig = true
    this.configSuccessMessage = ''
    this.configErrorMessage = ''

    this.configApi.getUserConfig()
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (config) => {
          // Format JSON with 2-space indentation
          this.userConfigJson = JSON.stringify(config, null, 2)
          this.isLoadingUserConfig = false
          this.isUserConfigOpen = true
        },
        error: (error) => {
          console.error('[FLOATING-MENU] Error loading user config:', error)
          this.isLoadingUserConfig = false
          this.configErrorMessage = error?.error?.error || 'Failed to load user configuration'
        }
      })
  }

  /**
   * Open project configuration editor
   */
  openProjectConfig(): void {
    const projectName = this.getCurrentProjectName()
    this.closeMenu()
    if (!projectName) {
      console.error('[FLOATING-MENU] No project selected')
      this.configErrorMessage = 'No project selected. Please select a project first.'
      return
    }

    this.isLoadingProjectConfig = true
    this.configSuccessMessage = ''
    this.configErrorMessage = ''

    this.configApi.getProjectConfig(projectName)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (config) => {
          // Format JSON with 2-space indentation
          this.projectConfigJson = JSON.stringify(config, null, 2)
          this.isLoadingProjectConfig = false
          this.isProjectConfigOpen = true
        },
        error: (error) => {
          console.error('[FLOATING-MENU] Error loading project config:', error)
          this.isLoadingProjectConfig = false
          this.configErrorMessage = error?.error?.error || 'Failed to load project configuration'
        }
      })
  }

  /**
   * Check if a project is currently selected
   */
  hasProject(): boolean {
    return this.sessionState.hasProjectSelected()
  }

  /**
   * Get current project name
   */
  getCurrentProjectName(): string | null {
    return this.sessionState.getCurrentProject()
  }

  /**
   * Handle user config save
   */
  onUserConfigSave(parsedConfig: any): void {
    this.isSavingUserConfig = true
    this.configErrorMessage = ''

    this.configApi.updateUserConfig(parsedConfig)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (response) => {
          console.log('[FLOATING-MENU] User config saved successfully')
          this.isSavingUserConfig = false
          this.configSuccessMessage = response.message ?? 'Configuration saved successfully'

          // Close modal after short delay
          setTimeout(() => {
            this.isUserConfigOpen = false
            this.configSuccessMessage = ''
          }, 1500)
        },
        error: (error) => {
          console.error('[FLOATING-MENU] Error saving user config:', error)
          this.isSavingUserConfig = false
          this.configErrorMessage = error?.error?.error || 'Failed to save user configuration'
        }
      })
  }

  /**
   * Handle project config save
   */
  onProjectConfigSave(parsedConfig: any): void {
    const projectName = this.getCurrentProjectName()
    if (!projectName) {
      console.error('[FLOATING-MENU] No project selected')
      this.configErrorMessage = 'No project selected. Cannot save configuration.'
      return
    }

    this.isSavingProjectConfig = true
    this.configErrorMessage = ''

    this.configApi.updateProjectConfig(projectName, parsedConfig)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (response) => {
          console.log('[FLOATING-MENU] Project config saved successfully')
          this.isSavingProjectConfig = false
          this.configSuccessMessage = response.message ?? 'Configuration saved successfully'

          // Close modal after short delay
          setTimeout(() => {
            this.isProjectConfigOpen = false
            this.configSuccessMessage = ''
          }, 1500)
        },
        error: (error) => {
          console.error('[FLOATING-MENU] Error saving project config:', error)
          this.isSavingProjectConfig = false
          this.configErrorMessage = error?.error?.error || 'Failed to save project configuration'
        }
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
}