import { ComponentFixture, TestBed } from '@angular/core/testing'
import { Subject } from 'rxjs'
import { AgentStateService } from '../../services/agent-state.service'
import { PromptStateService } from '../../services/prompt-state.service'
import { USER_PREFERENCES_PORT } from '../../services/user-preferences.service'
import { SelectionComposerComponent } from './selection-composer.component'

describe('SelectionComposerComponent', () => {
  let fixture: ComponentFixture<SelectionComposerComponent>

  const el = (): HTMLElement => fixture.nativeElement
  const input = (): HTMLTextAreaElement => el().querySelector('.selection-composer__input') as HTMLTextAreaElement

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [SelectionComposerComponent],
      providers: [
        { provide: USER_PREFERENCES_PORT, useValue: { shouldSend: (e: KeyboardEvent) => e.key === 'Enter' } },
        { provide: PromptStateService, useValue: { listEffective: jest.fn() } },
        { provide: AgentStateService, useValue: { listEffective: jest.fn().mockReturnValue(new Subject()) } },
      ],
    })
    fixture = TestBed.createComponent(SelectionComposerComponent)
    fixture.detectChanges()
  })

  function write(text: string): void {
    input().value = text
    input().dispatchEvent(new Event('input'))
    fixture.detectChanges()
  }

  it('has a short placeholder, one line high by default', () => {
    expect(input().placeholder).toBe('Ask an agent… (@ to pick one)')
    expect(input().rows).toBe(1)
  })

  it('does not take the focus on its own', () => {
    expect(document.activeElement).not.toBe(input())
  })

  it('tells whether an instruction is written', () => {
    expect(fixture.componentInstance.hasText()).toBe(false)
    write('  ')
    expect(fixture.componentInstance.hasText()).toBe(false)
    write('fix it')
    expect(fixture.componentInstance.hasText()).toBe(true)
  })

  it('takes a typed key and the focus, with the caret after it', () => {
    document.body.appendChild(el())
    fixture.componentInstance.type('h')
    fixture.detectChanges()
    TestBed.flushEffects?.()
    fixture.detectChanges()
    expect(input().value).toBe('h')
    expect(fixture.componentInstance.isFocused()).toBe(true)
    el().remove()
  })

  it('opens its suggestions away from the selection when asked to', () => {
    expect(el().querySelector('.selection-composer--suggestions-below')).toBeNull()
    fixture.componentRef.setInput('suggestionsPlacement', 'below')
    fixture.detectChanges()
    expect(el().querySelector('.selection-composer--suggestions-below')).not.toBeNull()
  })

  it('sends the trimmed instruction on Enter and closes on Escape', () => {
    const sent = jest.fn()
    const closed = jest.fn()
    fixture.componentInstance.sent.subscribe(sent)
    fixture.componentInstance.closed.subscribe(closed)
    write(' @Writer fix ')
    input().dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', cancelable: true }))
    expect(sent).toHaveBeenCalledWith('@Writer fix')
    input().dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', cancelable: true }))
    expect(closed).toHaveBeenCalledTimes(1)
  })
})
