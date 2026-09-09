import { ComponentFixture, TestBed } from '@angular/core/testing'
import {
  IntegrationConfigToolPreview,
  IntegrationConfigToolPreviewToolConfirmationModeEnum,
} from '@whoz-oss/agentos-api-client'
import { IntegrationToolPreviewComponent } from './integration-tool-preview.component'

describe('IntegrationToolPreviewComponent', () => {
  let fixture: ComponentFixture<IntegrationToolPreviewComponent>

  const preview: IntegrationConfigToolPreview = {
    integrationType: 'MCP_HTTP',
    configName: 'MCP_PROD',
    namespaceDescription: '2 tools exposed by the MCP server',
    tools: [
      {
        name: 'MCP_PROD__ListTickets',
        description: 'Lists tickets',
        inputSchema: '{"type":"object","properties":{"status":{"type":"string"}}}',
        confirmationMode: IntegrationConfigToolPreviewToolConfirmationModeEnum.NONE,
      },
      {
        name: 'MCP_PROD__UpdateTicket',
        description: 'Updates a ticket',
        inputSchema: 'not json',
        confirmationMode: IntegrationConfigToolPreviewToolConfirmationModeEnum.EVERY_TIME,
      },
    ],
  }

  function render(value: IntegrationConfigToolPreview): HTMLElement {
    fixture = TestBed.createComponent(IntegrationToolPreviewComponent)
    fixture.componentRef.setInput('preview', value)
    fixture.detectChanges()
    return fixture.nativeElement as HTMLElement
  }

  function texts(host: HTMLElement, selector: string): string[] {
    return Array.from(host.querySelectorAll(selector)).map((el) => (el.textContent ?? '').trim())
  }

  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [IntegrationToolPreviewComponent] })
  })

  it('renders the namespace line, the tool names in code style and their descriptions', () => {
    const host = render(preview)

    expect(host.querySelector('.tool-preview__namespace')?.textContent).toContain('2 tools exposed')
    expect(texts(host, 'code.tool-preview__name')).toEqual(['MCP_PROD__ListTickets', 'MCP_PROD__UpdateTicket'])
    expect(texts(host, '.tool-preview__description')).toEqual(['Lists tickets', 'Updates a ticket'])
    expect(host.querySelector('.tool-preview__error')).toBeNull()
  })

  it('flags tools that require a confirmation', () => {
    const host = render(preview)
    expect(texts(host, '.tool-preview__badge')).toEqual(['EVERY_TIME'])
  })

  it('shows the input schema of a tool only once expanded, pretty-printed when it is JSON', () => {
    const host = render(preview)
    expect(host.querySelector('pre.tool-preview__schema')).toBeNull()

    const toggles = host.querySelectorAll<HTMLButtonElement>('.tool-preview__toggle')
    toggles[0].click()
    fixture.detectChanges()

    const schema = host.querySelector('pre.tool-preview__schema')?.textContent ?? ''
    expect(schema).toContain('"status": {')
    expect(toggles[0].getAttribute('aria-expanded')).toBe('true')

    toggles[0].click()
    fixture.detectChanges()
    expect(host.querySelector('pre.tool-preview__schema')).toBeNull()
  })

  it('keeps a non-JSON schema verbatim', () => {
    const host = render(preview)
    host.querySelectorAll<HTMLButtonElement>('.tool-preview__toggle')[1].click()
    fixture.detectChanges()

    expect(host.querySelector('pre.tool-preview__schema')?.textContent).toBe('not json')
  })

  it('renders the plugin failure in the error style with no tool and no namespace line', () => {
    const host = render({
      integrationType: 'MCP_HTTP',
      configName: 'MCP_PROD',
      tools: [],
      error: 'IllegalStateException: MCP server unreachable',
    })

    expect(host.querySelector('.tool-preview__error')?.textContent).toContain('MCP server unreachable')
    expect(host.querySelector('.tool-preview__namespace')).toBeNull()
    expect(host.querySelectorAll('code.tool-preview__name').length).toBe(0)
    expect(host.querySelector('.tool-preview__empty')).toBeNull()
  })

  it('says so when the integration yields no tool and no error', () => {
    const host = render({ integrationType: 'BASH', configName: 'LOCAL', tools: [] })
    expect(host.querySelector('.tool-preview__empty')?.textContent).toContain('no tool')
  })
})
