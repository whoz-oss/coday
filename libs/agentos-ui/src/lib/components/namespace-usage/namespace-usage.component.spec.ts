import { TestBed } from '@angular/core/testing'
import { ActivatedRoute, provideRouter } from '@angular/router'
import { UsageConfigurationControllerService, UsageRecordControllerService } from '@whoz-oss/agentos-api-client'
import { of, Subject, throwError } from 'rxjs'
import { NamespaceUsageComponent } from './namespace-usage.component'

const rows = [
  {
    key: 'Agent One',
    aggregate: {
      recordCount: 2,
      totalTokens: 150,
      inputTokens: 100,
      outputTokens: 50,
      cacheReadTokens: 0,
      cacheWriteTokens: 0,
      cost: 12,
      unknownCostCount: 1,
    },
  },
]

describe('NamespaceUsageComponent', () => {
  let api: { aggregateByAgentUsageRecord: jest.Mock; aggregateByModelUsageRecord: jest.Mock }
  let configuration: { getUsageConfiguration: jest.Mock }
  beforeEach(() => {
    configuration = { getUsageConfiguration: jest.fn().mockReturnValue(of({ enabled: true })) }
    api = {
      aggregateByAgentUsageRecord: jest.fn().mockReturnValue(of(rows)),
      aggregateByModelUsageRecord: jest.fn().mockReturnValue(of([])),
    }
    TestBed.configureTestingModule({
      imports: [NamespaceUsageComponent],
      providers: [
        provideRouter([]),
        { provide: ActivatedRoute, useValue: { snapshot: { params: { namespaceId: 'ns' } } } },
        { provide: UsageRecordControllerService, useValue: api },
        { provide: UsageConfigurationControllerService, useValue: configuration },
      ],
    })
  })
  function render() {
    const fixture = TestBed.createComponent(NamespaceUsageComponent)
    fixture.detectChanges()
    return fixture
  }

  it('shows a disabled report on direct navigation without loading aggregates', () => {
    configuration.getUsageConfiguration.mockReturnValue(of({ enabled: false }))
    const fixture = render()
    fixture.componentInstance.load()
    expect(fixture.nativeElement.textContent).toContain('Usage tracking is disabled.')
    expect(fixture.nativeElement.textContent).not.toContain('No recorded usage')
    expect(fixture.nativeElement.querySelector('form')).toBeNull()
    expect(fixture.nativeElement.querySelector('table')).toBeNull()
    expect(api.aggregateByAgentUsageRecord).not.toHaveBeenCalled()
    expect(api.aggregateByModelUsageRecord).not.toHaveBeenCalled()
  })

  it('waits for enabled startup settings before requesting the report', () => {
    const settings = new Subject<{ enabled: boolean }>()
    configuration.getUsageConfiguration.mockReturnValue(settings)
    const fixture = render()
    fixture.componentInstance.load()
    expect(fixture.nativeElement.textContent).toContain('Loading usage settings')
    expect(fixture.nativeElement.textContent).not.toContain('No recorded usage')
    expect(api.aggregateByAgentUsageRecord).not.toHaveBeenCalled()
    expect(api.aggregateByModelUsageRecord).not.toHaveBeenCalled()
    settings.next({ enabled: true })
    settings.complete()
    fixture.detectChanges()
    expect(api.aggregateByAgentUsageRecord).toHaveBeenCalledTimes(1)
    expect(api.aggregateByModelUsageRecord).toHaveBeenCalledTimes(1)
    expect(fixture.nativeElement.textContent).toContain('Agent One')
  })

  it('reports configuration failures separately and allows retrying', () => {
    configuration.getUsageConfiguration.mockReturnValueOnce(throwError(() => new Error('offline')))
    const fixture = render()
    expect(fixture.nativeElement.textContent).toContain('Could not load usage settings')
    expect(fixture.nativeElement.textContent).not.toContain('Usage tracking is disabled.')
    expect(fixture.nativeElement.textContent).not.toContain('No recorded usage')
    expect(api.aggregateByAgentUsageRecord).not.toHaveBeenCalled()
    expect(api.aggregateByModelUsageRecord).not.toHaveBeenCalled()
    fixture.nativeElement.querySelector('button').click()
    fixture.detectChanges()
    expect(configuration.getUsageConfiguration).toHaveBeenCalledTimes(2)
    expect(fixture.nativeElement.textContent).toContain('Agent One')
  })

  it('renders the namespace report as a known lower bound and uses inclusive UTC dates', () => {
    const fixture = render()
    const component = fixture.componentInstance
    component.from = '2026-09-01'
    component.to = '2026-09-22'
    component.load()
    fixture.detectChanges()
    expect(api.aggregateByAgentUsageRecord).toHaveBeenLastCalledWith(
      'ns',
      '2026-09-01T00:00:00.000Z',
      '2026-09-22T23:59:59.999Z'
    )
    expect(fixture.nativeElement.textContent).toContain('Agent One')
    expect(fixture.nativeElement.textContent).toContain('At least 12.00')
  })
  it('clears an old report when access is denied or the dates are invalid', () => {
    const fixture = render()
    api.aggregateByAgentUsageRecord.mockReturnValue(throwError(() => ({ status: 403 })))
    fixture.componentInstance.load()
    fixture.detectChanges()
    expect(fixture.nativeElement.textContent).toContain('Namespace admin permission')
    expect(fixture.nativeElement.textContent).not.toContain('Agent One')
    const count = api.aggregateByAgentUsageRecord.mock.calls.length
    fixture.componentInstance.from = '2026-09-30'
    fixture.componentInstance.to = '2026-09-01'
    fixture.componentInstance.load()
    expect(api.aggregateByAgentUsageRecord).toHaveBeenCalledTimes(count)
    expect(fixture.componentInstance.error()).toBe('Select a valid date range.')
  })
  it('cancels the previous period response when a newer report is requested', () => {
    const older = new Subject<typeof rows>()
    api.aggregateByAgentUsageRecord.mockReturnValueOnce(older)
    const fixture = render()
    api.aggregateByAgentUsageRecord.mockReturnValue(of([]))
    fixture.componentInstance.load()
    older.next(rows)
    older.complete()
    fixture.detectChanges()
    expect(fixture.nativeElement.textContent).not.toContain('Agent One')
  })
})
