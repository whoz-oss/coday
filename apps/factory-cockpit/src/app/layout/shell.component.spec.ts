import { ComponentFixture, TestBed } from '@angular/core/testing'
import { provideRouter } from '@angular/router'
import { provideNoopAnimations } from '@angular/platform-browser/animations'
import { signal } from '@angular/core'
import { FactoryStore } from '../core/factory.store'
import { routes } from '../app.routes'
import { ShellComponent } from './shell.component'

describe('Factory navigation simplification', () => {
  it('redirects old Settings bookmarks to workflows without loading the obsolete import page', () => {
    expect(routes.find((route) => route.path === 'reglages')).toEqual({
      path: 'reglages', pathMatch: 'full', redirectTo: 'workflows',
    })
  })

  it('keeps workflow navigation and removes Settings', async () => {
    await TestBed.configureTestingModule({
      imports: [ShellComponent],
      providers: [provideRouter([]), provideNoopAnimations(), {
        provide: FactoryStore,
        useValue: { costs: signal({ active: 0, totalUsd: 0, workflowsUsd: 0 }), showDestroyed: signal(false) },
      }],
    }).compileComponents()
    const fixture: ComponentFixture<ShellComponent> = TestBed.createComponent(ShellComponent)
    fixture.detectChanges()
    const host = fixture.nativeElement as HTMLElement
    expect(host.querySelector('a[href="/workflows"]')).not.toBeNull()
    expect(host.querySelector('a[href="/reglages"]')).toBeNull()
    expect(host.textContent).not.toContain('Settings')
  })
})
