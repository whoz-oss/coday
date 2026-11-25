import { ApplicationRef, ComponentRef, createComponent, EnvironmentInjector } from '@angular/core'
import { TestBed } from '@angular/core/testing'
import { MessageActionsComponent, RELOAD_TOOLTIP, RERUN_TOOLTIP, REWIND_TOOLTIP } from './message-actions.component'

describe('MessageActionsComponent', () => {
  function makeComponent(editable: boolean, rewindable = false): ComponentRef<MessageActionsComponent> {
    const ref = createComponent(MessageActionsComponent, { environmentInjector: TestBed.inject(EnvironmentInjector) })
    TestBed.inject(ApplicationRef).attachView(ref.hostView)
    ref.setInput('text', 'Some message')
    ref.setInput('editable', editable)
    ref.setInput('rewindable', rewindable)
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

  it('adds reload then the pencil when the message can be edited, after the copy button', () => {
    const ref = makeComponent(true)

    expect(buttons(ref)).toHaveLength(3)
    expect(buttons(ref)[0]!.title).toBe('Copy message')
    expect(buttons(ref)[1]!.textContent).toContain('refresh')
    expect(buttons(ref)[2]!.textContent).toContain('edit')
  })

  it('says on the pencil that a re-run does not undo what the tools already did', () => {
    const ref = makeComponent(true)
    const tooltip = buttons(ref)[2]!.title

    expect(tooltip).toBe(RERUN_TOOLTIP)
    for (const word of ['files', 'commits', 'tickets', 'sub-cases']) expect(tooltip).toContain(word)
  })

  it('names the reload button, and says as much as the pencil about what a re-run does not undo', () => {
    const ref = makeComponent(true)
    const reload = buttons(ref)[1]!

    expect(reload.title).toBe(RELOAD_TOOLTIP)
    expect(reload.title.startsWith('Reload')).toBe(true)
    expect(reload.getAttribute('aria-label')).toBe(RELOAD_TOOLTIP)
    for (const word of ['files', 'commits', 'tickets', 'sub-cases']) expect(reload.title).toContain(word)
  })

  it('asks the host to open the editor when the pencil is clicked, and only that', () => {
    const ref = makeComponent(true)
    let edits = 0
    let reloads = 0
    ref.instance.editRequested.subscribe(() => edits++)
    ref.instance.reloadRequested.subscribe(() => reloads++)

    buttons(ref)[2]!.click()

    expect(edits).toBe(1)
    expect(reloads).toBe(0)
  })

  it('asks the host to run the message again when reload is clicked, and only that', () => {
    const ref = makeComponent(true)
    let edits = 0
    let reloads = 0
    ref.instance.editRequested.subscribe(() => edits++)
    ref.instance.reloadRequested.subscribe(() => reloads++)

    buttons(ref)[1]!.click()

    expect(reloads).toBe(1)
    expect(edits).toBe(0)
  })

  it('adds the rewind button last when the message can be rewound', () => {
    const ref = makeComponent(true, true)

    expect(buttons(ref)).toHaveLength(4)
    expect(buttons(ref)[3]!.textContent).toContain('history')
    expect(buttons(ref)[3]!.title).toBe(REWIND_TOOLTIP)
  })

  it('offers rewind even when the message cannot be edited', () => {
    const ref = makeComponent(false, true)

    expect(buttons(ref)).toHaveLength(2)
    expect(buttons(ref)[1]!.title).toBe(REWIND_TOOLTIP)
  })

  it('asks the host to rewind when the rewind button is clicked, and only that', () => {
    const ref = makeComponent(true, true)
    let rewinds = 0
    let edits = 0
    let reloads = 0
    ref.instance.rewindRequested.subscribe(() => rewinds++)
    ref.instance.editRequested.subscribe(() => edits++)
    ref.instance.reloadRequested.subscribe(() => reloads++)

    buttons(ref)[3]!.click()

    expect(rewinds).toBe(1)
    expect(edits).toBe(0)
    expect(reloads).toBe(0)
  })

  it('offers neither a fork nor a speaker', () => {
    const icons = Array.from(host(makeComponent(true)).querySelectorAll('mat-icon')).map((icon) => icon.textContent)

    expect(icons).toEqual(['content_copy', 'refresh', 'edit'])
  })
})
