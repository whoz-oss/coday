import {
  ApplicationRef,
  ComponentRef,
  EnvironmentInjector,
  Injectable,
  NgZone,
  createComponent,
  inject,
  signal,
} from '@angular/core'
import { MatSnackBar } from '@angular/material/snack-bar'
import { CompanionComponent } from '../components/companion/companion.component'

interface DocumentPictureInPicture {
  window: Window | null
  requestWindow(options?: { width?: number; height?: number }): Promise<Window>
}

declare global {
  interface Window {
    documentPictureInPicture?: DocumentPictureInPicture
  }
}

/**
 * Owns the lifecycle of the companion's floating window, opened via the browser's
 * native Document Picture-in-Picture API.
 *
 * Window lifecycle (simple model):
 * - toggle() ON  → ouvre la fenêtre immédiatement (user gesture requis par Chrome)
 * - toggle() OFF → ferme la fenêtre
 * - L'utilisateur ferme la fenêtre via le bouton natif du navigateur
 *   → pagehide détecté → companionEnabled passe à false (sync menu)
 * - Les notifs se ferment / s'ouvrent DANS la fenêtre, qui reste ouverte
 */
@Injectable({
  providedIn: 'root',
})
export class CompanionPipService {
  private readonly environmentInjector = inject(EnvironmentInjector)
  private readonly appRef = inject(ApplicationRef)
  private readonly ngZone = inject(NgZone)
  private readonly snackBar = inject(MatSnackBar)

  private componentRef: ComponentRef<CompanionComponent> | null = null
  private audioContext: AudioContext | null = null

  /** Whether the PiP window is currently open. Drives the menu badge "On". */
  readonly isOpen = signal(false)

  /**
   * Alias for isOpen — kept for template compatibility with companionEnabled.
   * The two are always in sync: the window is open ⇔ companion is enabled.
   */
  readonly companionEnabled = this.isOpen

  isSupported(): boolean {
    return typeof window !== 'undefined' && 'documentPictureInPicture' in window
  }

  /**
   * Toggle the companion window on/off.
   * MUST be called from a user gesture (click handler) — Chrome requires it for requestWindow().
   */
  toggle(): void {
    if (!this.isSupported()) {
      this.snackBar.open('Companion requires Chrome, Edge, or Firefox 151+.', 'Dismiss', { duration: 4000 })
      return
    }

    if (window.documentPictureInPicture?.window) {
      window.documentPictureInPicture.window.close()
    } else {
      void this.open()
    }
  }

  /**
   * Play an alert beep when a blocking notification arrives.
   * Called by CompanionStateService.
   */
  playAlertBeep(): void {
    const ctx = this.audioContext
    if (!ctx) return
    try {
      const oscillator = ctx.createOscillator()
      const gain = ctx.createGain()
      oscillator.type = 'sine'
      oscillator.frequency.value = 880
      gain.gain.setValueAtTime(0.15, ctx.currentTime)
      gain.gain.exponentialRampToValueAtTime(0.0001, ctx.currentTime + 0.25)
      oscillator.connect(gain)
      gain.connect(ctx.destination)
      oscillator.start()
      oscillator.stop(ctx.currentTime + 0.25)
    } catch (err) {
      console.warn('[Companion] failed to play alert beep', err)
    }
  }

  private async open(): Promise<void> {
    this.audioContext = new AudioContext()

    const pipWindow = await window.documentPictureInPicture!.requestWindow({ width: 360, height: 480 })

    const container = pipWindow.document.createElement('div')
    container.style.height = '100%'
    pipWindow.document.body.style.margin = '0'
    pipWindow.document.body.style.height = '100%'
    pipWindow.document.body.appendChild(container)

    this.componentRef = createComponent(CompanionComponent, {
      environmentInjector: this.environmentInjector,
      hostElement: container,
    })
    this.componentRef.setInput('audioContext', this.audioContext)
    this.appRef.attachView(this.componentRef.hostView)
    this.componentRef.changeDetectorRef.detectChanges()

    this.copyStylesheets(pipWindow)

    this.ngZone.run(() => {
      // Quand l'utilisateur ferme la fenêtre via le bouton natif du navigateur,
      // on sync le signal isOpen → false pour mettre le menu à jour.
      pipWindow.addEventListener('pagehide', () => this.ngZone.run(() => this.teardown()), { once: true })
      this.isOpen.set(true)
    })
  }

  private copyStylesheets(pipWindow: Window): void {
    for (const styleSheet of Array.from(document.styleSheets)) {
      try {
        const cssRules = Array.from(styleSheet.cssRules)
          .map((rule) => rule.cssText)
          .join('\n')
        const style = pipWindow.document.createElement('style')
        style.textContent = cssRules
        pipWindow.document.head.appendChild(style)
      } catch {
        if (styleSheet.href) {
          const link = pipWindow.document.createElement('link')
          link.rel = 'stylesheet'
          link.type = styleSheet.type
          link.href = styleSheet.href
          pipWindow.document.head.appendChild(link)
        }
      }
    }
  }

  private teardown(): void {
    if (this.componentRef) {
      this.appRef.detachView(this.componentRef.hostView)
      this.componentRef.destroy()
      this.componentRef = null
    }
    this.audioContext?.close()
    this.audioContext = null
    this.isOpen.set(false)
  }
}
