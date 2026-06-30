import { BreakpointObserver } from '@angular/cdk/layout'
import {
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  EventEmitter,
  inject,
  Input,
  OnInit,
  Output,
  signal,
} from '@angular/core'
import { takeUntilDestroyed } from '@angular/core/rxjs-interop'
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
 * Responsive behaviour:
 *   - Above `mobileBreakpoint` (default 768px): mode="side" — drawer pushes content.
 *   - At or below `mobileBreakpoint`: mode="over" — drawer overlays content with a backdrop.
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
export class DrawerComponent implements OnInit {
  private readonly breakpointObserver = inject(BreakpointObserver)
  private readonly destroyRef = inject(DestroyRef)

  /** Whether the sidenav is open. Supports two-way binding via [(open)]. */
  @Input() open: boolean = true

  /** Side of the container the drawer is anchored to. Default: 'start' (left). */
  @Input() position: 'start' | 'end' = 'start'

  /** Width of the sidenav panel. */
  @Input() drawerWidth: string = '280px'

  /**
   * When true, the panel spans the full viewport width (100vw) in mobile overlay mode,
   * where a fixed `drawerWidth` would be too wide. Opt-in so existing drawers are unaffected.
   */
  @Input() mobileFullWidth: boolean = false

  /**
   * Viewport width threshold (px) below which the drawer switches to overlay mode.
   * Default: 768px.
   */
  @Input() mobileBreakpoint: number = 768

  /** Emitted when the open state changes (close on backdrop click or programmatic toggle). */
  @Output() openChange = new EventEmitter<boolean>()

  protected readonly isMobile = signal(false)
  protected readonly drawerMode = computed(() => (this.isMobile() ? 'over' : 'side'))

  ngOnInit(): void {
    this.breakpointObserver
      .observe([`(max-width: ${this.mobileBreakpoint - 1}px)`])
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((state) => this.isMobile.set(state.matches))
  }

  protected onOpenedChange(opened: boolean): void {
    this.openChange.emit(opened)
  }
}
