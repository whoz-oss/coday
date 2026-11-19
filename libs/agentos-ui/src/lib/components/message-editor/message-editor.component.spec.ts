import { ApplicationRef, ComponentRef, createComponent, EnvironmentInjector } from '@angular/core'
import { TestBed } from '@angular/core/testing'
import { ExchangeFileEntryScopeEnum } from '@whoz-oss/agentos-api-client'
import { MentionedFile } from '../composer-attachments/composer-attachments.utils'
import { MessageEditorComponent } from './message-editor.component'

describe('MessageEditorComponent', () => {
  let appRef: ApplicationRef

  function makeComponent(
    text = 'Original text',
    busy = false,
    files: MentionedFile[] = []
  ): ComponentRef<MessageEditorComponent> {
    const ref = createComponent(MessageEditorComponent, { environmentInjector: TestBed.inject(EnvironmentInjector) })
    // In the document, so that the focus can be read back.
    document.body.appendChild(ref.location.nativeElement)
    appRef.attachView(ref.hostView)
    ref.setInput('text', text)
    ref.setInput('busy', busy)
    ref.setInput('files', files)
    appRef.tick()
    return ref
  }

  const host = (ref: ComponentRef<MessageEditorComponent>) => ref.location.nativeElement as HTMLElement
  const field = (ref: ComponentRef<MessageEditorComponent>) =>
    host(ref).querySelector('textarea') as HTMLTextAreaElement
  const buttons = (ref: ComponentRef<MessageEditorComponent>) =>
    Array.from(host(ref).querySelectorAll('button')) as HTMLButtonElement[]
  const cancelButton = (ref: ComponentRef<MessageEditorComponent>) => buttons(ref)[0]!
  const rerunButton = (ref: ComponentRef<MessageEditorComponent>) => buttons(ref)[1]!

  function type(ref: ComponentRef<MessageEditorComponent>, value: string): void {
    const textarea = field(ref)
    textarea.value = value
    textarea.dispatchEvent(new Event('input'))
    appRef.tick()
  }

  function press(ref: ComponentRef<MessageEditorComponent>, init: KeyboardEventInit): KeyboardEvent {
    const event = new KeyboardEvent('keydown', { cancelable: true, bubbles: true, ...init })
    field(ref).dispatchEvent(event)
    appRef.tick()
    return event
  }

  beforeEach(() => {
    appRef = TestBed.inject(ApplicationRef)
  })

  afterEach(() => {
    document.body.innerHTML = ''
    TestBed.resetTestingModule()
  })

  it('starts from the text of the message, and takes the focus', () => {
    const ref = makeComponent('Original text')

    expect(field(ref).value).toBe('Original text')
    expect(document.activeElement).toBe(field(ref))
  })

  it('offers Cancel and one button, which says Rerun while the text is the original one', () => {
    const ref = makeComponent('Original text')

    expect(buttons(ref)).toHaveLength(2)
    expect(cancelButton(ref).textContent?.trim()).toBe('Cancel')
    expect(rerunButton(ref).textContent?.trim()).toBe('Rerun')
  })

  it('compares the trimmed texts: blanks around the original are not a change', () => {
    const ref = makeComponent('Original text')

    type(ref, '  Original text \n')

    expect(rerunButton(ref).textContent?.trim()).toBe('Rerun')
  })

  it('says Save & Rerun once the text has changed, and Rerun again when it is back to the original', () => {
    const ref = makeComponent('Original text')

    type(ref, 'Another text')
    expect(rerunButton(ref).textContent?.trim()).toBe('Save & Rerun')

    type(ref, 'Original text')
    expect(rerunButton(ref).textContent?.trim()).toBe('Rerun')
  })

  it('offers no way to save without running', () => {
    const ref = makeComponent('Original text')
    type(ref, 'Another text')

    expect(buttons(ref).map((button) => button.textContent?.trim())).toEqual(['Cancel', 'Save & Rerun'])
  })

  it('sends the trimmed text on the button, with no confirmation', () => {
    const ref = makeComponent('Original text')
    const sent: string[] = []
    ref.instance.rerun.subscribe((text) => sent.push(text))
    type(ref, '  Another text \n')

    rerunButton(ref).click()

    expect(sent).toEqual(['Another text'])
  })

  it('sends the original text as it is on Rerun', () => {
    const ref = makeComponent('Original text')
    const sent: string[] = []
    ref.instance.rerun.subscribe((text) => sent.push(text))

    rerunButton(ref).click()

    expect(sent).toEqual(['Original text'])
  })

  it('cannot send an empty text', () => {
    const ref = makeComponent('Original text')
    const sent: string[] = []
    ref.instance.rerun.subscribe((text) => sent.push(text))
    type(ref, '  \n ')

    expect(rerunButton(ref).disabled).toBe(true)
    press(ref, { key: 'Enter', ctrlKey: true })
    rerunButton(ref).click()

    expect(sent).toEqual([])
  })

  it('cannot send while the case runs, but the text can still be edited', () => {
    const ref = makeComponent('Original text', true)
    const sent: string[] = []
    ref.instance.rerun.subscribe((text) => sent.push(text))

    expect(rerunButton(ref).disabled).toBe(true)
    press(ref, { key: 'Enter', ctrlKey: true })

    expect(sent).toEqual([])
    expect(field(ref).disabled).toBe(false)
  })

  it('Cancel closes the editor without sending anything', () => {
    const ref = makeComponent('Original text')
    const sent: string[] = []
    let cancelled = 0
    ref.instance.rerun.subscribe((text) => sent.push(text))
    ref.instance.cancelled.subscribe(() => cancelled++)

    cancelButton(ref).click()

    expect(cancelled).toBe(1)
    expect(sent).toEqual([])
  })

  it('Escape cancels', () => {
    const ref = makeComponent('Original text')
    let cancelled = 0
    ref.instance.cancelled.subscribe(() => cancelled++)

    const event = press(ref, { key: 'Escape' })

    expect(cancelled).toBe(1)
    expect(event.defaultPrevented).toBe(true)
  })

  it('Ctrl+Enter and Cmd+Enter send', () => {
    const ref = makeComponent('Original text')
    const sent: string[] = []
    ref.instance.rerun.subscribe((text) => sent.push(text))
    type(ref, 'Another text')

    const ctrl = press(ref, { key: 'Enter', ctrlKey: true })
    press(ref, { key: 'Enter', metaKey: true })

    expect(sent).toEqual(['Another text', 'Another text'])
    expect(ctrl.defaultPrevented).toBe(true)
  })

  it('a plain Enter only inserts a new line: nothing is sent', () => {
    const ref = makeComponent('Original text')
    const sent: string[] = []
    ref.instance.rerun.subscribe((text) => sent.push(text))

    const event = press(ref, { key: 'Enter' })

    expect(sent).toEqual([])
    expect(event.defaultPrevented).toBe(false)
  })

  describe('a message that carries files', () => {
    const files: MentionedFile[] = [
      { name: 'pasted.png', path: 'pasted.png', scope: ExchangeFileEntryScopeEnum.CASE, kind: 'attached' },
      {
        name: 'readme.md',
        path: 'docs/readme.md',
        scope: ExchangeFileEntryScopeEnum.NAMESPACE,
        kind: 'referenced',
      },
    ]
    const chips = (ref: ComponentRef<MessageEditorComponent>) => host(ref).querySelectorAll('agentos-file-chip')

    it('starts from the text alone: the lines of the files are not in the textarea', () => {
      const ref = makeComponent('Original text', false, files)

      expect(field(ref).value).toBe('Original text')
      expect(field(ref).value).not.toContain('Files')
    })

    it('shows the files under the textarea, as chips that cannot be removed', () => {
      const ref = makeComponent('Original text', false, files)

      expect(chips(ref)).toHaveLength(2)
      expect(chips(ref)[0]!.textContent).toContain('pasted.png')
      expect(chips(ref)[1]!.textContent).toContain('readme.md')
      expect(chips(ref)[1]!.textContent).toContain('namespace')
      // No cross on a chip: the only buttons are Cancel and Rerun.
      expect(host(ref).querySelectorAll('agentos-file-chip button')).toHaveLength(0)
      expect(buttons(ref)).toHaveLength(2)
      // The chips are under the field, above the buttons.
      const order = Array.from(host(ref).children).map((element) => element.tagName.toLowerCase())
      expect(order.indexOf('textarea')).toBeLessThan(order.indexOf('div'))
      expect(host(ref).querySelector('.message-editor__files')).not.toBeNull()
    })

    it('shows no chip row for a message without file', () => {
      const ref = makeComponent('Original text')

      expect(chips(ref)).toHaveLength(0)
      expect(host(ref).querySelector('.message-editor__files')).toBeNull()
    })

    it('says Rerun while the text is the original one, files or not, and Save & Rerun once it has changed', () => {
      const ref = makeComponent('Original text', false, files)
      expect(rerunButton(ref).textContent?.trim()).toBe('Rerun')

      type(ref, 'Another text')
      expect(rerunButton(ref).textContent?.trim()).toBe('Save & Rerun')

      type(ref, '  Original text ')
      expect(rerunButton(ref).textContent?.trim()).toBe('Rerun')
    })

    it('sends the text alone: the lines of the files are put back by the host', () => {
      const ref = makeComponent('Original text', false, files)
      const sent: string[] = []
      ref.instance.rerun.subscribe((text) => sent.push(text))
      type(ref, '  Another text ')

      rerunButton(ref).click()

      expect(sent).toEqual(['Another text'])
    })

    it('can run again with no text at all: the files are the message', () => {
      const ref = makeComponent('', false, files)
      const sent: string[] = []
      ref.instance.rerun.subscribe((text) => sent.push(text))

      expect(rerunButton(ref).disabled).toBe(false)
      rerunButton(ref).click()
      type(ref, '   ')
      press(ref, { key: 'Enter', ctrlKey: true })

      expect(sent).toEqual(['', ''])
    })

    it('still cannot send while the case runs', () => {
      const ref = makeComponent('', true, files)

      expect(rerunButton(ref).disabled).toBe(true)
    })
  })
})
