import { TestBed } from '@angular/core/testing'
import { ExchangeFileEntryScopeEnum } from '@whoz-oss/agentos-api-client'
import { ExchangeDrawerComponent } from './exchange-drawer.component'

describe('ExchangeDrawerComponent directory controls', () => {
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
})
