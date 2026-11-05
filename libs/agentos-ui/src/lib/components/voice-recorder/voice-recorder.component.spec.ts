import { ApplicationRef, ComponentRef, createComponent, EnvironmentInjector } from '@angular/core'
import { TestBed } from '@angular/core/testing'
import { VoiceRecorderComponent } from './voice-recorder.component'
import { VoiceRecorderState } from './voice-recorder.service'

function makeComponent(
  state: VoiceRecorderState,
  elapsedMs = 0,
  error: string | null = null,
  sendKey?: string
): ComponentRef<VoiceRecorderComponent> {
  const ref = createComponent(VoiceRecorderComponent, { environmentInjector: TestBed.inject(EnvironmentInjector) })
  TestBed.inject(ApplicationRef).attachView(ref.hostView)
  ref.setInput('state', state)
  ref.setInput('elapsedMs', elapsedMs)
  ref.setInput('error', error)
  if (sendKey) ref.setInput('sendKey', sendKey)
  ref.changeDetectorRef.detectChanges()
  return ref
}

const host = (ref: ComponentRef<VoiceRecorderComponent>) => ref.location.nativeElement as HTMLElement

describe('VoiceRecorderComponent', () => {
  afterEach(() => TestBed.resetTestingModule())

  it('renders nothing when idle', () => {
    expect(host(makeComponent('idle')).textContent?.trim()).toBe('')
  })

  it('shows the indicator, the elapsed time and the hint while recording, and no button of its own', () => {
    const ref = makeComponent('recording', 65_000)
    expect(host(ref).querySelector('.voice-recorder__pulse')).not.toBeNull()
    expect(host(ref).querySelector('.voice-recorder__time')?.textContent).toBe('01:05')
    expect(host(ref).querySelector('.voice-recorder__hint')?.textContent).toBe('Recording… press Enter to send')
    // Cancel and send live in the composer's action bar.
    expect(host(ref).querySelectorAll('button')).toHaveLength(0)
  })

  it('names the key that sends, as the host defines it', () => {
    const ref = makeComponent('recording', 0, null, 'Ctrl/Cmd+Enter')
    expect(host(ref).querySelector('.voice-recorder__hint')?.textContent).toBe(
      'Recording… press Ctrl/Cmd+Enter to send'
    )
  })

  it('takes the focus when the recording starts, so that the keys are read without the textarea', () => {
    const ref = makeComponent('recording')
    // A detached element cannot take the focus: put the host in the document, as in the app.
    document.body.appendChild(host(ref))
    TestBed.inject(ApplicationRef).tick()
    const bar = host(ref).querySelector('.voice-recorder') as HTMLElement
    expect(bar.getAttribute('tabindex')).toBe('-1')
    expect(document.activeElement).toBe(bar)
    host(ref).remove()
    ref.destroy()
  })

  it('hands the keys pressed on the bar to the host', () => {
    const ref = makeComponent('recording')
    const keys: string[] = []
    ref.instance.keyPressed.subscribe((event) => keys.push(event.key))
    const bar = host(ref).querySelector('.voice-recorder') as HTMLElement

    bar.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }))
    bar.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }))

    expect(keys).toEqual(['Enter', 'Escape'])
  })

  it('shows a spinner and a progress line, no button and no key handling, while sending and transcribing', () => {
    const ref = makeComponent('sending')
    expect(host(ref).querySelector('.voice-recorder__spinner')).not.toBeNull()
    expect(host(ref).querySelector('.voice-recorder__hint')?.textContent).toBe('Sending and transcribing…')
    expect(host(ref).querySelectorAll('button')).toHaveLength(0)
    expect(host(ref).querySelector('.voice-recorder__pulse')).toBeNull()
    expect(host(ref).querySelector('[tabindex]')).toBeNull()
  })

  it('shows the error as one line, without the recording bar', () => {
    const ref = makeComponent('error', 0, 'Microphone access was denied.')
    expect(host(ref).querySelector('[role="alert"]')?.textContent).toContain('Microphone access was denied.')
    expect(host(ref).querySelector('.voice-recorder')).toBeNull()
    expect(host(ref).querySelector('.voice-recorder__pulse')).toBeNull()
  })
})
