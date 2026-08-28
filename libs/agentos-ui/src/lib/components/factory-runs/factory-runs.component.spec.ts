import { ComponentFixture, TestBed } from '@angular/core/testing'
import { ActivatedRoute, convertToParamMap, Router } from '@angular/router'
import { RouterTestingModule } from '@angular/router/testing'
import { NamespaceStateService } from '@whoz-oss/agentos-dataflow'
import { signal } from '@angular/core'
import { BehaviorSubject } from 'rxjs'
import { FactoryStateService } from '../../services/factory-state.service'
import { FactoryRunsComponent } from './factory-runs.component'

class NamespaceStateStub {
  readonly namespacesSubject = new BehaviorSubject<Array<{ id?: string; name: string }>>([])
  readonly initializedSubject = new BehaviorSubject(false)
  readonly namespaces$ = this.namespacesSubject.asObservable()
  readonly initialized$ = this.initializedSubject.asObservable()
  readonly selectNamespace = jest.fn()
}

describe('FactoryRunsComponent', () => {
  let routeParams: BehaviorSubject<ReturnType<typeof convertToParamMap>>
  let namespaceState: NamespaceStateStub
  let factoryState: { namespaceId: jest.Mock; clear: jest.Mock; load: jest.Mock }
  let routerMock: { navigate: jest.Mock }

  function makeFactoryState() {
    return {
      namespaceId: jest.fn().mockReturnValue(null),
      runs: signal([]),
      loading: signal(false),
      error: signal(null),
      clear: jest.fn(),
      load: jest.fn(),
      refresh: jest.fn(),
      selectedRun: signal(null),
      detailLoading: signal(false),
      detailError: signal(null),
      namespaceMismatch: signal(false),
      clearDetail: jest.fn(),
      loadDetail: jest.fn(),
    } as unknown as typeof factoryState
  }

  function create(
    params: Record<string, string> = {},
    embeddedNamespaceId?: string
  ): { component: FactoryRunsComponent; fixture: ComponentFixture<FactoryRunsComponent> } {
    routeParams = new BehaviorSubject(convertToParamMap(params))
    namespaceState = new NamespaceStateStub()
    routerMock = { navigate: jest.fn() }
    factoryState = makeFactoryState()

    TestBed.configureTestingModule({
      imports: [FactoryRunsComponent, RouterTestingModule],
      providers: [
        {
          provide: ActivatedRoute,
          useValue: { queryParamMap: routeParams, snapshot: { queryParamMap: routeParams.value } },
        },
        { provide: Router, useValue: routerMock },
        { provide: NamespaceStateService, useValue: namespaceState },
        { provide: FactoryStateService, useValue: factoryState },
      ],
    })
    const fixture = TestBed.createComponent(FactoryRunsComponent)
    if (embeddedNamespaceId !== undefined) {
      fixture.componentRef.setInput('embeddedNamespaceId', embeddedNamespaceId)
    }
    fixture.detectChanges()
    return { component: fixture.componentInstance, fixture }
  }

  afterEach(() => TestBed.resetTestingModule())

  // ---------------------------------------------------------------------------
  // Namespace initialization
  // ---------------------------------------------------------------------------

  it('waits for namespace initialization before loading a valid query namespace', () => {
    const { fixture } = create({ ns: 'ns-1' })
    expect(factoryState.load).not.toHaveBeenCalled()

    namespaceState.namespacesSubject.next([{ id: 'ns-1', name: 'One' }])
    fixture.detectChanges()
    expect(factoryState.load).not.toHaveBeenCalled()

    namespaceState.initializedSubject.next(true)
    fixture.detectChanges()
    expect(namespaceState.selectNamespace).toHaveBeenCalledWith('ns-1')
    expect(factoryState.load).toHaveBeenCalledTimes(1)
    expect(factoryState.load).toHaveBeenCalledWith('ns-1')
  })

  it('loads a valid initialized namespace exactly once', () => {
    const { fixture } = create({ ns: 'ns-1' })
    namespaceState.namespacesSubject.next([{ id: 'ns-1', name: 'One' }])
    namespaceState.initializedSubject.next(true)
    fixture.detectChanges()
    namespaceState.namespacesSubject.next([{ id: 'ns-1', name: 'One' }])
    fixture.detectChanges()

    expect(factoryState.load).toHaveBeenCalledTimes(1)
  })

  it('keeps the explicit choose state when ns is missing', () => {
    const { fixture } = create()
    namespaceState.initializedSubject.next(true)
    fixture.detectChanges()

    expect(factoryState.load).not.toHaveBeenCalled()
    expect(namespaceState.selectNamespace).not.toHaveBeenCalled()
  })

  it('does not load an unknown namespace after initialization', () => {
    const { fixture } = create({ ns: 'unknown' })
    namespaceState.namespacesSubject.next([{ id: 'ns-1', name: 'One' }])
    namespaceState.initializedSubject.next(true)
    fixture.detectChanges()

    expect(factoryState.load).not.toHaveBeenCalled()
    expect(namespaceState.selectNamespace).not.toHaveBeenCalled()
  })

  // ---------------------------------------------------------------------------
  // selectRun — embedded run navigation and view preservation
  // ---------------------------------------------------------------------------

  describe('selectRun', () => {
    it('navigates with queryParamsHandling merge so ?view=factory is preserved', () => {
      // Root cause: old routerLink used { ns, run } queryParams without merge,
      // which dropped ?view=factory and caused the shell to revert to cases view.
      const { component } = create({ ns: 'ns-1', view: 'factory' })

      component['selectRun']('run-abc')

      expect(routerMock.navigate).toHaveBeenCalledWith(
        [],
        expect.objectContaining({
          queryParams: { run: 'run-abc' },
          queryParamsHandling: 'merge',
        })
      )
    })

    it('only sets ?run in queryParams — merge handles ?view, ?ns, and other params', () => {
      const { component } = create({ ns: 'ns-1', view: 'factory' })

      component['selectRun']('run-xyz')

      const call = routerMock.navigate.mock.calls[0]
      const params = call[1].queryParams as Record<string, string>
      expect(params['view']).toBeUndefined()
      expect(params['ns']).toBeUndefined()
      expect(params['run']).toBe('run-xyz')
    })

    it('works in standalone route mode (no ?view param)', () => {
      const { component } = create({ ns: 'ns-1' })

      component['selectRun']('run-standalone')

      expect(routerMock.navigate).toHaveBeenCalledWith(
        [],
        expect.objectContaining({ queryParams: { run: 'run-standalone' }, queryParamsHandling: 'merge' })
      )
    })

    it('works in embedded mode (embeddedNamespaceId provided)', () => {
      const { component } = create({}, 'ns-embedded')

      component['selectRun']('run-embedded')

      expect(routerMock.navigate).toHaveBeenCalledWith(
        [],
        expect.objectContaining({ queryParams: { run: 'run-embedded' }, queryParamsHandling: 'merge' })
      )
    })

    it('replaces an existing ?run when selecting a different run', () => {
      const { component } = create({ ns: 'ns-1', view: 'factory', run: 'old-run' })

      component['selectRun']('new-run')

      const call = routerMock.navigate.mock.calls[0]
      expect(call[1].queryParams).toEqual({ run: 'new-run' })
      expect(call[1].queryParamsHandling).toBe('merge')
    })
  })
})
