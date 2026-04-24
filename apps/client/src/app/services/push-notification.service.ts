import { inject, Injectable } from '@angular/core'
import { firstValueFrom } from 'rxjs'
import { PushApiService } from '../core/services/push-api.service'

/**
 * Service managing Web Push notification subscriptions.
 * Registers the service worker, subscribes to push notifications
 * and syncs the subscription with the Coday server via PushApiService.
 */
@Injectable({
  providedIn: 'root',
})
export class PushNotificationService {
  private readonly pushApi = inject(PushApiService)

  /**
   * Returns true if push notifications are supported in this browser.
   */
  isSupported(): boolean {
    return 'serviceWorker' in navigator && 'PushManager' in window && 'Notification' in window
  }

  /**
   * Subscribe to push notifications.
   * Requests notification permission, fetches VAPID key and registers the subscription.
   * Returns true on success, false on failure.
   */
  async subscribe(): Promise<boolean> {
    if (!this.isSupported()) {
      console.warn('[PUSH] Push notifications not supported')
      return false
    }

    try {
      // Request notification permission
      const permission = await Notification.requestPermission()
      if (permission !== 'granted') {
        console.warn('[PUSH] Notification permission denied')
        return false
      }

      // Register our custom service worker (idempotent — returns existing if already registered)
      await navigator.serviceWorker.register('/sw.js')

      // Get VAPID public key from server
      const { publicKey } = await firstValueFrom(this.pushApi.getVapidPublicKey())

      // Wait for the service worker to be ready
      const registration = await navigator.serviceWorker.ready

      // Subscribe to push
      const subscription = await registration.pushManager.subscribe({
        userVisibleOnly: true,
        applicationServerKey: this.urlBase64ToUint8Array(publicKey),
      })

      // Send subscription to server
      await firstValueFrom(this.pushApi.subscribe(subscription.toJSON()))

      console.log('[PUSH] Subscribed successfully')
      return true
    } catch (error) {
      console.error('[PUSH] Subscription failed:', error)
      return false
    }
  }

  /**
   * Unsubscribe from push notifications.
   */
  async unsubscribe(): Promise<void> {
    try {
      const registration = await navigator.serviceWorker.ready
      const subscription = await registration.pushManager.getSubscription()

      if (subscription) {
        await firstValueFrom(this.pushApi.unsubscribe(subscription.endpoint))
        await subscription.unsubscribe()
        console.log('[PUSH] Unsubscribed successfully')
      }
    } catch (error) {
      console.error('[PUSH] Unsubscribe failed:', error)
    }
  }

  /**
   * Convert base64 VAPID key to Uint8Array for PushManager.
   */
  private urlBase64ToUint8Array(base64String: string): Uint8Array<ArrayBuffer> {
    const padding = '='.repeat((4 - (base64String.length % 4)) % 4)
    const base64 = (base64String + padding).replace(/-/g, '+').replace(/_/g, '/')
    const rawData = atob(base64)
    const buffer = new ArrayBuffer(rawData.length)
    const view = new Uint8Array(buffer)
    for (let i = 0; i < rawData.length; i++) {
      view[i] = rawData.charCodeAt(i)
    }
    return view
  }
}
