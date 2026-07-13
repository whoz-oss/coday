import { ChangeDetectionStrategy, Component, input, output } from '@angular/core'

/**
 * ShellUserMenuComponent — user context menu overlay.
 *
 * Shared between desktop sidebar and mobile topbar.
 * Handles its own backdrop + action list.
 * The parent controls visibility via the `open` signal input.
 */
@Component({
  selector: 'agentos-shell-user-menu',
  templateUrl: './shell-user-menu.component.html',
  styleUrl: './shell-user-menu.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ShellUserMenuComponent {
  /** Whether the menu is currently visible */
  readonly open = input.required<boolean>()

  /** Whether the current user has admin rights */
  readonly isAdmin = input.required<boolean>()

  /** Whether dark mode is active */
  readonly isDark = input.required<boolean>()

  /** Whether technical logs are currently shown */
  readonly showTechnical = input.required<boolean>()

  /**
   * Visual variant:
   * - 'desktop': absolute positioned relative to the user button in the sidebar
   * - 'mobile': absolute positioned relative to the mobile topbar
   */
  readonly variant = input<'desktop' | 'mobile'>('desktop')

  /** Emits the route path to navigate to */
  readonly navigateTo = output<string>()

  /** Emits when the user toggles the theme */
  readonly themeToggled = output<void>()

  /** Emits when the user toggles technical logs */
  readonly logsToggled = output<void>()

  /** Emits when the backdrop is clicked or Escape is pressed */
  readonly closed = output<Event>()

  protected onNavigate(path: string): void {
    this.navigateTo.emit(path)
  }

  protected onThemeToggle(): void {
    this.themeToggled.emit()
  }

  protected onLogsToggle(): void {
    this.logsToggled.emit()
  }

  protected onClose(event: Event): void {
    event.stopPropagation()
    this.closed.emit(event)
  }
}
