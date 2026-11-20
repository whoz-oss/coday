import { BreakpointObserver } from '@angular/cdk/layout'
import {
  afterRenderEffect,
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  ElementRef,
  inject,
  input,
  model,
  OnInit,
  output,
  signal,
  viewChild,
} from '@angular/core'
import { takeUntilDestroyed } from '@angular/core/rxjs-interop'
import { MatSidenavContainer, MatSidenavModule } from '@angular/material/sidenav'

/** Step (px) of a keyboard resize (ArrowLeft / ArrowRight) on the handle. */
export const DRAWER_KEYBOARD_STEP = 16

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
 * Resizing (opt-in, desktop only): with `resizable`, a drag handle sits on the inner edge of the panel. The
 * parent owns the width: it binds `drawerWidth` and listens to `widthChange` (px, emitted once at the end of a
 * drag, or on each keyboard step) to store the new value.
 *
 * CSS contract: --color-border, --glass-bg, --glass-backdrop-blur, --color-divider, --color-accent
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
  private readonly hostEl = inject<ElementRef<HTMLElement>>(ElementRef)
  private readonly container = viewChild(MatSidenavContainer)

  /** Whether the sidenav is open. Supports two-way binding via [(open)] (openChange is auto-provided). */
  readonly open = model<boolean>(true)

  /** Side of the container the drawer is anchored to. Default: 'start' (left). */
  readonly position = input<'start' | 'end'>('start')

  /** Width of the sidenav panel. */
  readonly drawerWidth = input<string>('280px')

  /**
   * When true, the panel spans the full viewport width (100vw) in mobile overlay mode,
   * where a fixed `drawerWidth` would be too wide. Opt-in so existing drawers are unaffected.
   */
  readonly mobileFullWidth = input<boolean>(false)

  /**
   * Viewport width threshold (px) below which the drawer switches to overlay mode.
   * Default: 768px.
   */
  readonly mobileBreakpoint = input<number>(768)

  /** Shows a drag handle on the inner edge to resize the panel (not in mobile overlay mode). Default: false. */
  readonly resizable = input<boolean>(false)

  /** Smallest width (px) the panel can be resized to. */
  readonly minWidth = input<number>(320)

  /** Largest width of the panel, as a ratio of the viewport width. */
  readonly maxWidthRatio = input<number>(0.8)

  /** New width (px), emitted once when a drag ends and on each keyboard resize step. */
  readonly widthChange = output<number>()

  protected readonly isMobile = signal(false)
  protected readonly drawerMode = computed(() => (this.isMobile() ? 'over' : 'side'))
  protected readonly showHandle = computed(() => this.resizable() && !this.isMobile())

  /** Current width in px (from `drawerWidth`), for the a11y values of the handle and the keyboard steps. */
  protected readonly currentWidth = computed(() => parseFloat(this.drawerWidth()) || this.minWidth())

  /** Upper bound in px (never below the minimum, on a narrow viewport). */
  protected maxWidth(): number {
    return Math.max(this.minWidth(), Math.floor(window.innerWidth * this.maxWidthRatio()))
  }

  private clamp(width: number): number {
    return Math.max(this.minWidth(), Math.min(this.maxWidth(), width))
  }

  /** Removes the listeners of a drag in progress, if any. */
  private stopDrag: (() => void) | null = null

  constructor() {
    // mat-sidenav-container only measures its panels when they open or close: a width that changes while the
    // panel stays open (resize) has to ask for the content margin to be recomputed.
    afterRenderEffect(() => {
      this.drawerWidth()
      this.container()?.updateContentMargins()
    })
  }

  ngOnInit(): void {
    this.breakpointObserver
      .observe([`(max-width: ${this.mobileBreakpoint() - 1}px)`])
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((state) => this.isMobile.set(state.matches))
    this.destroyRef.onDestroy(() => this.stopDrag?.())
  }

  protected onOpenedChange(opened: boolean): void {
    this.open.set(opened)
  }

  /**
   * Starts a drag session. The width of the panel is written straight to the DOM during the drag (no change
   * detection per pixel, transition off) and committed once on mouseup through `widthChange`.
   * The handle sits on the inner edge, so for a panel anchored at the end, moving left makes it wider.
   */
  protected onResizeStart(event: MouseEvent): void {
    event.preventDefault()
    this.stopDrag?.()

    const sidenav = this.hostEl.nativeElement.querySelector<HTMLElement>('.ds-drawer__sidenav')
    const direction = this.position() === 'end' ? -1 : 1
    const startX = event.clientX
    const startWidth = sidenav?.getBoundingClientRect().width || this.currentWidth()
    let width = this.clamp(startWidth)

    if (sidenav) sidenav.style.transition = 'none'
    document.body.style.userSelect = 'none'
    document.body.style.cursor = 'col-resize'

    const onMove = (e: MouseEvent): void => {
      width = this.clamp(startWidth + direction * (e.clientX - startX))
      if (sidenav) sidenav.style.width = `${width}px`
      // The content margin follows the panel, which is resized behind Angular's back.
      this.container()?.updateContentMargins()
    }

    const cleanup = (): void => {
      document.removeEventListener('mousemove', onMove)
      document.removeEventListener('mouseup', onUp)
      document.body.style.userSelect = ''
      document.body.style.cursor = ''
      if (sidenav) sidenav.style.transition = ''
      this.stopDrag = null
    }

    const onUp = (): void => {
      cleanup()
      // The inline width is dropped: the parent's binding takes over with the committed value.
      if (sidenav) sidenav.style.width = ''
      this.widthChange.emit(width)
    }

    this.stopDrag = () => {
      cleanup()
      if (sidenav) sidenav.style.width = ''
    }
    document.addEventListener('mousemove', onMove)
    document.addEventListener('mouseup', onUp)
  }

  /** ArrowLeft / ArrowRight move the inner edge by one step; the direction follows the side of the panel. */
  protected onResizeKeydown(event: KeyboardEvent): void {
    if (event.key !== 'ArrowLeft' && event.key !== 'ArrowRight') return
    event.preventDefault()
    // Moving the handle to the right widens a panel at the start and narrows one at the end.
    const grows = this.position() === 'end' ? event.key === 'ArrowLeft' : event.key === 'ArrowRight'
    const next = this.clamp(this.currentWidth() + (grows ? DRAWER_KEYBOARD_STEP : -DRAWER_KEYBOARD_STEP))
    this.widthChange.emit(next)
  }
}
