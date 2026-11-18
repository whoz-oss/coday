import { ApplicationRef, ComponentRef, createComponent, EnvironmentInjector } from '@angular/core'
import { TestBed } from '@angular/core/testing'
import { MessageActionsComponent, RERUN_TOOLTIP } from './message-actions.component'

describe('MessageActionsComponent', () => {
  function makeComponent(editable: boolean): ComponentRef<MessageActionsComponent> {
    const ref = createComponent(MessageActionsComponent, { environmentInjector: TestBed.inject(EnvironmentInjector) })
    TestBed.inject(ApplicationRef).attachView(ref.hostView)
    ref.setInput('text', 'Some message')
    ref.setInput('editable', editable)
    ref.changeDetectorRef.detectChanges()
    return ref
  }

  const host = (ref: ComponentRef<MessageActionsComponent>) => ref.location.nativeElement as HTMLElement
  const buttons = (ref: ComponentRef<MessageActionsComponent>) =>
    Array.from(host(ref).querySelectorAll('button')) as HTMLButtonElement[]

  afterEach(() => TestBed.resetTestingModule())

  it('offers the copy button alone when the message cannot be edited (agent messages, replies to a question)', () => {
    const ref = makeComponent(false)

    expect(buttons(ref)).toHaveLength(1)
    expect(buttons(ref)[0]!.title).toBe('Copy message')
  })

  it('adds the pencil when the message can be edited, after the copy button', () => {
    const ref = makeComponent(true)

    expect(buttons(ref)).toHaveLength(2)
    expect(buttons(ref)[0]!.title).toBe('Copy message')
    expect(buttons(ref)[1]!.textContent).toContain('edit')
  })

  it('says on the pencil that a re-run does not undo what the tools already did', () => {
    const ref = makeComponent(true)
    const tooltip = buttons(ref)[1]!.title

    expect(tooltip).toBe(RERUN_TOOLTIP)
    for (const word of ['files', 'commits', 'tickets', 'sub-cases']) expect(tooltip).toContain(word)
  })

  it('asks the host to open the editor when the pencil is clicked', () => {
    const ref = makeComponent(true)
    let requested = 0
    ref.instance.editRequested.subscribe(() => requested++)

    buttons(ref)[1]!.click()

    expect(requested).toBe(1)
  })

  it('offers neither a fork nor a speaker', () => {
    const icons = Array.from(host(makeComponent(true)).querySelectorAll('mat-icon')).map((icon) => icon.textContent)

    expect(icons).toEqual(['content_copy', 'edit'])
  })
})
