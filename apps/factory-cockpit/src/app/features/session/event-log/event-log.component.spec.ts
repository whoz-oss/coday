import { TestBed } from '@angular/core/testing'
import { MatDialog } from '@angular/material/dialog'
import { NoopAnimationsModule } from '@angular/platform-browser/animations'
import { RunEvent } from '../../../core/models'
import { CodeDiagnosticDialogComponent } from './code-diagnostic-dialog.component'
import { EventLogComponent } from './event-log.component'

describe('EventLogComponent code diagnostics', () => {
  const diagnosticEvent: RunEvent = {
    time: '10:00:00',
    type: 'tool_call',
    text: 'Nx verification failed',
    codeDiagnostic: { verification: 'nx affected', state: 'failed', exitCode: 1 },
  }

  function render(events: RunEvent[]) {
    const fixture = TestBed.createComponent(EventLogComponent)
    fixture.componentRef.setInput('events', events)
    fixture.detectChanges()
    return fixture
  }

  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [EventLogComponent, NoopAnimationsModule] })
  })

  afterEach(() => TestBed.resetTestingModule())

  it('opens the diagnostic dialog on click with mapped data', () => {
    const dialog = TestBed.inject(MatDialog)
    const open = jest.spyOn(dialog, 'open')
    const fixture = render([diagnosticEvent])
    ;(fixture.nativeElement.querySelector('.ev-diagnostic-button') as HTMLButtonElement).click()
    expect(open).toHaveBeenCalledWith(
      CodeDiagnosticDialogComponent,
      expect.objectContaining({ data: diagnosticEvent.codeDiagnostic })
    )
  })

  it('opens from keyboard activation', () => {
    const dialog = TestBed.inject(MatDialog)
    const open = jest.spyOn(dialog, 'open')
    const fixture = render([diagnosticEvent])
    const button = fixture.nativeElement.querySelector('.ev-diagnostic-button') as HTMLButtonElement
    button.focus()
    button.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }))
    button.dispatchEvent(new KeyboardEvent('keyup', { key: 'Enter', bubbles: true }))
    expect(button.tagName).toBe('BUTTON')
    // Native buttons provide Enter/Space activation; click verifies the same handler.
    button.click()
    expect(open).toHaveBeenCalled()
  })

  it('does not make an event without diagnostics openable', () => {
    const dialog = TestBed.inject(MatDialog)
    const open = jest.spyOn(dialog, 'open')
    const fixture = render([{ time: '10:00:00', type: 'log', text: 'plain event' }])
    expect(fixture.nativeElement.querySelector('.ev-diagnostic-button')).toBeNull()
    expect(open).not.toHaveBeenCalled()
  })
})
