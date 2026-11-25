import { TestBed } from '@angular/core/testing'
import { ExchangeFileEntryScopeEnum } from '@whoz-oss/agentos-api-client'
import {
  ExchangeSelectionRequestService,
  NewExchangeSelectionRequest,
  selectionRequestMarker,
} from './exchange-selection-request.service'

describe('ExchangeSelectionRequestService', () => {
  const ref = { scope: ExchangeFileEntryScopeEnum.CASE, path: 'notes/todo.md' }
  const draft = (overrides: Partial<NewExchangeSelectionRequest> = {}): NewExchangeSelectionRequest => ({
    ref,
    from: 4,
    to: 7,
    selection: 'two',
    instruction: 'Translate',
    ...overrides,
  })
  let service: ExchangeSelectionRequestService

  beforeEach(() => {
    TestBed.configureTestingModule({})
    service = TestBed.inject(ExchangeSelectionRequestService)
  })

  it('registers a request that waits, with a unique id, findable by file', () => {
    const a = service.register(draft())
    expect(a.status).toBe('waiting')
    expect(service.forFile(ref)).toEqual(a)
    expect(service.forFile({ scope: ExchangeFileEntryScopeEnum.NAMESPACE, path: 'notes/todo.md' })).toBeNull()
    const b = service.register(draft({ ref: { ...ref, path: 'other.md' } }))
    expect(b.id).not.toBe(a.id)
    expect(service.requests()).toHaveLength(2)
  })

  it('replaces the request of the same file', () => {
    const first = service.register(draft())
    const second = service.register(draft({ instruction: 'Again' }))
    expect(service.requests()).toEqual([second])
    expect(service.get(first.id)).toBeNull()
  })

  it('moves from waiting to proposed with the proposal and the explanation', () => {
    const request = service.register(draft())
    service.propose(request.id, 'deux', 'French')
    expect(service.get(request.id)).toEqual(
      expect.objectContaining({ status: 'proposed', proposal: 'deux', answer: 'French' })
    )
  })

  it('moves from waiting to answered, or to failed', () => {
    const a = service.register(draft())
    service.answer(a.id, 'Nothing to change')
    expect(service.get(a.id)).toEqual(expect.objectContaining({ status: 'answered', answer: 'Nothing to change' }))
    const b = service.register(draft({ ref: { ...ref, path: 'b.md' } }))
    service.fail(b.id, 'Execution error')
    expect(service.get(b.id)).toEqual(expect.objectContaining({ status: 'failed', answer: 'Execution error' }))
  })

  it('ignores a later answer once the request is settled', () => {
    const request = service.register(draft())
    service.propose(request.id, 'deux', 'French')
    service.answer(request.id, 'late')
    service.fail(request.id, 'late')
    expect(service.get(request.id)).toEqual(expect.objectContaining({ status: 'proposed', proposal: 'deux' }))
  })

  it('tells which request is the first one waiting', () => {
    const a = service.register(draft())
    const b = service.register(draft({ ref: { ...ref, path: 'b.md' } }))
    expect(service.firstWaiting()?.id).toBe(a.id)
    service.answer(a.id, 'ok')
    expect(service.firstWaiting()?.id).toBe(b.id)
  })

  it('clears a request on accept, reject or dismiss, and reports pending files', () => {
    const request = service.register(draft())
    expect(service.hasPending(ref)).toBe(true)
    service.clear(request.id)
    expect(service.forFile(ref)).toBeNull()
    expect(service.hasPending(ref)).toBe(false)
    expect(service.hasPending(null)).toBe(false)
  })

  it('fails every waiting request but leaves the settled ones', () => {
    const a = service.register(draft())
    const b = service.register(draft({ ref: { ...ref, path: 'b.md' } }))
    service.propose(b.id, 'x', 'y')
    service.failWaiting('The case ended')
    expect(service.get(a.id)).toEqual(expect.objectContaining({ status: 'failed', answer: 'The case ended' }))
    expect(service.get(b.id)!.status).toBe('proposed')
  })

  it('writes the marker of a request id', () => {
    expect(selectionRequestMarker('abc')).toBe('[selection-request:abc]')
  })
})
