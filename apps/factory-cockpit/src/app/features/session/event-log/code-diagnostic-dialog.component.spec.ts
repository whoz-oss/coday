import { ComponentFixture, TestBed } from '@angular/core/testing'
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog'
import { CodeDiagnosticDialogComponent, CodeDiagnosticDialogData } from './code-diagnostic-dialog.component'

describe('CodeDiagnosticDialogComponent', () => {
  function render(data: CodeDiagnosticDialogData): ComponentFixture<CodeDiagnosticDialogComponent> {
    TestBed.configureTestingModule({
      imports: [CodeDiagnosticDialogComponent],
      providers: [
        { provide: MAT_DIALOG_DATA, useValue: data },
        { provide: MatDialogRef, useValue: { close: jest.fn() } },
      ],
    })
    const fixture = TestBed.createComponent(CodeDiagnosticDialogComponent)
    fixture.detectChanges()
    return fixture
  }

  afterEach(() => TestBed.resetTestingModule())

  it('renders only sections backed by present fields', () => {
    const fixture = render({ state: 'passed', verification: 'nx test', command: 'pnpm nx test client' })
    expect(fixture.nativeElement.textContent).toContain('Command')
    expect(fixture.nativeElement.textContent).not.toContain('stderr')
    expect(fixture.nativeElement.textContent).not.toContain('stdout')
    expect(fixture.nativeElement.textContent).not.toContain('Technical details')
  })

  it('marks bounded output as truncated', () => {
    const fixture = render({ state: 'failed', stderr: { excerpt: 'failure', truncated: true } })
    expect(fixture.nativeElement.textContent).toContain('stderr')
    expect(fixture.nativeElement.textContent).toContain('truncated')
  })

  it('provides a dialog close action', () => {
    const fixture = render({ state: 'failed' })
    const close = fixture.nativeElement.querySelector('button[mat-dialog-close]') as HTMLButtonElement
    expect(close?.textContent).toContain('Close')
  })
})
