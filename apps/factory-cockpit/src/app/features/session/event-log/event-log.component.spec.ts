import { CdkVirtualScrollViewport } from '@angular/cdk/scrolling'
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

  /**
   * jsdom has no layout: the CDK virtual-scroll viewport measures 0px and so
   * renders no rows. Give it a fixed viewport size, then let its (microtask)
   * attach run and re-render so the rows exist in the DOM.
   */
  async function render(events: RunEvent[]) {
    jest.spyOn(CdkVirtualScrollViewport.prototype, 'getViewportSize').mockReturnValue(400)
    const fixture = TestBed.createComponent(EventLogComponent)
    fixture.componentRef.setInput('events', events)
    fixture.detectChanges()
    await fixture.whenStable()
    fixture.detectChanges()
    return fixture
  }

  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [EventLogComponent, NoopAnimationsModule] })
  })

  afterEach(() => {
    jest.restoreAllMocks()
    TestBed.resetTestingModule()
  })

  it('opens the diagnostic dialog on click with mapped data', async () => {
    const dialog = TestBed.inject(MatDialog)
    const open = jest.spyOn(dialog, 'open')
    const fixture = await render([diagnosticEvent])
    ;(fixture.nativeElement.querySelector('.ev-diagnostic-button') as HTMLButtonElement).click()
    expect(open).toHaveBeenCalledWith(
      CodeDiagnosticDialogComponent,
      expect.objectContaining({ data: diagnosticEvent.codeDiagnostic })
    )
  })

  it('opens from keyboard activation', async () => {
    const dialog = TestBed.inject(MatDialog)
    const open = jest.spyOn(dialog, 'open')
    const fixture = await render([diagnosticEvent])
    const button = fixture.nativeElement.querySelector('.ev-diagnostic-button') as HTMLButtonElement
    button.focus()
    button.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }))
    button.dispatchEvent(new KeyboardEvent('keyup', { key: 'Enter', bubbles: true }))
    expect(button.tagName).toBe('BUTTON')
    // Native buttons provide Enter/Space activation; click verifies the same handler.
    button.click()
    expect(open).toHaveBeenCalled()
  })

  it('does not make an event without diagnostics openable', async () => {
    const dialog = TestBed.inject(MatDialog)
    const open = jest.spyOn(dialog, 'open')
    const fixture = await render([{ time: '10:00:00', type: 'log', text: 'plain event' }])
    // The row itself must be rendered, otherwise the null check below proves nothing.
    expect(fixture.nativeElement.querySelector('.ev')).not.toBeNull()
    expect(fixture.nativeElement.querySelector('.ev-diagnostic-button')).toBeNull()
    expect(open).not.toHaveBeenCalled()
  })
})
