import { BreakpointObserver } from '@angular/cdk/layout'
import { Component, signal } from '@angular/core'
import { TestBed } from '@angular/core/testing'
import { BehaviorSubject } from 'rxjs'
import { DRAWER_KEYBOARD_STEP, DrawerComponent } from './drawer.component'

@Component({
  standalone: true,
  imports: [DrawerComponent],
  template: `
    <ds-drawer
      [position]="position()"
      [drawerWidth]="width() + 'px'"
      [resizable]="resizable()"
      [minWidth]="300"
      (widthChange)="changes.push($event)"
    >
      <div dsDrawerSide>side</div>
      <div dsDrawerContent>content</div>
    </ds-drawer>
  `,
})
class HostComponent {
  readonly position = signal<'start' | 'end'>('end')
  readonly width = signal(500)
  readonly resizable = signal(true)
  readonly changes: number[] = []
}

describe('DrawerComponent resize', () => {
  let mobile$: BehaviorSubject<{ matches: boolean }>

  function setup(inputs: { mobile?: boolean; resizable?: boolean; position?: 'start' | 'end' } = {}) {
    mobile$ = new BehaviorSubject({ matches: inputs.mobile ?? false })
    TestBed.configureTestingModule({
      imports: [HostComponent],
      providers: [{ provide: BreakpointObserver, useValue: { observe: () => mobile$ } }],
    })
    const fixture = TestBed.createComponent(HostComponent)
    fixture.componentInstance.resizable.set(inputs.resizable ?? true)
    fixture.componentInstance.position.set(inputs.position ?? 'end')
    fixture.detectChanges()
    const el = fixture.nativeElement as HTMLElement
    const handle = () => el.querySelector<HTMLElement>('.ds-drawer__resize-handle')
    return { fixture, host: fixture.componentInstance, el, handle }
  }

  const key = (handle: HTMLElement, name: string) =>
    handle.dispatchEvent(new KeyboardEvent('keydown', { key: name, bubbles: true, cancelable: true }))

  beforeEach(() => {
    Object.defineProperty(window, 'innerWidth', { value: 1000, configurable: true, writable: true })
  })

  it('has no handle by default, so existing drawers are unaffected', () => {
    const { handle } = setup({ resizable: false })
    expect(handle()).toBeNull()
  })

  it('has no handle in mobile overlay mode, and gets it back on a wide viewport', () => {
    const { fixture, handle } = setup({ mobile: true })
    expect(handle()).toBeNull()
    mobile$.next({ matches: false })
    fixture.detectChanges()
    expect(handle()).not.toBeNull()
  })

  it('renders an accessible vertical separator with its values', () => {
    const { handle } = setup()
    const el = handle()!
    expect(el.getAttribute('role')).toBe('separator')
    expect(el.getAttribute('aria-orientation')).toBe('vertical')
    expect(el.getAttribute('aria-label')).toBe('Resize panel')
    expect(el.getAttribute('tabindex')).toBe('0')
    expect(el.getAttribute('aria-valuenow')).toBe('500')
    expect(el.getAttribute('aria-valuemin')).toBe('300')
    expect(el.getAttribute('aria-valuemax')).toBe('800')
  })

  it('sits on the inner edge: left for a panel at the end, right for one at the start', () => {
    expect(setup({ position: 'end' }).handle()!.classList).toContain('ds-drawer__resize-handle--end')
    TestBed.resetTestingModule()
    expect(setup({ position: 'start' }).handle()!.classList).not.toContain('ds-drawer__resize-handle--end')
  })

  it('widens a panel at the end with ArrowLeft and narrows it with ArrowRight', () => {
    const { handle, host } = setup({ position: 'end' })
    key(handle()!, 'ArrowLeft')
    key(handle()!, 'ArrowRight')
    expect(host.changes).toEqual([500 + DRAWER_KEYBOARD_STEP, 500 - DRAWER_KEYBOARD_STEP])
  })

  it('widens a panel at the start with ArrowRight and narrows it with ArrowLeft', () => {
    const { handle, host } = setup({ position: 'start' })
    key(handle()!, 'ArrowRight')
    key(handle()!, 'ArrowLeft')
    expect(host.changes).toEqual([500 + DRAWER_KEYBOARD_STEP, 500 - DRAWER_KEYBOARD_STEP])
  })

  it('clamps keyboard resizes between the minimum and the ratio of the viewport', () => {
    const { fixture, handle, host } = setup({ position: 'end' })
    host.width.set(305)
    fixture.detectChanges()
    key(handle()!, 'ArrowRight')
    host.width.set(795)
    fixture.detectChanges()
    key(handle()!, 'ArrowLeft')
    expect(host.changes).toEqual([300, 800])
  })

  it('ignores the other keys', () => {
    const { handle, host } = setup()
    const event = new KeyboardEvent('keydown', { key: 'ArrowUp', cancelable: true })
    handle()!.dispatchEvent(event)
    expect(host.changes).toEqual([])
    expect(event.defaultPrevented).toBe(false)
  })

  describe('dragging', () => {
    const sidenavOf = (el: HTMLElement) => el.querySelector<HTMLElement>('.ds-drawer__sidenav')!
    const mouse = (type: string, clientX: number) => document.dispatchEvent(new MouseEvent(type, { clientX }))

    function startDrag(el: HTMLElement, handle: HTMLElement, from: number) {
      jest.spyOn(sidenavOf(el), 'getBoundingClientRect').mockReturnValue({ width: 500 } as DOMRect)
      handle.dispatchEvent(new MouseEvent('mousedown', { clientX: from, bubbles: true, cancelable: true }))
    }

    it('grows a panel at the end when the pointer moves left, writing the DOM directly and emitting once on release', () => {
      const { el, handle, host } = setup({ position: 'end' })
      startDrag(el, handle()!, 600)
      expect(document.body.style.cursor).toBe('col-resize')
      expect(document.body.style.userSelect).toBe('none')
      expect(sidenavOf(el).style.transition).toBe('none')

      mouse('mousemove', 550)
      expect(sidenavOf(el).style.width).toBe('550px')
      expect(host.changes).toEqual([])

      mouse('mouseup', 550)
      expect(host.changes).toEqual([550])
      expect(document.body.style.cursor).toBe('')
      expect(document.body.style.userSelect).toBe('')
      expect(sidenavOf(el).style.transition).toBe('')
      expect(sidenavOf(el).style.width).not.toBe('550px')
    })

    it('grows a panel at the start when the pointer moves right', () => {
      const { el, handle, host } = setup({ position: 'start' })
      startDrag(el, handle()!, 500)
      mouse('mousemove', 540)
      mouse('mouseup', 540)
      expect(host.changes).toEqual([540])
    })

    it('clamps the dragged width', () => {
      const { el, handle, host } = setup({ position: 'end' })
      startDrag(el, handle()!, 600)
      mouse('mousemove', 0)
      mouse('mouseup', 0)
      mouse('mouseup', 0)
      expect(host.changes).toEqual([800])

      startDrag(el, handle()!, 0)
      mouse('mousemove', 900)
      mouse('mouseup', 900)
      expect(host.changes).toEqual([800, 300])
    })

    it('stops listening once released', () => {
      const { el, handle } = setup({ position: 'end' })
      startDrag(el, handle()!, 600)
      mouse('mouseup', 600)
      sidenavOf(el).style.width = '123px'
      mouse('mousemove', 100)
      expect(sidenavOf(el).style.width).toBe('123px')
    })

    it('cleans up a drag in progress when destroyed', () => {
      const { fixture, el, handle, host } = setup({ position: 'end' })
      startDrag(el, handle()!, 600)
      fixture.destroy()
      expect(document.body.style.cursor).toBe('')
      expect(document.body.style.userSelect).toBe('')
      mouse('mouseup', 400)
      expect(host.changes).toEqual([])
    })
  })
})
