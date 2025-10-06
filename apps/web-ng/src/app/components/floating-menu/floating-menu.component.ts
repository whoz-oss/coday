import { Component, OnInit, OnDestroy, HostListener, inject } from '@angular/core'
import { CommonModule } from '@angular/common'
import { Subject } from 'rxjs'
import { SessionStateService } from '../../core/services/session-state.service'
import { ConfigApiService } from '../../core/services/config-api.service'
import { ThemeSelectorComponent } from '../theme-selector/theme-selector.component'
import { OptionsPanelComponent } from '../options-panel/options-panel.component'
import { ProjectSelectorComponent } from '../project-selector/project-selector.component'
import { ThreadSelectorComponent } from '../thread-selector/thread-selector.component'
import { JsonEditorComponent } from '../json-editor/json-editor.component'

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
  
  // Configuration data
  userConfigJson = ''
  projectConfigJson = ''
  isLoadingUserConfig = false
  isLoadingProjectConfig = false
  isSavingUserConfig = false
  isSavingProjectConfig = false
  
  // Success message for user feedback
  configSuccessMessage = ''

  // Modern Angular dependency injection
  private sessionState = inject(SessionStateService)
  private configApi = inject(ConfigApiService)

  ngOnInit(): void {
    // Log session state for debugging (ensures sessionState is used)
    console.log('[FLOATING-MENU] SessionState service injected:', !!this.sessionState)
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
    
    this.configApi.getUserConfig().subscribe({
      next: (config) => {
        // Format JSON with 2-space indentation
        this.userConfigJson = JSON.stringify(config, null, 2)
        this.isLoadingUserConfig = false
        this.isUserConfigOpen = true
      },
      error: (error) => {
        console.error('[FLOATING-MENU] Error loading user config:', error)
        this.isLoadingUserConfig = false
        // Could show error message to user
      }
    })
  }
  
  /**
   * Open project configuration editor
   */
  openProjectConfig(): void {
    const projectName = this.getCurrentProjectName()
    if (!projectName) {
      console.error('[FLOATING-MENU] No project selected')
      return
    }
    
    this.closeMenu()
    this.isLoadingProjectConfig = true
    this.configSuccessMessage = ''
    
    this.configApi.getProjectConfig(projectName).subscribe({
      next: (config) => {
        // Format JSON with 2-space indentation
        this.projectConfigJson = JSON.stringify(config, null, 2)
        this.isLoadingProjectConfig = false
        this.isProjectConfigOpen = true
      },
      error: (error) => {
        console.error('[FLOATING-MENU] Error loading project config:', error)
        this.isLoadingProjectConfig = false
        // Could show error message to user
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
    
    this.configApi.updateUserConfig(parsedConfig).subscribe({
      next: (response) => {
        console.log('[FLOATING-MENU] User config saved successfully')
        this.isSavingUserConfig = false
        this.configSuccessMessage = response.message || 'Configuration saved successfully'
        
        // Close modal after short delay
        setTimeout(() => {
          this.isUserConfigOpen = false
          this.configSuccessMessage = ''
        }, 1500)
      },
      error: (error) => {
        console.error('[FLOATING-MENU] Error saving user config:', error)
        this.isSavingUserConfig = false
        // Error will be shown by the API response
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
      return
    }
    
    this.isSavingProjectConfig = true
    
    this.configApi.updateProjectConfig(projectName, parsedConfig).subscribe({
      next: (response) => {
        console.log('[FLOATING-MENU] Project config saved successfully')
        this.isSavingProjectConfig = false
        this.configSuccessMessage = response.message || 'Configuration saved successfully'
        
        // Close modal after short delay
        setTimeout(() => {
          this.isProjectConfigOpen = false
          this.configSuccessMessage = ''
        }, 1500)
      },
      error: (error) => {
        console.error('[FLOATING-MENU] Error saving project config:', error)
        this.isSavingProjectConfig = false
        // Error will be shown by the API response
      }
    })
  }
  
  /**
   * Handle user config cancel
   */
  onUserConfigCancel(): void {
    this.isUserConfigOpen = false
    this.configSuccessMessage = ''
  }
  
  /**
   * Handle project config cancel
   */
  onProjectConfigCancel(): void {
    this.isProjectConfigOpen = false
    this.configSuccessMessage = ''
  }
}