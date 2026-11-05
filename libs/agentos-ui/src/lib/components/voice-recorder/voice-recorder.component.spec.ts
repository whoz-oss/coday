import { ApplicationRef, ComponentRef, createComponent, EnvironmentInjector } from '@angular/core'
import { TestBed } from '@angular/core/testing'
import { VoiceRecorderComponent } from './voice-recorder.component'
import { VoiceRecorderState } from './voice-recorder.service'

function makeComponent(
  state: VoiceRecorderState,
  elapsedMs = 0,
  error: string | null = null
): ComponentRef<VoiceRecorderComponent> {
  const ref = createComponent(VoiceRecorderComponent, { environmentInjector: TestBed.inject(EnvironmentInjector) })
  TestBed.inject(ApplicationRef).attachView(ref.hostView)
  ref.setInput('state', state)
  ref.setInput('elapsedMs', elapsedMs)
  ref.setInput('error', error)
  ref.changeDetectorRef.detectChanges()
  return ref
}

const host = (ref: ComponentRef<VoiceRecorderComponent>) => ref.location.nativeElement as HTMLElement

describe('VoiceRecorderComponent', () => {
  afterEach(() => TestBed.resetTestingModule())

  it('renders nothing when idle', () => {
    expect(host(makeComponent('idle')).textContent?.trim()).toBe('')
  })

  it('shows the indicator, the elapsed time and the two buttons while recording', () => {
    const ref = makeComponent('recording', 65_000)
    expect(host(ref).querySelector('.voice-recorder__pulse')).not.toBeNull()
    expect(host(ref).querySelector('.voice-recorder__time')?.textContent).toBe('01:05')
    expect(host(ref).querySelectorAll('button')).toHaveLength(2)
  })

  it('emits cancelled and sent from the two buttons', () => {
    const ref = makeComponent('recording')
    const events: string[] = []
    ref.instance.cancelled.subscribe(() => events.push('cancelled'))
    ref.instance.sent.subscribe(() => events.push('sent'))
    const [cancel, send] = Array.from(host(ref).querySelectorAll('button'))

    cancel!.click()
    send!.click()

    expect(events).toEqual(['cancelled', 'sent'])
  })

  it('shows a progress line and no button while sending and transcribing', () => {
    const ref = makeComponent('sending')
    expect(host(ref).textContent).toContain('Sending and transcribing')
    expect(host(ref).querySelectorAll('button')).toHaveLength(0)
  })

  it('shows the error inline', () => {
    const ref = makeComponent('error', 0, 'Microphone access was denied.')
    expect(host(ref).querySelector('[role="alert"]')?.textContent).toContain('Microphone access was denied.')
    expect(host(ref).querySelector('.voice-recorder__pulse')).toBeNull()
  })
})
