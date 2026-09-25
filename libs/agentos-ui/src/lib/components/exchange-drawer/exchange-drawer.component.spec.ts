import { fakeAsync, TestBed, tick } from '@angular/core/testing'
import { BehaviorSubject, Subject } from 'rxjs'
import { MatDialog } from '@angular/material/dialog'
import { ExchangeEnvironmentService, ExchangeEnvironment } from '../../services/exchange-environment.service'
import { CaseWorkspaceService } from '../../services/case-workspace.service'
import { ExchangeFileEntryScopeEnum } from '@whoz-oss/agentos-api-client'
import { ExchangeDrawerComponent } from './exchange-drawer.component'

describe('ExchangeDrawerComponent directory controls', () => {
  it('distinguishes workspace preparation from file loading errors', () => {
    TestBed.configureTestingModule({ imports: [ExchangeDrawerComponent] })
    const fixture = TestBed.createComponent(ExchangeDrawerComponent)
    fixture.componentRef.setInput('caseSectionVisible', true)
    fixture.componentRef.setInput('caseStatus', 'preparing')
    fixture.detectChanges()
    expect(fixture.nativeElement.textContent).toContain('Preparing workspace')
    expect(fixture.nativeElement.querySelector('ds-spinner')).not.toBeNull()
    expect(fixture.nativeElement.querySelector('.exchange-drawer__retry')).toBeNull()

    fixture.componentRef.setInput('caseStatus', 'error')
    fixture.detectChanges()
    expect(fixture.nativeElement.textContent).not.toContain('Preparing workspace')
    expect(fixture.nativeElement.querySelector('ds-spinner')).toBeNull()
    expect(fixture.nativeElement.querySelector('.exchange-drawer__retry')).not.toBeNull()
  })

  it('renders reachable load-more controls for both scopes and keeps folder-only downloads available', async () => {
    await TestBed.configureTestingModule({ imports: [ExchangeDrawerComponent] }).compileComponents()
    const fixture = TestBed.createComponent(ExchangeDrawerComponent)
    for (const scope of ['case', 'namespace']) {
      fixture.componentRef.setInput(`${scope}Status`, 'ready')
      fixture.componentRef.setInput(`${scope}SectionVisible`, true)
      fixture.componentRef.setInput(`${scope}HasMore`, true)
      fixture.componentRef.setInput(`${scope}Folders`, [{ path: 'nested', name: 'nested', directory: true }])
    }
    const more = jest.fn()
    fixture.componentInstance.loadMoreRequested.subscribe(more)
    fixture.detectChanges()
    const buttons = Array.from(fixture.nativeElement.querySelectorAll('button')) as HTMLButtonElement[]
    const loadMore = buttons.filter((button) => button.textContent?.trim() === 'Load more files')
    expect(loadMore).toHaveLength(2)
    loadMore[0]!.click()
    loadMore[1]!.click()
    expect(more.mock.calls).toEqual([[ExchangeFileEntryScopeEnum.CASE], [ExchangeFileEntryScopeEnum.NAMESPACE]])
    expect(fixture.nativeElement.querySelector('[title="Download all case files"]')).not.toBeNull()
    expect(fixture.nativeElement.querySelector('[title="Download all namespace files"]')).not.toBeNull()
    fixture.componentRef.setInput('caseFolders', [])
    fixture.componentRef.setInput('caseBreadcrumb', [{ label: 'empty', path: 'empty' }])
    fixture.componentRef.setInput('namespaceLoadingMore', true)
    fixture.detectChanges()
    expect(loadMore[1]!.disabled).toBe(true)
    expect(fixture.nativeElement.querySelector('[title="Download all case files"]')).not.toBeNull()
  })
  it('mounts the Git/participant observer only while Files is visible and cancels it on close', fakeAsync(() => {
    const request = new Subject<ExchangeEnvironment>()
    const get = jest.fn().mockReturnValue(request)
    const workspace = new BehaviorSubject({
      view: { equipped: true, rootCaseId: 'root', status: 'READY' },
    })
    TestBed.configureTestingModule({
      imports: [ExchangeDrawerComponent],
      providers: [
        { provide: ExchangeEnvironmentService, useValue: { get } },
        { provide: CaseWorkspaceService, useValue: { watch: () => workspace } },
        { provide: MatDialog, useValue: { open: jest.fn() } },
      ],
    })
    const fixture = TestBed.createComponent(ExchangeDrawerComponent)
    fixture.componentRef.setInput('caseId', 'root')
    fixture.componentRef.setInput('caseStatus', 'ready')
    fixture.componentRef.setInput('namespaceStatus', 'ready')
    fixture.componentRef.setInput('caseSectionVisible', true)
    fixture.detectChanges()
    tick(10000)
    expect(get).not.toHaveBeenCalled()
    fixture.componentRef.setInput('environmentActive', true)
    fixture.detectChanges()
    tick(0)
    expect(get).toHaveBeenCalledTimes(1)
    expect(request.observed).toBe(true)
    tick(20000)
    expect(get).toHaveBeenCalledTimes(1) // A slow request is never overlapped or restarted.
    request.next({ equipped: true, status: 'READY', agents: [], branch: 'feature/test' })
    request.complete()
    fixture.detectChanges()
    expect(fixture.nativeElement.textContent).toContain('feature/test')
    expect(fixture.nativeElement.textContent).not.toContain('Refresh Git status')
    fixture.componentRef.setInput('canWriteCase', true)
    fixture.detectChanges()
    expect(fixture.nativeElement.textContent).toContain('Refresh Git status')
    tick(10000)
    const pending = new Subject<ExchangeEnvironment>()
    get.mockReturnValue(pending)
    tick(10000)
    expect(pending.observed).toBe(true)
    fixture.componentRef.setInput('environmentActive', false)
    fixture.detectChanges()
    expect(pending.observed).toBe(false)
    expect(workspace.observed).toBe(false)
    const calls = get.mock.calls.length
    tick(20000)
    expect(get).toHaveBeenCalledTimes(calls)
    expect(fixture.nativeElement.querySelector('agentos-exchange-environment')).toBeNull()
    fixture.destroy()
  }))
})
