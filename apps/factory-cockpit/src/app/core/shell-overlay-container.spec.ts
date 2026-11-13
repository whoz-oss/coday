import { Component } from '@angular/core'
import { TestBed } from '@angular/core/testing'
import { Overlay, OverlayContainer } from '@angular/cdk/overlay'
import { ComponentPortal } from '@angular/cdk/portal'
import { ShellOverlayContainer } from './shell-overlay-container'

@Component({ selector: 'sf-overlay-probe', template: '<p class="probe">probe</p>' })
class OverlayProbeComponent {}

describe('ShellOverlayContainer', () => {
  beforeEach(() => {
    document.querySelectorAll('agentos-shell, .cdk-overlay-container').forEach((el) => el.remove())
    TestBed.configureTestingModule({ providers: [{ provide: OverlayContainer, useClass: ShellOverlayContainer }] })
  })

  afterEach(() => {
    TestBed.resetTestingModule()
    document.querySelectorAll('agentos-shell, .cdk-overlay-container').forEach((el) => el.remove())
  })

  it('creates the overlay container inside agentos-shell, so overlays inherit the design tokens', () => {
    const shell = document.createElement('agentos-shell')
    document.body.appendChild(shell)

    const container = TestBed.inject(OverlayContainer).getContainerElement()

    expect(container.classList.contains('cdk-overlay-container')).toBe(true)
    expect(container.parentElement).toBe(shell)
    expect(document.querySelectorAll('.cdk-overlay-container')).toHaveLength(1)
  })

  it('renders an overlay inside agentos-shell', () => {
    const shell = document.createElement('agentos-shell')
    document.body.appendChild(shell)

    const ref = TestBed.inject(Overlay).create()
    ref.attach(new ComponentPortal(OverlayProbeComponent))
    TestBed.inject(OverlayContainer)

    expect(shell.querySelector('.cdk-overlay-container .probe')).not.toBeNull()
    ref.dispose()
  })

  it('falls back to <body> when there is no agentos-shell', () => {
    const container = TestBed.inject(OverlayContainer).getContainerElement()

    expect(container.parentElement).toBe(document.body)
  })

  it('reuses the same container for every call', () => {
    const shell = document.createElement('agentos-shell')
    document.body.appendChild(shell)
    const overlayContainer = TestBed.inject(OverlayContainer)

    expect(overlayContainer.getContainerElement()).toBe(overlayContainer.getContainerElement())
    expect(shell.querySelectorAll('.cdk-overlay-container')).toHaveLength(1)
  })
})
