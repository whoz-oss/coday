import express from 'express'
import { PushNotificationService, PushSubscriptionData } from './push-notification.service'
import { debugLog } from './log'

/**
 * Push Notification REST API Routes
 *
 * GET    /api/push/vapid-public-key  → VAPID public key for client subscription
 * POST   /api/push/subscribe          → Register a push subscription
 * DELETE /api/push/unsubscribe        → Remove a push subscription
 */
export function registerPushRoutes(
  app: express.Application,
  pushService: PushNotificationService,
  getUsernameFn: (req: express.Request) => string
): void {
  app.get('/api/push/vapid-public-key', (_req: express.Request, res: express.Response) => {
    try {
      res.status(200).json({ publicKey: pushService.getVapidPublicKey() })
    } catch (error) {
      console.error('[PUSH] Error getting VAPID public key:', error)
      res.status(500).json({ error: 'Failed to get VAPID public key' })
    }
  })

  app.post('/api/push/subscribe', async (req: express.Request, res: express.Response) => {
    try {
      const username = getUsernameFn(req)
      const subscription = req.body as PushSubscriptionData

      if (!subscription?.endpoint || !subscription?.keys?.p256dh || !subscription?.keys?.auth) {
        res.status(400).json({ error: 'Invalid subscription' })
        return
      }

      await pushService.saveSubscription(username, subscription)
      debugLog('PUSH', `Subscription registered for ${username}`)
      res.status(201).json({ success: true })
    } catch (error) {
      console.error('[PUSH] Error saving subscription:', error)
      res.status(500).json({ error: 'Failed to save subscription' })
    }
  })

  app.delete('/api/push/unsubscribe', async (req: express.Request, res: express.Response) => {
    try {
      const username = getUsernameFn(req)
      const { endpoint } = req.body as { endpoint: string }

      if (!endpoint) {
        res.status(400).json({ error: 'Missing endpoint' })
        return
      }

      await pushService.removeSubscription(username, endpoint)
      res.status(200).json({ success: true })
    } catch (error) {
      console.error('[PUSH] Error removing subscription:', error)
      res.status(500).json({ error: 'Failed to remove subscription' })
    }
  })
}
