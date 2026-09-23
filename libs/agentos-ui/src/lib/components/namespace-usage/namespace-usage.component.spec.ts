import { TestBed } from '@angular/core/testing'
import { ActivatedRoute, provideRouter } from '@angular/router'
import { UsageRecordControllerService } from '@whoz-oss/agentos-api-client'
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
  beforeEach(() => {
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
      ],
    })
  })
  it('renders the namespace report as a known lower bound and uses inclusive UTC dates', () => {
    const fixture = TestBed.createComponent(NamespaceUsageComponent)
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
    const fixture = TestBed.createComponent(NamespaceUsageComponent)
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
    const fixture = TestBed.createComponent(NamespaceUsageComponent)
    api.aggregateByAgentUsageRecord.mockReturnValue(of([]))
    fixture.componentInstance.load()
    older.next(rows)
    older.complete()
    fixture.detectChanges()
    expect(fixture.nativeElement.textContent).not.toContain('Agent One')
  })
})
