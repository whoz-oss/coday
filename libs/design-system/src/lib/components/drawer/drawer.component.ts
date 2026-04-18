import { ChangeDetectionStrategy, Component, EventEmitter, Input, Output } from '@angular/core'
import { MatSidenavModule } from '@angular/material/sidenav'

/**
 * DsDrawer — a collapsible side drawer wrapping mat-sidenav.
 *
 * Provides two named content slots via ng-content:
 *   [dsDrawerSide]    — content rendered inside the sidenav panel
 *   [dsDrawerContent] — main content area rendered beside the sidenav
 *
 * The open state is controlled externally via two-way binding:
 *   <ds-drawer [(open)]="drawerOpen" drawerWidth="300px">
 *
 * CSS contract: --color-border, --glass-bg, --glass-backdrop-blur
 *
 * @example
 * <ds-drawer [(open)]="open" drawerWidth="300px">
 *   <div dsDrawerSide>Navigation here</div>
 *   <div dsDrawerContent><router-outlet /></div>
 * </ds-drawer>
 */
@Component({
  selector: 'ds-drawer',
  standalone: true,
  imports: [MatSidenavModule],
  templateUrl: './drawer.component.html',
  styleUrl: './drawer.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DrawerComponent {
  /** Whether the sidenav is open. Supports two-way binding via [(open)]. */
  @Input() open: boolean = true

  /** Width of the sidenav panel. */
  @Input() drawerWidth: string = '280px'

  /** Emitted when the open state changes (close on backdrop click or programmatic toggle). */
  @Output() openChange = new EventEmitter<boolean>()

  protected onOpenedChange(opened: boolean): void {
    this.openChange.emit(opened)
  }
}
