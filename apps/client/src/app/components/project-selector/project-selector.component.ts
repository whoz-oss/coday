import { Component, EventEmitter, inject, OnDestroy, OnInit, Output } from '@angular/core'
import { Subject } from 'rxjs'
import { CodayService } from '../../core/services/coday.service'
import { SessionState } from '@coday/model/session-state'

@Component({
  selector: 'app-project-selector',
  standalone: true,
  imports: [],
  templateUrl: './project-selector.component.html',
  styleUrl: './project-selector.component.scss',
})
export class ProjectSelectorComponent implements OnInit, OnDestroy {
  private destroy$ = new Subject<void>()

  // State from SessionStateService
  projects: SessionState['projects'] | null = null
  isExpanded = false

  // Output event when a project is selected
  @Output() projectSelected = new EventEmitter<string>()

  // Modern Angular dependency injection
  private codayService = inject(CodayService)

  ngOnInit(): void {
    // Subscribe to projects state
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

    // Emit event
    this.projectSelected.emit(projectName)

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
      return 'Loading...'
    }

    if (this.projects.current) {
      return this.projects.current
    }

    if (!this.projects.canCreate) {
      return 'Projects locked'
    }

    if (!this.projects.list || this.projects.list.length === 0) {
      return 'No projet'
    }

    return 'Select a project'
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
