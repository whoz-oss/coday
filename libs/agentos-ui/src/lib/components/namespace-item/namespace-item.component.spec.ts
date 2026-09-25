import { TestBed } from '@angular/core/testing'
import { NamespaceItemComponent } from './namespace-item.component'

function chipLabels(gitAvailable: boolean): string[] {
  const fixture = TestBed.createComponent(NamespaceItemComponent)
  fixture.componentRef.setInput('namespace', { id: 'ns-1', name: 'Platform' })
  fixture.componentRef.setInput('gitAvailable', gitAvailable)
  fixture.detectChanges()
  const element: HTMLElement = fixture.nativeElement
  return Array.from(element.querySelectorAll('.ac-chip')).map((chip) => chip.textContent?.trim() ?? '')
}

it('shows the Git entry only while Git is available', () => {
  expect(chipLabels(true)).toContain('Git')
  expect(chipLabels(false)).toContain('Members')
  expect(chipLabels(false)).not.toContain('Git')
})
