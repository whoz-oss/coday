import { HttpErrorResponse } from '@angular/common/http'
import { TestBed } from '@angular/core/testing'
import { Subject, throwError } from 'rxjs'
import { SideQuestionApiService } from './side-question-api.service'
import { SideQuestionStateService, sideQuestionErrorMessage } from './side-question-state.service'

describe('SideQuestionStateService', () => {
  let api: { ask: jest.Mock }
  let service: SideQuestionStateService

  beforeEach(() => {
    api = { ask: jest.fn() }
    TestBed.configureTestingModule({
      providers: [SideQuestionStateService, { provide: SideQuestionApiService, useValue: api }],
    })
    service = TestBed.inject(SideQuestionStateService)
  })

  afterEach(() => TestBed.resetTestingModule())

  it('starts with no aside', () => {
    expect(service.visible()).toBe(false)
    expect(service.pending()).toBe(false)
    expect(service.question()).toBeNull()
    expect(service.answer()).toBeNull()
    expect(service.error()).toBeNull()
  })

  it('shows the question as pending at once, then the answer', () => {
    const response = new Subject<{ answer: string }>()
    api.ask.mockReturnValue(response)

    service.ask('c-1', '  what is the plan?  ')

    expect(api.ask).toHaveBeenCalledWith('c-1', 'what is the plan?')
    expect(service.visible()).toBe(true)
    expect(service.question()).toBe('what is the plan?')
    expect(service.pending()).toBe(true)
    expect(service.answer()).toBeNull()

    response.next({ answer: 'The plan is X.' })

    expect(service.pending()).toBe(false)
    expect(service.answer()).toBe('The plan is X.')
    expect(service.error()).toBeNull()
  })

  it('shows a readable error, not the one of the server, when the request fails', () => {
    const response = new Subject<{ answer: string }>()
    api.ask.mockReturnValue(response)
    service.ask('c-1', 'q')

    response.error(new HttpErrorResponse({ status: 502, error: { message: 'sk-secret leaked' } }))

    expect(service.pending()).toBe(false)
    expect(service.answer()).toBeNull()
    expect(service.error()).toBe('The model could not answer. Try again.')
    expect(service.question()).toBe('q')
  })

  it('ignores a blank question', () => {
    service.ask('c-1', '   ')

    expect(api.ask).not.toHaveBeenCalled()
    expect(service.visible()).toBe(false)
  })

  it('dismiss closes the aside and clears everything', () => {
    const response = new Subject<{ answer: string }>()
    api.ask.mockReturnValue(response)
    service.ask('c-1', 'q')
    response.next({ answer: 'a' })

    service.dismiss()

    expect(service.visible()).toBe(false)
    expect(service.question()).toBeNull()
    expect(service.answer()).toBeNull()
    expect(service.error()).toBeNull()
    expect(service.pending()).toBe(false)
  })

  it('an answer that arrives after a dismiss is dropped, and the request is cancelled', () => {
    const response = new Subject<{ answer: string }>()
    api.ask.mockReturnValue(response)
    service.ask('c-1', 'q')

    service.dismiss()
    response.next({ answer: 'late' })

    expect(response.observed).toBe(false)
    expect(service.visible()).toBe(false)
    expect(service.answer()).toBeNull()
  })

  it('a new question replaces the previous one, and the stale answer of the first is ignored', () => {
    const first = new Subject<{ answer: string }>()
    const second = new Subject<{ answer: string }>()
    api.ask.mockReturnValueOnce(first).mockReturnValueOnce(second)

    service.ask('c-1', 'first?')
    service.ask('c-1', 'second?')
    first.next({ answer: 'stale answer' })

    expect(first.observed).toBe(false)
    expect(service.question()).toBe('second?')
    expect(service.pending()).toBe(true)
    expect(service.answer()).toBeNull()

    second.next({ answer: 'fresh answer' })

    expect(service.answer()).toBe('fresh answer')
    expect(service.pending()).toBe(false)
  })

  it('a stale error of a replaced question does not hide the new answer', () => {
    const first = new Subject<{ answer: string }>()
    const second = new Subject<{ answer: string }>()
    api.ask.mockReturnValueOnce(first).mockReturnValueOnce(second)
    service.ask('c-1', 'first?')
    service.ask('c-1', 'second?')

    first.error(new HttpErrorResponse({ status: 500 }))
    second.next({ answer: 'fresh answer' })

    expect(service.error()).toBeNull()
    expect(service.answer()).toBe('fresh answer')
  })

  it('a new question after an error clears it', () => {
    api.ask.mockReturnValueOnce(throwError(() => new HttpErrorResponse({ status: 409 })))
    service.ask('c-1', 'q1')
    expect(service.error()).toContain('No AI model')

    const response = new Subject<{ answer: string }>()
    api.ask.mockReturnValueOnce(response)
    service.ask('c-1', 'q2')

    expect(service.error()).toBeNull()
    expect(service.pending()).toBe(true)
  })

  it('cancels its pending request when destroyed', () => {
    const response = new Subject<{ answer: string }>()
    api.ask.mockReturnValue(response)
    service.ask('c-1', 'q')

    TestBed.resetTestingModule()

    expect(response.observed).toBe(false)
  })
})

describe('sideQuestionErrorMessage()', () => {
  const message = (status: number) => sideQuestionErrorMessage(new HttpErrorResponse({ status }))

  it('tells each status in plain words', () => {
    expect(message(400)).toContain('empty or too long')
    expect(message(403)).toContain('no longer available')
    expect(message(404)).toContain('no longer available')
    expect(message(409)).toContain('No AI model')
    expect(message(502)).toContain('could not answer')
  })

  it('falls back to a generic message for any other failure', () => {
    expect(message(500)).toBe('The side question failed. Try again.')
    expect(sideQuestionErrorMessage(new Error('boom'))).toBe('The side question failed. Try again.')
  })
})
