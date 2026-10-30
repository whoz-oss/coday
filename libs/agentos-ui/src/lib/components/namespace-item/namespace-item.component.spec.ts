import { TestBed } from '@angular/core/testing'
import { Namespace } from '@whoz-oss/agentos-api-client'
import { NamespaceItemComponent } from './namespace-item.component'

describe('NamespaceItemComponent', () => {
  const namespace = { id: 'ns', name: 'Test namespace' } as Namespace

  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [NamespaceItemComponent] })
  })

  function render() {
    const fixture = TestBed.createComponent(NamespaceItemComponent)
    fixture.componentRef.setInput('namespace', namespace)
    fixture.detectChanges()
    return fixture
  }

  it('exposes only Open and Configure as visible card actions', () => {
    const buttons = Array.from(render().nativeElement.querySelectorAll('button')).map((button) =>
      button.textContent?.trim()
    )
    expect(buttons).toEqual(expect.arrayContaining(['Open', 'Configure']))
    expect(buttons).not.toEqual(expect.arrayContaining(['Members', 'Git', 'Usage and costs']))
  })

  it('keeps only the destructive action in the kebab, settings live in the settings layout', () => {
    const labels = render().componentInstance['menuItems'].map((item) => item.label)
    expect(labels).toEqual(['Delete namespace'])
  })

  it('asks for confirmation before emitting deleteRequested', () => {
    const component = render().componentInstance
    const emit = jest.fn()
    component.deleteRequested.subscribe(emit)

    component['onMenuAction']('delete')
    expect(component['pendingDelete']()).toBe(true)
    expect(emit).not.toHaveBeenCalled()

    component['onDeleteConfirmed']()
    expect(component['pendingDelete']()).toBe(false)
    expect(emit).toHaveBeenCalledWith(namespace)
  })

  it('cancels a pending deletion without emitting', () => {
    const component = render().componentInstance
    const emit = jest.fn()
    component.deleteRequested.subscribe(emit)

    component['onMenuAction']('delete')
    component['onDeleteCancelled']()

    expect(component['pendingDelete']()).toBe(false)
    expect(emit).not.toHaveBeenCalled()
  })
})
