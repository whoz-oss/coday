import { fakeAsync, TestBed, tick } from '@angular/core/testing'
import { MatDialog } from '@angular/material/dialog'
import { of, throwError } from 'rxjs'
import { CaseWorkspaceService } from '../../services/case-workspace.service'
import { ExchangeEnvironmentService } from '../../services/exchange-environment.service'
import { ExchangeEnvironmentComponent } from './exchange-environment.component'

describe('ExchangeEnvironmentComponent', () => {
  it('stays hidden and stops polling on an instance without Git workspaces', fakeAsync(() => {
    const get = jest.fn().mockImplementation(() => throwError(() => ({ status: 404 })))
    TestBed.configureTestingModule({
      imports: [ExchangeEnvironmentComponent],
      providers: [
        { provide: ExchangeEnvironmentService, useValue: { get } },
        { provide: CaseWorkspaceService, useValue: { watch: () => of({ view: null, errorStatus: 404 }) } },
        { provide: MatDialog, useValue: { open: jest.fn() } },
      ],
    })
    const fixture = TestBed.createComponent(ExchangeEnvironmentComponent)
    fixture.componentRef.setInput('scope', { id: 'case-1' })
    fixture.detectChanges()
    tick(30_000)
    fixture.detectChanges()

    expect(get).toHaveBeenCalledTimes(1)
    expect(fixture.nativeElement.textContent).not.toContain('Environment unavailable')
    fixture.destroy()
  }))

  it('asks nothing once the instance is known to have no Git workspaces', () => {
    const get = jest.fn()
    const watch = jest.fn()
    TestBed.configureTestingModule({
      imports: [ExchangeEnvironmentComponent],
      providers: [
        { provide: ExchangeEnvironmentService, useValue: { get } },
        { provide: CaseWorkspaceService, useValue: { unavailable: true, watch } },
        { provide: MatDialog, useValue: { open: jest.fn() } },
      ],
    })
    const fixture = TestBed.createComponent(ExchangeEnvironmentComponent)
    fixture.componentRef.setInput('scope', { id: 'case-1' })
    fixture.detectChanges()

    expect(get).not.toHaveBeenCalled()
    expect(watch).not.toHaveBeenCalled()
    fixture.destroy()
  })
})
