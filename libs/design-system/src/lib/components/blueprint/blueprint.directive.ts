import { Directive, ElementRef, inject, OnDestroy, OnInit, Renderer2 } from '@angular/core'

/**
 * BlueprintDirective — injects 4 decorative corner accents into the host element.
 *
 * The host element must have `position: relative` (the directive sets it automatically).
 * Corner styles are defined globally in `apps/client/src/styles.scss` under `.blueprint-corner`.
 *
 * Usage:
 *   <div dsBlueprint class="my-card">...</div>
 *
 * The directive appends `.blueprint-corner.tl|tr|bl|br` spans as the first children,
 * so they sit behind the content in DOM order (no z-index needed).
 */
@Directive({
  selector: '[dsBlueprint]',
  host: { class: 'ds-blueprint' },
})
export class BlueprintDirective implements OnInit, OnDestroy {
  private readonly el = inject(ElementRef<HTMLElement>)
  private readonly renderer = inject(Renderer2)

  private corners: HTMLElement[] = []

  ngOnInit(): void {
    const host = this.el.nativeElement as HTMLElement

    // Ensure the host is relatively positioned so absolute corners land correctly.
    const currentPosition = getComputedStyle(host).position
    if (currentPosition === 'static') {
      this.renderer.setStyle(host, 'position', 'relative')
    }

    // Prepend corners so they are painted beneath content.
    for (const pos of ['tl', 'tr', 'bl', 'br'] as const) {
      const corner = this.renderer.createElement('span') as HTMLElement
      this.renderer.addClass(corner, 'blueprint-corner')
      this.renderer.addClass(corner, pos)
      this.renderer.setAttribute(corner, 'aria-hidden', 'true')
      // insertBefore(host, firstChild) = prepend
      this.renderer.insertBefore(host, corner, host.firstChild)
      this.corners.push(corner)
    }
  }

  ngOnDestroy(): void {
    for (const corner of this.corners) {
      this.renderer.removeChild(this.el.nativeElement, corner)
    }
    this.corners = []
  }
}
