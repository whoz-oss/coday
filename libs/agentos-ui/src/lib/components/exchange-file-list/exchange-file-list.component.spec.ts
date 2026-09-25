import { TestBed } from '@angular/core/testing'
import { ExchangeFileEntryScopeEnum } from '@whoz-oss/agentos-api-client'
import { ExchangeFileListComponent } from './exchange-file-list.component'

describe('Exchange file Git indicators', () => {
  beforeEach(() => TestBed.configureTestingModule({ imports: [ExchangeFileListComponent] }))

  it('opens the diff from the Git indicator while the filename still previews the file', () => {
    const fixture = TestBed.createComponent(ExchangeFileListComponent)
    fixture.componentRef.setInput('scope', ExchangeFileEntryScopeEnum.CASE)
    fixture.componentRef.setInput('rows', [
      { path: 'repo/src/test.ts', filename: 'test.ts', meta: '', icon: 'description', gitStatus: 'MODIFIED' },
    ])
    const diff = jest.fn()
    const preview = jest.fn()
    fixture.componentInstance.diffRequested.subscribe(diff)
    fixture.componentInstance.fileSelected.subscribe(preview)
    fixture.detectChanges()
    const indicator: HTMLButtonElement = fixture.nativeElement.querySelector('.exchange-item__git-status')
    expect(indicator.textContent?.trim()).toBe('M')
    expect(indicator.getAttribute('aria-label')).toContain('Modified: test.ts')
    indicator.click()
    expect(diff).toHaveBeenCalledWith({ scope: ExchangeFileEntryScopeEnum.CASE, path: 'repo/src/test.ts' })
    expect(preview).not.toHaveBeenCalled()
    fixture.nativeElement.querySelector('.exchange-item__main').click()
    expect(preview).toHaveBeenCalledTimes(1)
    fixture.componentRef.setInput('rows', [
      { path: 'repo/src/test.ts', filename: 'test.ts', meta: '', icon: 'description' },
    ])
    fixture.detectChanges()
    expect(fixture.nativeElement.querySelector('.exchange-item__git-status')).toBeNull()
  })

  it('routes a deleted row to its diff and offers no download or delete action', () => {
    const fixture = TestBed.createComponent(ExchangeFileListComponent)
    fixture.componentRef.setInput('scope', ExchangeFileEntryScopeEnum.CASE)
    fixture.componentRef.setInput('canWrite', true)
    fixture.componentRef.setInput('rows', [
      { path: 'repo/gone.ts', filename: 'gone.ts', meta: '', icon: 'description', gitStatus: 'DELETED', missing: true },
    ])
    const diff = jest.fn()
    const preview = jest.fn()
    fixture.componentInstance.diffRequested.subscribe(diff)
    fixture.componentInstance.fileSelected.subscribe(preview)
    fixture.detectChanges()
    fixture.nativeElement.querySelector('.exchange-item__main').click()
    expect(diff).toHaveBeenCalledWith({ scope: ExchangeFileEntryScopeEnum.CASE, path: 'repo/gone.ts' })
    expect(preview).not.toHaveBeenCalled()
    expect(fixture.nativeElement.querySelector('.exchange-item__actions')).toBeNull()
    expect(fixture.nativeElement.querySelector('.exchange-item--missing')).not.toBeNull()
  })
})
