import { Injectable, inject, OnDestroy } from '@angular/core'
import { Subject } from 'rxjs'
import { takeUntil, pairwise, filter } from 'rxjs/operators'
import { CodayService } from '../core/services/coday.service'
import { PreferencesService } from './preferences.service'

/**
 * Service responsible for notifying the user when the agent finishes a task
 * and is waiting for user action.
 *
 * This service monitors the agent's thinking state and triggers notifications
 * when the agent transitions from "thinking" to "idle".
 */
@Injectable({
  providedIn: 'root',
})
export class AgentNotificationService implements OnDestroy {
  private destroy$ = new Subject<void>()
  private isSetup = false

  private codayService = inject(CodayService)
  private preferencesService = inject(PreferencesService)

  /**
   * Set up the notification service to start monitoring agent state
   * Should be called once during application initialization
   */
  setup(): void {
    if (this.isSetup) {
      console.warn('[AGENT-NOTIFICATION] Service already set up')
      return
    }

    console.log('[AGENT-NOTIFICATION] Setting up agent notification service')

    // Monitor thinking state transitions
    this.codayService.isThinking$
      .pipe(
        takeUntil(this.destroy$),
        pairwise(), // Get [previousValue, currentValue]
        filter(([previous, current]) => previous === true && current === false) // Detect thinking → idle
      )
      .subscribe(() => {
        console.log('[AGENT-NOTIFICATION] Agent stopped thinking, notifying task finished')
        this.notifyTaskFinishedIfEnabled()
      })

    // Monitor browser notification preference changes
    this.preferencesService.browserNotificationEnabled$
      .pipe(
        takeUntil(this.destroy$),
        pairwise(), // Get [previousValue, currentValue]
        filter(([previous, current]) => previous === false && current === true) // Detect disabled → enabled
      )
      .subscribe(() => {
        console.log('[AGENT-NOTIFICATION] Browser notifications enabled, checking permission')
        this.handleBrowserNotificationEnabled()
      })

    this.isSetup = true
  }

  /**
   * Notify the user that the agent has finished a task
   * Uses browser notification API and sound notification based on preferences
   */
  private notifyTaskFinishedIfEnabled(): void {
    // Check if agent notifications are enabled
    const notificationsEnabled = this.preferencesService.getAgentNotificationEnabled()
    if (notificationsEnabled) {
      const soundEnabled = this.preferencesService.getNotificationSoundEnabled()
      if (soundEnabled) {
        this.playCompletionSound()
      }

      // Send browser notification if enabled and tab is not focused
      const browserNotificationEnabled = this.preferencesService.getBrowserNotificationEnabled()
      if (browserNotificationEnabled) {
        this.sendBrowserNotification()
      }
    }
  }

  /**
   * Play a completion notification sound (double tone)
   * Creates a rising "your turn" signal with two tones
   */
  private playCompletionSound(): void {
    try {
      const audioContext = new (window.AudioContext || (window as any).webkitAudioContext)()

      // First tone: 700Hz
      const oscillator1 = audioContext.createOscillator()
      const gainNode1 = audioContext.createGain()

      oscillator1.connect(gainNode1)
      gainNode1.connect(audioContext.destination)

      oscillator1.frequency.setValueAtTime(700, audioContext.currentTime)
      gainNode1.gain.setValueAtTime(0.1, audioContext.currentTime)
      gainNode1.gain.exponentialRampToValueAtTime(0.01, audioContext.currentTime + 0.08)

      oscillator1.start(audioContext.currentTime)
      oscillator1.stop(audioContext.currentTime + 0.08)

      // Second tone: 950Hz (after a 0.03s pause)
      const oscillator2 = audioContext.createOscillator()
      const gainNode2 = audioContext.createGain()

      oscillator2.connect(gainNode2)
      gainNode2.connect(audioContext.destination)

      const startTime2 = audioContext.currentTime + 0.11 // 0.08s + 0.03s pause
      oscillator2.frequency.setValueAtTime(950, startTime2)
      gainNode2.gain.setValueAtTime(0.1, startTime2)
      gainNode2.gain.exponentialRampToValueAtTime(0.01, startTime2 + 0.09)

      oscillator2.start(startTime2)
      oscillator2.stop(startTime2 + 0.09)

      console.log('[AGENT-NOTIFICATION] Completion sound played')
    } catch (error) {
      console.error('[AGENT-NOTIFICATION] Failed to play completion sound:', error)
    }
  }

