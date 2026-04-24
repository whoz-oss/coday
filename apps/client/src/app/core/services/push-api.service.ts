import { inject, Injectable } from '@angular/core'
import { HttpClient } from '@angular/common/http'
import { Observable } from 'rxjs'

/**
 * Pure HTTP service for push notification API endpoints.
 * Delegates all browser/SW logic to PushNotificationService.
 */
@Injectable({
  providedIn: 'root',
})
export class PushApiService {
  private readonly http = inject(HttpClient)

  /**
   * Fetch the VAPID public key from the server.
   */
  getVapidPublicKey(): Observable<{ publicKey: string }> {
    return this.http.get<{ publicKey: string }>('/api/push/vapid-public-key')
  }

  /**
   * Register a push subscription with the server.
   */
  subscribe(subscription: PushSubscriptionJSON): Observable<{ success: boolean }> {
    return this.http.post<{ success: boolean }>('/api/push/subscribe', subscription)
  }

  /**
   * Remove a push subscription from the server.
   */
  unsubscribe(endpoint: string): Observable<{ success: boolean }> {
    return this.http.delete<{ success: boolean }>('/api/push/unsubscribe', { body: { endpoint } })
  }
}
