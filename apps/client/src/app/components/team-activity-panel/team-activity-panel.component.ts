import { Component, Output, EventEmitter, inject } from '@angular/core'
import { CommonModule } from '@angular/common'
import { MatIconModule } from '@angular/material/icon'
import { MatButtonModule } from '@angular/material/button'

import { CodayService, TeamState } from '../../core/services/coday.service'

/**
 * TeamActivityPanelComponent - Displays team activity and status
 *
 * Shows:
 * - Active teammates with their status (idle/working/stopped)
 * - Tasks and their progress
 *
 * This is the content component displayed inside the sliding panel overlay.
 */
@Component({
  selector: 'app-team-activity-panel',
  standalone: true,
  imports: [CommonModule, MatIconModule, MatButtonModule],
  templateUrl: './team-activity-panel.component.html',
  styleUrl: './team-activity-panel.component.scss',
})
export class TeamActivityPanelComponent {
  @Output() closePanel = new EventEmitter<void>()

  private readonly codayService = inject(CodayService)

  // Team state from service
  teamState: TeamState = { active: false, teamId: null, teammates: [], tasks: [] }

  constructor() {
    // Subscribe to team state
    this.codayService.teamState$.subscribe((state) => {
      this.teamState = state
    })
  }

  close(): void {
    this.closePanel.emit()
  }

  /**
   * Get status dot class based on teammate status
   */
  getStatusClass(status: string): string {
    switch (status) {
      case 'working':
        return 'status-working'
      case 'idle':
        return 'status-idle'
      case 'stopped':
        return 'status-stopped'
      default:
        return 'status-unknown'
    }
  }

  /**
   * Get task status icon
   */
  getTaskIcon(status: string): string {
    switch (status) {
      case 'completed':
        return '✅'
      case 'in_progress':
        return '🔄'
      case 'pending':
      default:
        return '⏳'
    }
  }

  /**
   * Get task status class for styling
   */
  getTaskClass(status: string): string {
    return `task-${status}`
  }

  /**
   * Truncate task description for display
   */
  truncateDescription(description: string, maxLength: number = 80): string {
    if (description.length <= maxLength) {
      return description
    }
    return description.substring(0, maxLength) + '...'
  }
}
