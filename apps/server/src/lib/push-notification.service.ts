import webpush from 'web-push'
import * as fs from 'fs'
import * as path from 'path'
import { debugLog } from './log'

export interface PushSubscriptionData {
  endpoint: string
  keys: {
    p256dh: string
    auth: string
  }
}

interface VapidKeys {
  publicKey: string
  privateKey: string
}

interface UserSubscriptions {
  [username: string]: PushSubscriptionData[]
}

const VAPID_KEYS_FILE = 'push-vapid-keys.json'
const SUBSCRIPTIONS_FILE = 'push-subscriptions.json'

/**
 * Service managing VAPID keys and push subscriptions.
 * Keys and subscriptions are stored locally in ~/.coday/
 */
export class PushNotificationService {
  private vapidKeys: VapidKeys
  private subscriptions: UserSubscriptions = {}
  private readonly keysPath: string
  private readonly subscriptionsPath: string

  constructor(configDir: string) {
    this.keysPath = path.join(configDir, VAPID_KEYS_FILE)
    this.subscriptionsPath = path.join(configDir, SUBSCRIPTIONS_FILE)
    this.vapidKeys = this.loadOrGenerateVapidKeys()
    this.subscriptions = this.loadSubscriptions()

    webpush.setVapidDetails('mailto:coday@whoz.com', this.vapidKeys.publicKey, this.vapidKeys.privateKey)

    debugLog('PUSH', 'Push notification service initialized')
  }

  getVapidPublicKey(): string {
    return this.vapidKeys.publicKey
  }

  async saveSubscription(username: string, subscription: PushSubscriptionData): Promise<void> {
    if (!this.subscriptions[username]) {
      this.subscriptions[username] = []
    }

    // Avoid duplicates by endpoint
    const existing = this.subscriptions[username].findIndex((s) => s.endpoint === subscription.endpoint)
    if (existing >= 0) {
      this.subscriptions[username][existing] = subscription
    } else {
      this.subscriptions[username].push(subscription)
    }

    await this.persistSubscriptions()
    debugLog('PUSH', `Subscription saved for ${username}`)
  }

  async removeSubscription(username: string, endpoint: string): Promise<void> {
    if (this.subscriptions[username]) {
      this.subscriptions[username] = this.subscriptions[username].filter((s) => s.endpoint !== endpoint)
      await this.persistSubscriptions()
    }
    debugLog('PUSH', `Subscription removed for ${username}`)
  }

  async sendNotification(username: string, title: string, body: string, url?: string): Promise<void> {
    const userSubs = this.subscriptions[username]
    if (!userSubs || userSubs.length === 0) {
      debugLog('PUSH', `No subscriptions for ${username}`)
      return
    }

    const payload = JSON.stringify({ title, body, url: url || '/', icon: '/CODAY-Logo.png' })
    const expiredEndpoints: string[] = []

    for (const sub of userSubs) {
      try {
        await webpush.sendNotification(sub, payload)
        debugLog('PUSH', `Notification sent to ${username}`)
      } catch (error: any) {
        if (error.statusCode === 410 || error.statusCode === 404) {
          // Subscription expired or invalid
          expiredEndpoints.push(sub.endpoint)
          debugLog('PUSH', `Expired subscription removed for ${username}`)
        } else {
          console.error('[PUSH] Error sending notification:', error)
        }
      }
    }

    // Clean up expired subscriptions
    for (const ep of expiredEndpoints) {
      await this.removeSubscription(username, ep)
    }
  }

  private loadOrGenerateVapidKeys(): VapidKeys {
    if (fs.existsSync(this.keysPath)) {
      try {
        const raw = fs.readFileSync(this.keysPath, 'utf-8')
        const keys = JSON.parse(raw) as VapidKeys
        debugLog('PUSH', 'VAPID keys loaded from disk')
        return keys
      } catch (e) {
        console.error('[PUSH] Failed to read VAPID keys, regenerating')
      }
    }

    const keys = webpush.generateVAPIDKeys()
    fs.writeFileSync(this.keysPath, JSON.stringify(keys, null, 2))
    debugLog('PUSH', 'VAPID keys generated and saved')
    return keys
  }

  private loadSubscriptions(): UserSubscriptions {
    if (fs.existsSync(this.subscriptionsPath)) {
      try {
        const raw = fs.readFileSync(this.subscriptionsPath, 'utf-8')
        return JSON.parse(raw) as UserSubscriptions
      } catch (e) {
        console.error('[PUSH] Failed to read subscriptions')
      }
    }
    return {}
  }

  private async persistSubscriptions(): Promise<void> {
    await fs.promises.writeFile(this.subscriptionsPath, JSON.stringify(this.subscriptions, null, 2))
  }
}
