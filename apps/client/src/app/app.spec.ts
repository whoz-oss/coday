import { TestBed } from '@angular/core/testing'
import { HttpClientTestingModule } from '@angular/common/http/testing'
import { App } from './app'
import { WINDOW, windowFactory } from './core/tokens/window'

describe('App', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [App, HttpClientTestingModule],
      providers: [{ provide: WINDOW, useFactory: windowFactory }],
    }).compileComponents()
  })

  it('should create the app', () => {
    const fixture = TestBed.createComponent(App)
    const app = fixture.componentInstance
    expect(app).toBeTruthy()
  })
})
