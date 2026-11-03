import { ComponentFixture, TestBed } from '@angular/core/testing'
import { Subject } from 'rxjs'
import { createRowDeletionState, RowDeletionState } from '../../utils/row-deletion-state'
import { RowDeleteActionComponent } from './row-delete-action.component'

describe('RowDeleteActionComponent', () => {
  let fixture: ComponentFixture<RowDeleteActionComponent>
  let state: RowDeletionState

  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [RowDeleteActionComponent] })
    state = createRowDeletionState()
    fixture = TestBed.createComponent(RowDeleteActionComponent)
    fixture.componentRef.setInput('rowKey', 'row-1')
    fixture.componentRef.setInput('rowLabel', 'Acme provider')
    fixture.componentRef.setInput('state', state)
    fixture.detectChanges()
  })

  function buttons(): HTMLButtonElement[] {
    return Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('button'))
  }

  it('shows normal delete mode with an accessible row label and emits request', () => {
    const request = jest.fn()
    fixture.componentInstance.deleteRequested.subscribe(request)

    expect(buttons().map((button) => button.textContent?.trim())).toEqual(['Delete'])
    expect(buttons()[0].getAttribute('aria-label')).toBe('Delete Acme provider')

    buttons()[0].click()
    expect(request).toHaveBeenCalledTimes(1)
  })

  it('shows confirmation controls with row labels and emits confirm and cancel', () => {
    const confirm = jest.fn()
    const cancel = jest.fn()
    fixture.componentInstance.deleteConfirmed.subscribe(confirm)
    fixture.componentInstance.deleteCancelled.subscribe(cancel)
    state.request('row-1')
    fixture.detectChanges()

    expect(buttons().map((button) => button.textContent?.trim())).toEqual(['Confirm', 'Cancel'])
    expect(buttons().map((button) => button.getAttribute('aria-label'))).toEqual([
      'Confirm deletion of Acme provider',
      'Cancel deletion of Acme provider',
    ])

    buttons()[0].click()
    buttons()[1].click()
    expect(confirm).toHaveBeenCalledTimes(1)
    expect(cancel).toHaveBeenCalledTimes(1)
  })

  it('shows deleting text and disables confirmation controls while deleting', () => {
    state.request('row-1')
    state.execute('row-1', { request$: new Subject<void>(), errorMessage: 'Delete failed' })
    fixture.detectChanges()

    expect(buttons().map((button) => button.textContent?.trim())).toEqual(['Deleting…', 'Cancel'])
    expect(buttons().every((button) => button.disabled)).toBe(true)
    expect(buttons().map((button) => button.getAttribute('aria-label'))).toEqual([
      'Confirm deletion of Acme provider',
      'Cancel deletion of Acme provider',
    ])
  })
})
