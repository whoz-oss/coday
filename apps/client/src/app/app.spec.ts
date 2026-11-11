import { TestBed } from '@angular/core/testing'
import { HttpClientTestingModule } from '@angular/common/http/testing'
import { NavigationEnd, NavigationError, NavigationStart, Router } from '@angular/router'
import { Subject } from 'rxjs'
import { App } from './app'

describe('App', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [App, HttpClientTestingModule],
    }).compileComponents()
  })

  it('should create the app', () => {
    const fixture = TestBed.createComponent(App)
    const app = fixture.componentInstance
    expect(app).toBeTruthy()
  })

  describe('never a blank page', () => {
    const setup = () => {
      const fixture = TestBed.createComponent(App)
      const events = TestBed.inject(Router).events as unknown as Subject<unknown>
      const text = () => (fixture.nativeElement as HTMLElement).textContent ?? ''
      return { fixture, events, text }
    }

    it('shows a loader before the first navigation and while one is pending (e.g. a guard waiting for AgentOS)', () => {
      const { fixture, events, text } = setup()
      fixture.detectChanges()
      expect(text()).toContain('Loading Coday')

      events.next(new NavigationStart(1, '/home'))
      events.next(new NavigationEnd(1, '/home', '/home'))
      fixture.detectChanges()
      expect(text()).not.toContain('Loading Coday')

      events.next(new NavigationStart(2, '/home'))
      fixture.detectChanges()
      expect(text()).toContain('Loading Coday')
    })

    it('shows an error with a reload button when a navigation fails', () => {
      const { fixture, events, text } = setup()
      events.next(new NavigationStart(1, '/home'))
      events.next(new NavigationError(1, '/home', new Error('Failed to fetch dynamically imported module')))
      fixture.detectChanges()
      expect(text()).toContain('could not load this page')
      expect((fixture.nativeElement as HTMLElement).querySelector('button')).not.toBeNull()
    })
  })
})
