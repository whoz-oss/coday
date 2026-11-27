import { ComponentFixture, TestBed } from '@angular/core/testing'
import { ActivatedRoute, Router } from '@angular/router'
import { AgentConfig, AgentConfigControllerService } from '@whoz-oss/agentos-api-client'
import { of, throwError } from 'rxjs'
import { NamespaceAgentConfigsComponent } from './namespace-agent-configs.component'

/**
 * The Enabled column of the namespace agent-configs list: a switch on namespace rows that enables or disables
 * the agent in place, and a ticked, locked checkbox on the platform rows this page cannot change.
 */
describe('NamespaceAgentConfigsComponent (enabled column)', () => {
  let controller: {
    listByParentAgentConfig: jest.Mock
    listPlatformAgentsAgentConfig: jest.Mock
    enableAgentConfig: jest.Mock
    disableAgentConfig: jest.Mock
    deleteAgentConfig: jest.Mock
  }
  let fixture: ComponentFixture<NamespaceAgentConfigsComponent>

  const agent = (overrides: Partial<AgentConfig>): AgentConfig =>
    ({
      id: 'a-1',
      namespaceId: 'ns-1',
      name: 'Alpha',
      createdOn: '2026-01-01T00:00:00Z',
      updatedOn: '2026-01-01T00:00:00Z',
      enabled: true,
      ...overrides,
    }) as AgentConfig

  const root = () => fixture.nativeElement as HTMLElement
  const switchOf = (name: string) =>
    root().querySelector<HTMLInputElement>(`input[role="switch"][aria-label="Enabled: ${name}"]`)
  const alert = () => root().querySelector('.settings-table__error')

  function render(namespace: AgentConfig[], platform: AgentConfig[] = []): void {
    controller.listByParentAgentConfig.mockReturnValue(of(namespace))
    controller.listPlatformAgentsAgentConfig.mockReturnValue(of(platform))
    fixture = TestBed.createComponent(NamespaceAgentConfigsComponent)
    fixture.detectChanges()
  }

  function flip(input: HTMLInputElement): void {
    input.checked = !input.checked
    input.dispatchEvent(new Event('change'))
    fixture.detectChanges()
  }

  beforeEach(() => {
    controller = {
      listByParentAgentConfig: jest.fn(),
      listPlatformAgentsAgentConfig: jest.fn(),
      enableAgentConfig: jest.fn(),
      disableAgentConfig: jest.fn(),
      deleteAgentConfig: jest.fn(),
    }
    TestBed.configureTestingModule({
      imports: [NamespaceAgentConfigsComponent],
      providers: [
        { provide: AgentConfigControllerService, useValue: controller },
        { provide: ActivatedRoute, useValue: { snapshot: { params: { namespaceId: 'ns-1' } } } },
        { provide: Router, useValue: { navigate: jest.fn() } },
      ],
    })
  })

  it('renders an Enabled column with a checked switch for an enabled agent and an unchecked one otherwise', () => {
    render([agent({ id: 'a-1', name: 'Alpha', enabled: true }), agent({ id: 'a-2', name: 'Beta', enabled: false })])

    const headers = Array.from(root().querySelectorAll('th')).map((th) => th.textContent?.trim())
    expect(headers).toContain('Enabled')
    expect(switchOf('Alpha')?.checked).toBe(true)
    expect(switchOf('Beta')?.checked).toBe(false)
  })

  it('disables an enabled agent, and the row reflects the answer of the server', () => {
    controller.disableAgentConfig.mockReturnValue(of(agent({ id: 'a-1', name: 'Alpha', enabled: false })))
    render([agent({ id: 'a-1', name: 'Alpha', enabled: true })])

    flip(switchOf('Alpha')!)

    expect(controller.disableAgentConfig).toHaveBeenCalledWith('a-1')
    expect(controller.enableAgentConfig).not.toHaveBeenCalled()
    expect(switchOf('Alpha')?.checked).toBe(false)
    expect(switchOf('Alpha')?.disabled).toBe(false)
    expect(alert()).toBeNull()
  })

  it('enables a disabled agent', () => {
    controller.enableAgentConfig.mockReturnValue(of(agent({ id: 'a-2', name: 'Beta', enabled: true })))
    render([agent({ id: 'a-2', name: 'Beta', enabled: false })])

    flip(switchOf('Beta')!)

    expect(controller.enableAgentConfig).toHaveBeenCalledWith('a-2')
    expect(controller.disableAgentConfig).not.toHaveBeenCalled()
    expect(switchOf('Beta')?.checked).toBe(true)
  })

  it('shows the Enabled label next to a namespace switch', () => {
    render([agent({ id: 'a-2', name: 'Beta', enabled: false })])

    expect(switchOf('Beta')?.closest('label')?.textContent?.trim()).toBe('Enabled')
  })

  it('shows a platform agent as a ticked, locked checkbox', () => {
    render([], [agent({ id: 'p-1', namespaceId: undefined, name: 'Shared', enabled: true })])

    expect(root().querySelector('input[role="switch"]')).toBeNull()
    const cell = root().querySelector('td[data-label="Enabled"]')
    const checkbox = cell?.querySelector<HTMLInputElement>('input[type="checkbox"]')
    expect(checkbox).not.toBeNull()
    expect(checkbox?.checked).toBe(true)
    expect(checkbox?.disabled).toBe(true)
    expect(cell?.querySelector('ds-tag')).toBeNull()
    expect(cell?.textContent?.trim()).toBe('Enabled')
    checkbox?.dispatchEvent(new Event('change'))
    fixture.detectChanges()
    expect(controller.enableAgentConfig).not.toHaveBeenCalled()
    expect(controller.disableAgentConfig).not.toHaveBeenCalled()
  })

  it('puts the switch back and shows an alert when the request fails', () => {
    controller.disableAgentConfig.mockReturnValue(throwError(() => ({ status: 403 })))
    render([agent({ id: 'a-1', name: 'Alpha', enabled: true })])

    flip(switchOf('Alpha')!)

    expect(switchOf('Alpha')?.checked).toBe(true)
    expect(switchOf('Alpha')?.disabled).toBe(false)
    expect(alert()?.textContent).toContain('Disabling "Alpha" failed. Try again.')
  })

  it('clears the previous alert when the next toggle starts', () => {
    controller.disableAgentConfig.mockReturnValueOnce(throwError(() => ({ status: 500 })))
    render([agent({ id: 'a-1', name: 'Alpha', enabled: true })])
    flip(switchOf('Alpha')!)
    expect(alert()).not.toBeNull()

    controller.disableAgentConfig.mockReturnValue(of(agent({ id: 'a-1', name: 'Alpha', enabled: false })))
    flip(switchOf('Alpha')!)

    expect(alert()).toBeNull()
    expect(switchOf('Alpha')?.checked).toBe(false)
  })

  it('matches the filter on Disabled', () => {
    render([agent({ id: 'a-1', name: 'Alpha', enabled: true }), agent({ id: 'a-2', name: 'Beta', enabled: false })])

    const filter = root().querySelector<HTMLInputElement>('agentos-table-filter input')!
    filter.value = 'Disabled'
    filter.dispatchEvent(new Event('input'))
    fixture.detectChanges()

    expect(switchOf('Beta')).not.toBeNull()
    expect(switchOf('Alpha')).toBeNull()
  })
})
