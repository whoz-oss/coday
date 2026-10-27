import { signal } from '@angular/core'
import { TestBed } from '@angular/core/testing'
import { Namespace } from '@whoz-oss/agentos-api-client'
import { UsageConfigurationService } from '../../services/usage-configuration.service'
import { NamespaceItemComponent } from './namespace-item.component'

describe('NamespaceItemComponent', () => {
  const namespace = { id: 'ns', name: 'Test namespace' } as Namespace
  let usageEnabled: ReturnType<typeof signal<boolean>>

  beforeEach(() => {
    usageEnabled = signal(false)
    TestBed.configureTestingModule({
      imports: [NamespaceItemComponent],
      providers: [{ provide: UsageConfigurationService, useValue: { enabled: usageEnabled } }],
    })
  })

  function render(gitAvailable = false) {
    const fixture = TestBed.createComponent(NamespaceItemComponent)
    fixture.componentRef.setInput('namespace', namespace)
    fixture.componentRef.setInput('gitAvailable', gitAvailable)
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

  it('places Members and conditionally Git and Usage in the kebab contract', () => {
    usageEnabled.set(true)
    const fixture = render(true)
    const labels = fixture.componentInstance['menuItems']().map((item) => item.label)
    expect(labels).toEqual(expect.arrayContaining(['Members', 'Git', 'Usage and costs']))
  })

  it('omits unavailable Git and Usage menu actions', () => {
    const labels = render()
      .componentInstance['menuItems']()
      .map((item) => item.label)
    expect(labels).toContain('Members')
    expect(labels).not.toEqual(expect.arrayContaining(['Git', 'Usage and costs']))
  })

  it.each([
    ['members', 'membersRequested'],
    ['git', 'gitRequested'],
    ['usage', 'usageRequested'],
  ])('emits the namespace for the %s menu action', (key, output) => {
    usageEnabled.set(true)
    const component = render(true).componentInstance
    const emit = jest.fn()
    ;(
      component[output as keyof NamespaceItemComponent] as { subscribe: (handler: (value: Namespace) => void) => void }
    ).subscribe(emit)
    component['onMenuAction'](key)
    expect(emit).toHaveBeenCalledWith(namespace)
  })
})
