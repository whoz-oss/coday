import { Component, OnInit, OnDestroy, inject } from '@angular/core'
import { CommonModule } from '@angular/common'
import { Subject } from 'rxjs'
import { takeUntil } from 'rxjs/operators'
import { SessionStateService } from '../../core/services/session-state.service'
import { CodayService } from '../../core/services/coday.service'
import { SessionState } from '@coday/model/session-state'

@Component({
  selector: 'app-project-selector',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './project-selector.component.html',
  styleUrl: './project-selector.component.scss'
})
export class ProjectSelectorComponent implements OnInit, OnDestroy {
  private destroy$ = new Subject<void>()
  
  // State from SessionStateService
  projects: SessionState['projects'] | null = null
  isExpanded = false
  
  // Modern Angular dependency injection
  private sessionState = inject(SessionStateService)
  private codayService = inject(CodayService)
  
  ngOnInit(): void {
    // Subscribe to projects state
    this.sessionState.getProjects$()
      .pipe(takeUntil(this.destroy$))
      .subscribe(projects => {
        console.log('[PROJECT-SELECTOR] Projects updated:', projects)
        this.projects = projects
      })
  }
  
  ngOnDestroy(): void {
    this.destroy$.next()
    this.destroy$.complete()
  }
  
  /**
   * Toggle the project dropdown
   */
  toggleDropdown(): void {
    if (this.canShowDropdown()) {
      this.isExpanded = !this.isExpanded
    }
  }
  
  /**
   * Close the dropdown
   */
  closeDropdown(): void {
    this.isExpanded = false
  }
  
  /**
   * Select a project
   */
  selectProject(projectName: string): void {
    console.log('[PROJECT-SELECTOR] Selecting project:', projectName)
    
    // Use the correct command format
    this.codayService.sendMessage(`config select-project ${projectName}`)
    
    // Close dropdown
    this.closeDropdown()
  }
  
  /**
   * Check if we can show the dropdown
   */
  canShowDropdown(): boolean {
    return !!(this.projects?.canCreate && this.projects?.list && this.projects.list.length > 0)
  }
  
  /**
   * Get display text for the current state
   */
  getDisplayText(): string {
    if (!this.projects) {
      return 'Chargement...'
    }
    
    if (this.projects.current) {
      return this.projects.current
    }
    
    if (!this.projects.canCreate) {
      return 'Projet verrouillé'
    }
    
    if (!this.projects.list || this.projects.list.length === 0) {
      return 'Aucun projet'
    }
    
    return 'Sélectionner un projet'
  }
  
  /**
   * Get the icon to display
   */
  getIcon(): string {
    if (!this.projects) {
      return '⏳'
    }
    
    if (this.projects.current) {
      return '📁' // Folder icon only when project is selected
    }
    
    if (!this.projects.canCreate) {
      return '🔒'
    }
    
    return '' // No icon when no project selected but can select
  }
  
  /**
   * Check if the selector is in a clickable state
   */
  isClickable(): boolean {
    return this.canShowDropdown()
  }
  
  /**
   * Get available projects excluding the current one
   */
  getAvailableProjects(): Array<{ name: string }> {
    if (!this.projects?.list) {
      return []
    }
    
    // Return all projects (including current one for now)
    // User can select the same project if they want
    return this.projects.list
  }
  
  /**
   * Track by function for project list
   */
  trackByProjectName(_index: number, project: { name: string }): string {
    return project.name
  }
  
  /**
   * Handle click outside to close dropdown
   */
  onDocumentClick(event: Event): void {
    const target = event.target as HTMLElement
    if (!target.closest('.project-selector')) {
      this.closeDropdown()
    }
  }
}