  /**
   * Handle browser notification being enabled
   * Checks permission status and requests if needed
   */
  private async handleBrowserNotificationEnabled(): Promise<void> {
    // Check if browser supports notifications
    if (!('Notification' in window)) {
      console.warn('[AGENT-NOTIFICATION] Browser does not support notifications')
      this.preferencesService.setBrowserNotificationEnabled(false)
      return
    }

    // If permission is denied, disable the preference
    if (Notification.permission === 'denied') {
      console.warn('[AGENT-NOTIFICATION] Permission denied, disabling browser notifications')
      this.preferencesService.setBrowserNotificationEnabled(false)
      return
    }

    // If already granted, show confirmation notification
    if (Notification.permission === 'granted') {
      console.log('[AGENT-NOTIFICATION] Permission already granted, showing confirmation')
      this.createConfirmationNotification()
      return
    }

    // Request permission
    try {
      const permission = await Notification.requestPermission()
      if (permission === 'granted') {
        console.log('[AGENT-NOTIFICATION] Permission granted, showing confirmation')
        this.createConfirmationNotification()
      } else {
        console.log('[AGENT-NOTIFICATION] Permission denied or dismissed')
        this.preferencesService.setBrowserNotificationEnabled(false)
      }
    } catch (error) {
      console.error('[AGENT-NOTIFICATION] Error requesting permission:', error)
      this.preferencesService.setBrowserNotificationEnabled(false)
    }
  }

  /**
   * Send a browser notification using the Notification API
   */
  private sendBrowserNotification(): void {
    // Check if browser supports notifications
    if (!('Notification' in window)) {
      console.warn('[AGENT-NOTIFICATION] Browser does not support notifications')
      return
    }

    if (document.hasFocus()) {
      console.warn('[AGENT-NOTIFICATION] Document is focused, skipping browser notification')
      return
    }

    // Only send if permission is already granted
    // We don't request permission here because :
    // 1. The browser may not be focused
    // 2. Permission should have been granted when enabling browser notifications.
    if (Notification.permission === 'granted') {
      this.createTaskCompleteNotification()
    } else {
      console.warn('[AGENT-NOTIFICATION] Permission not granted, cannot send notification')
    }
  }

  /**
   * Create and display a confirmation notification when notifications are enabled
   */
  private createConfirmationNotification(): void {
    const notification = new Notification('Coday - Notifications Enabled', {
      body: 'You will be notified when the agent completes tasks',
      icon: '/favicon.ico',
      tag: 'coday-notifications-enabled',
      requireInteraction: false,
    })

    // Auto-close after 4 seconds
    setTimeout(() => notification.close(), 4000)

    // Focus window when notification is clicked
    notification.onclick = () => {
      window.focus()
      notification.close()
    }

    console.log('[AGENT-NOTIFICATION] Confirmation notification sent')
  }

  /**
   * Create and display a task completion browser notification
   */
  private createTaskCompleteNotification(): void {
    const notification = new Notification('Coday - Task Complete', {
      body: 'The agent has finished and is waiting for your input',
      icon: '/favicon.ico',
      tag: 'coday-task-complete', // Prevent duplicate notifications
      requireInteraction: false,
    })

    // Auto-close after 5 seconds
    setTimeout(() => notification.close(), 5000)

    // Focus window when notification is clicked
    notification.onclick = () => {
      window.focus()
      notification.close()
    }

    console.log('[AGENT-NOTIFICATION] Task complete notification sent')
  }

  ngOnDestroy(): void {
    console.log('[AGENT-NOTIFICATION] Service destroyed')
    this.destroy$.next()
    this.destroy$.complete()
  }
}
