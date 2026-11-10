import { signal } from '@angular/core'
import { TestBed } from '@angular/core/testing'
import { ActivatedRoute } from '@angular/router'
import { ExchangeFileEntryScopeEnum } from '@whoz-oss/agentos-api-client'
import { BehaviorSubject, of } from 'rxjs'
import { ExchangeStateService } from '../../services/exchange-state.service'
import { ExchangeShellComponent } from './exchange-shell.component'

/** The state service is stubbed with the signals the shell template reads; no HTTP is involved. */
function stubState() {
  return {
    caseFiles: signal([]),
    caseFolders: signal([]),
    caseBreadcrumb: signal([]),
    caseHasMore: signal(false),
    caseLoadingMore: signal(false),
    caseStatus: signal('loading'),
    caseSectionVisible: signal(true),
    canWriteCase: signal(false),
    caseUploading: signal(false),
    namespaceFiles: signal([{ path: 'docs/readme.md', name: 'readme.md', directory: false, size: 1 }]),
    namespaceFolders: signal([]),
    namespaceBreadcrumb: signal([]),
    namespaceHasMore: signal(false),
    namespaceLoadingMore: signal(false),
    namespaceStatus: signal('ready'),
    namespaceSectionVisible: signal(true),
    canWriteNamespace: signal(false),
    namespaceUploading: signal(false),
    namespaceManifestVersion: signal(0),
    namespaceManifest: jest.fn().mockReturnValue(of([])),
    initializeForCase: jest.fn(),
    clear: jest.fn(),
    loadMore: jest.fn(),
  }
}

describe('ExchangeShellComponent', () => {
  let state: ReturnType<typeof stubState>
  let queryParams$: BehaviorSubject<Record<string, string>>

  function setup(params: Record<string, string>) {
    state = stubState()
    queryParams$ = new BehaviorSubject(params)
    TestBed.configureTestingModule({
      imports: [ExchangeShellComponent],
      providers: [
        { provide: ExchangeStateService, useValue: state },
        { provide: ActivatedRoute, useValue: { queryParams: queryParams$ } },
      ],
    })
    const fixture = TestBed.createComponent(ExchangeShellComponent)
    fixture.componentRef.setInput('selectable', true)
    fixture.detectChanges()
    return { fixture, el: fixture.nativeElement as HTMLElement }
  }

  const sectionTitles = (el: HTMLElement) =>
    Array.from(el.querySelectorAll('.exchange-drawer__section-title')).map((t) => t.textContent?.trim())

  describe('namespace-only mode (no ?case)', () => {
    it('hides the case section and shows the namespace files', () => {
      const { el } = setup({ ns: 'ns-1' })
      expect(sectionTitles(el)).toEqual(['Namespace files'])
      expect(el.querySelector('ds-spinner')).toBeNull()
    })

    it('leaves the shared exchange state to its host, on destroy', () => {
      const { fixture } = setup({ ns: 'ns-1' })
      fixture.destroy()
      expect(state.initializeForCase).not.toHaveBeenCalled()
      expect(state.clear).not.toHaveBeenCalled()
    })

    it('relays the files added to the context', () => {
      const { fixture, el } = setup({ ns: 'ns-1' })
      const added = jest.fn()
      fixture.componentInstance.addToContextRequested.subscribe(added)
      el.querySelector<HTMLInputElement>('input[aria-label="Select readme.md"]')!.click()
      fixture.detectChanges()
      Array.from(el.querySelectorAll<HTMLButtonElement>('.exchange-drawer__selection-bar button'))
        .find((b) => b.textContent?.trim() === 'Add to message')!
        .click()
      expect(added).toHaveBeenCalledWith([{ scope: ExchangeFileEntryScopeEnum.NAMESPACE, path: 'docs/readme.md' }])
    })
  })

  describe('with a case', () => {
    it('shows both sections and initialises the case', () => {
      const { el } = setup({ ns: 'ns-1', case: 'c-1' })
      expect(sectionTitles(el)).toEqual(['Case files', 'Namespace files'])
      expect(state.initializeForCase).toHaveBeenCalledWith('ns-1', 'c-1')
    })

    it('still clears the state it initialised on destroy', () => {
      const { fixture } = setup({ ns: 'ns-1', case: 'c-1' })
      expect(state.clear).not.toHaveBeenCalled()
      fixture.destroy()
      expect(state.clear).toHaveBeenCalledTimes(1)
    })

    it('clears when the case leaves the route, and not a second time on destroy', () => {
      const { fixture } = setup({ ns: 'ns-1', case: 'c-1' })
      queryParams$.next({ ns: 'ns-1' })
      expect(state.clear).toHaveBeenCalledTimes(1)
      fixture.destroy()
      expect(state.clear).toHaveBeenCalledTimes(1)
    })
  })
})
