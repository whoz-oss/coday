import { TestBed } from '@angular/core/testing'
import {
  ExchangeDrawerWidthService,
  FILES_DRAWER_DEFAULT_WIDTH,
  FILES_DRAWER_MIN_WIDTH,
  FILES_DRAWER_WIDTH_KEY,
} from './exchange-drawer-width.service'

describe('ExchangeDrawerWidthService', () => {
  const service = () => {
    TestBed.resetTestingModule()
    return TestBed.inject(ExchangeDrawerWidthService)
  }

  beforeEach(() => {
    localStorage.clear()
    Object.defineProperty(window, 'innerWidth', { value: 1000, configurable: true, writable: true })
  })

  it('starts at the default width when nothing is stored', () => {
    expect(service().width()).toBe(FILES_DRAWER_DEFAULT_WIDTH)
  })

  it('reads the stored width, clamped', () => {
    localStorage.setItem(FILES_DRAWER_WIDTH_KEY, '600')
    expect(service().width()).toBe(600)
    localStorage.setItem(FILES_DRAWER_WIDTH_KEY, '5000')
    expect(service().width()).toBe(800)
    localStorage.setItem(FILES_DRAWER_WIDTH_KEY, '10')
    expect(service().width()).toBe(FILES_DRAWER_MIN_WIDTH)
  })

  it('falls back to the default for a garbage stored value', () => {
    localStorage.setItem(FILES_DRAWER_WIDTH_KEY, 'wide')
    expect(service().width()).toBe(FILES_DRAWER_DEFAULT_WIDTH)
  })

  it('stores the new width, clamped, and shares it between the injectors of the two pages', () => {
    const widths = service()
    widths.setWidth(550.4)
    expect(widths.width()).toBe(550)
    expect(localStorage.getItem(FILES_DRAWER_WIDTH_KEY)).toBe('550')
    widths.setWidth(99999)
    expect(widths.width()).toBe(800)
    expect(TestBed.inject(ExchangeDrawerWidthService).width()).toBe(800)
  })
})
