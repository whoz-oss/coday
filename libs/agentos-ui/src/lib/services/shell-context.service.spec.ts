import { signal } from '@angular/core'
import { TestBed } from '@angular/core/testing'
import { NamespaceControllerService } from '@whoz-oss/agentos-api-client'
import { NamespaceStateService } from '@whoz-oss/agentos-dataflow'
import { of, throwError } from 'rxjs'
import { ShellContextService } from './shell-context.service'

function setup(options: { activeId?: string | null; list?: unknown } = {}) {
  const activeNamespaceId = signal<string | null>(options.activeId ?? null)
  const listAllNamespace = jest.fn(
    () =>
      options.list ??
      of([
        { id: 'a', name: 'Alpha' },
        { id: 'b', name: 'Beta' },
      ])
  )
  TestBed.configureTestingModule({
    providers: [
      { provide: NamespaceStateService, useValue: { activeNamespaceId } },
      { provide: NamespaceControllerService, useValue: { listAllNamespace } },
    ],
  })
  return { service: TestBed.inject(ShellContextService), activeNamespaceId, listAllNamespace }
}

describe('backTarget', () => {
  it('is unknown until something resolves it', () => {
    const { service } = setup()

    expect(service.backTarget()).toBeNull()
  })

  it('is the active namespace once its name is known', () => {
    const { service, activeNamespaceId } = setup()
    activeNamespaceId.set('b')

    expect(service.backTarget()).toBeNull()
    service.rememberNamespaceNames([{ id: 'b', name: 'Beta' }])

    expect(service.backTarget()).toEqual({ id: 'b', name: 'Beta' })
  })

  it('falls back to the first namespace, like the cases view, when none was visited', () => {
    const { service, listAllNamespace } = setup()

    service.loadBackTarget()

    expect(listAllNamespace).toHaveBeenCalledTimes(1)
    expect(service.backTarget()).toEqual({ id: 'a', name: 'Alpha' })
  })

  it('prefers the active namespace over the default one', () => {
    const { service, activeNamespaceId } = setup()
    service.loadBackTarget()

    activeNamespaceId.set('b')

    expect(service.backTarget()).toEqual({ id: 'b', name: 'Beta' })
  })

  it('stays unknown when the user has no namespace at all', () => {
    const { service } = setup({ list: of([]) })

    service.loadBackTarget()

    expect(service.backTarget()).toBeNull()
  })

  it('does not reload once a target is known, nor while a request was already made', () => {
    const { service, listAllNamespace } = setup()

    service.loadBackTarget()
    service.loadBackTarget()

    expect(listAllNamespace).toHaveBeenCalledTimes(1)
  })

  it('can try again after a failed request', () => {
    const { service, listAllNamespace } = setup({ list: throwError(() => new Error('booting')) })

    service.loadBackTarget()
    service.loadBackTarget()

    expect(listAllNamespace).toHaveBeenCalledTimes(2)
  })
})

describe('workQueryParams', () => {
  it('restores the open case only for its own namespace', () => {
    const { service } = setup()
    service.rememberCase('a', 'case-1')

    expect(service.workQueryParams('a')).toEqual({ ns: 'a', case: 'case-1' })
    expect(service.workQueryParams('b')).toEqual({ ns: 'b' })
    expect(service.workQueryParams(null)).toEqual({})
  })
})
