import { TestBed } from '@angular/core/testing'
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog'
import { Subject, of } from 'rxjs'
import { ExchangeEnvironmentService } from '../../services/exchange-environment.service'
import { ExchangeDiffComponent } from './exchange-diff.component'

describe('ExchangeDiffComponent', () => {
  const files = [
    { path: 'first.txt', additions: 1, deletions: 0, untracked: false },
    { path: 'second.txt', additions: 1, deletions: 0, untracked: false },
  ]
  const patch = (name: string, content: string) =>
    `diff --git a/${name} b/${name}\n--- a/${name}\n+++ b/${name}\n@@ -0,0 +1 @@\n+${content}\n`
  async function setup(diff: jest.Mock) {
    await TestBed.configureTestingModule({
      imports: [ExchangeDiffComponent],
      providers: [
        { provide: ExchangeEnvironmentService, useValue: { diff } },
        { provide: MatDialogRef, useValue: { close: jest.fn() } },
        {
          provide: MAT_DIALOG_DATA,
          useValue: {
            scope: { kind: 'cases', id: 'root' },
            environment: { changes: { files, additions: 2, deletions: 0, base: '12345678' } },
          },
        },
      ],
    }).compileComponents()
    const fixture = TestBed.createComponent(ExchangeDiffComponent)
    fixture.detectChanges()
    return fixture
  }
  it('ignores a late response from the previously selected file', async () => {
    const first = new Subject<{ patch: string }>()
    const second = new Subject<{ patch: string }>()
    const diff = jest.fn().mockReturnValueOnce(first).mockReturnValueOnce(second)
    const fixture = await setup(diff)
    expect(first.observed).toBe(true)
    fixture.nativeElement.querySelectorAll('nav button')[1].click()
    expect(first.observed).toBe(false)
    second.next({ patch: patch('second.txt', 'second content') })
    second.complete()
    await fixture.whenStable()
    first.next({ patch: patch('first.txt', 'stale content') })
    first.complete()
    await fixture.whenStable()
    fixture.detectChanges()
    expect(fixture.nativeElement.textContent).toContain('second content')
    expect(fixture.nativeElement.textContent).not.toContain('stale content')
  })
  it('renders source markup as text and filters filenames', async () => {
    const fixture = await setup(
      jest.fn().mockReturnValue(of({ patch: patch('first.txt', '<img src=x onerror=alert(1)>') }))
    )
    await fixture.whenStable()
    fixture.detectChanges()
    expect(fixture.nativeElement.querySelector('.diff-content img')).toBeNull()
    expect(fixture.nativeElement.textContent).toContain('<img src=x onerror=alert(1)>')
    const input = fixture.nativeElement.querySelector('input')
    input.value = 'second'
    input.dispatchEvent(new Event('input'))
    fixture.detectChanges()
    expect(fixture.nativeElement.querySelectorAll('nav button')).toHaveLength(1)
  })
  it('cancels an unfinished diff request when the dialog is destroyed', async () => {
    const pending = new Subject<{ patch: string }>()
    const fixture = await setup(jest.fn().mockReturnValue(pending))
    expect(pending.observed).toBe(true)
    fixture.destroy()
    expect(pending.observed).toBe(false)
  })
})
