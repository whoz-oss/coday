import { ApplicationRef, ComponentRef, createComponent, EnvironmentInjector } from '@angular/core'
import { TestBed } from '@angular/core/testing'
import { ShellSidebarComponent } from './shell-sidebar.component'

/**
 * Render a real ShellSidebarComponent with its required inputs set, so the template bindings of the
 * New case buttons can be asserted on the DOM.
 */
function makeComponentRef(newCaseDisabled?: boolean): ComponentRef<ShellSidebarComponent> {
  const environmentInjector = TestBed.inject(EnvironmentInjector)
  const appRef = TestBed.inject(ApplicationRef)

  const ref = createComponent(ShellSidebarComponent, { environmentInjector })
  appRef.attachView(ref.hostView)
  document.body.appendChild(ref.location.nativeElement)

  ref.setInput('sidebarWidth', 400)
  ref.setInput('sidebarOpen', true)
  ref.setInput('selectedNamespace', null)
  ref.setInput('namespaces', [])
  ref.setInput('showNsPicker', false)
  ref.setInput('nsMenuOpen', false)
  ref.setInput('cases', [])
  ref.setInput('activeCaseId', null)
  ref.setInput('userInitials', 'VP')
  ref.setInput('userName', 'Vincent Palita')
  ref.setInput('menuOpen', false)
  ref.setInput('isAdmin', false)
  ref.setInput('isDark', false)
  ref.setInput('themeVariant', 'industry')
  ref.setInput('showToolCalls', true)
  if (newCaseDisabled !== undefined) ref.setInput('newCaseDisabled', newCaseDisabled)
  ref.changeDetectorRef.detectChanges()

  return ref
}

function host(ref: ComponentRef<ShellSidebarComponent>): HTMLElement {
  return ref.location.nativeElement as HTMLElement
}

/** The expanded sidebar button, then (once compact) the rail button: both are the first `.btn-primary` of the host. */
function newCaseButton(ref: ComponentRef<ShellSidebarComponent>): HTMLButtonElement {
  return host(ref).querySelector<HTMLButtonElement>('button.btn-primary')!
}

/** Counts the createRequested emissions of the sidebar. */
function countCreateRequests(ref: ComponentRef<ShellSidebarComponent>): () => number {
  let count = 0
  ref.instance.createRequested.subscribe(() => count++)
  return () => count
}

describe('ShellSidebarComponent', () => {
  afterEach(() => {
    document.querySelectorAll('agentos-shell-sidebar').forEach((node) => node.remove())
    TestBed.resetTestingModule()
  })

  describe('New case button, expanded', () => {
    it('is enabled by default, titled New case, and emits createRequested on click', () => {
      const ref = makeComponentRef()
      const emissions = countCreateRequests(ref)

      const button = newCaseButton(ref)
      expect(button.classList).toContain('shell-sidebar__new-case-btn')
      expect(button.disabled).toBe(false)
      expect(button.title).toBe('New case')

      button.click()

      expect(emissions()).toBe(1)
    })

    it('is disabled with an explanatory title, and emits nothing, when newCaseDisabled is true', () => {
      const ref = makeComponentRef(true)
      const emissions = countCreateRequests(ref)

      const button = newCaseButton(ref)
      expect(button.disabled).toBe(true)
      expect(button.title).toBe('Already on a new case')

      button.click()

      expect(emissions()).toBe(0)
    })

    it('follows the input live', () => {
      const ref = makeComponentRef(true)
      expect(newCaseButton(ref).disabled).toBe(true)

      ref.setInput('newCaseDisabled', false)
      ref.changeDetectorRef.detectChanges()

      expect(newCaseButton(ref).disabled).toBe(false)
      expect(newCaseButton(ref).title).toBe('New case')
    })
  })

  describe('New case button, compact rail', () => {
    it.each([
      { disabled: true, title: 'Already on a new case' },
      { disabled: false, title: 'New case' },
    ])('follows newCaseDisabled=$disabled', ({ disabled, title }) => {
      const ref = makeComponentRef(disabled)
      ref.instance['toggleCompact']()
      ref.changeDetectorRef.detectChanges()
      const emissions = countCreateRequests(ref)

      const button = newCaseButton(ref)
      expect(button.classList).toContain('btn-icon')
      expect(button.disabled).toBe(disabled)
      expect(button.title).toBe(title)

      button.click()

      expect(emissions()).toBe(disabled ? 0 : 1)
    })
  })
})
