import { ComponentFixture, TestBed } from '@angular/core/testing'
import { ThinkingLoaderComponent } from './thinking-loader.component'

describe('ThinkingLoaderComponent', () => {
  let component: ThinkingLoaderComponent
  let fixture: ComponentFixture<ThinkingLoaderComponent>

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [ThinkingLoaderComponent],
    }).compileComponents()

    fixture = TestBed.createComponent(ThinkingLoaderComponent)
    component = fixture.componentInstance
    fixture.detectChanges()
  })

  it('should create', () => {
    expect(component).toBeTruthy()
  })

  it('should display the initial thinking phrase', () => {
    const compiled = fixture.nativeElement as HTMLElement
    const thinkingText = compiled.querySelector('.thinking-text')
    expect(thinkingText?.textContent).toContain('Processing request...')
  })

  it('should display the coday logo', () => {
    const compiled = fixture.nativeElement as HTMLElement
    const logo = compiled.querySelector('.coday-logo') as HTMLImageElement
    expect(logo).toBeTruthy()
    expect(logo?.src).toContain('CODAY-Logo.png')
  })

  it('should render as an assistant message', () => {
    const compiled = fixture.nativeElement as HTMLElement
    const messageWrapper = compiled.querySelector('.message-wrapper')
    const message = compiled.querySelector('.message.assistant')
    expect(messageWrapper).toBeTruthy()
    expect(message).toBeTruthy()
  })

  it('should cycle through thinking phrases', (done) => {
    // Wait for the first interval (2 seconds)
    setTimeout(() => {
      fixture.detectChanges()
      expect(component.currentThinkingPhrase).toBe('Thinking...')

      // Wait for the second interval (4 seconds total)
      setTimeout(() => {
        fixture.detectChanges()
        expect(component.currentThinkingPhrase).toBe('Working on it...')

        // Verify it stays at the last phrase
        setTimeout(() => {
          fixture.detectChanges()
          expect(component.currentThinkingPhrase).toBe('Working on it...')
          done()
        }, 2100)
      }, 2100)
    }, 2100)
  }, 10000) // Increase timeout for this test

  it('should stop animation on destroy', () => {
    const stopSpy = jest.spyOn(component as any, 'stopThinkingAnimation')
    component.ngOnDestroy()
    expect(stopSpy).toHaveBeenCalled()
  })
})
