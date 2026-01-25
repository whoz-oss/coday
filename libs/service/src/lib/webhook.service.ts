import * as path from 'node:path'
import * as os from 'node:os'
import { randomUUID } from 'node:crypto'
import { existsSync, mkdirSync, readdirSync, unlinkSync } from 'fs'
import { readYamlFile } from './read-yaml-file'
import { writeYamlFile } from './write-yaml-file'

export interface Webhook {
  uuid: string
  name: string
  project: string
  createdBy: string
  createdAt: Date
  commandType: 'free' | 'template'
  commands?: string[]
}

export class WebhookService {
  private webhooksDir: string

  constructor(codayConfigPath?: string) {
    const defaultConfigPath = path.join(os.userInfo().homedir, '.coday')
    this.webhooksDir = path.join(codayConfigPath ?? defaultConfigPath, 'webhooks')

    // Ensure the webhooks directory exists
    mkdirSync(this.webhooksDir, { recursive: true })
  }

  /**
   * Creates a new webhook with generated UUID and timestamp
   */
  async create(webhook: Omit<Webhook, 'uuid' | 'createdAt'>): Promise<Webhook> {
    try {
      // Generate proper UUID v4
      const uuid = randomUUID()

      const newWebhook: Webhook = {
        ...webhook,
        uuid,
        createdAt: new Date(),
      }

      const filePath = path.join(this.webhooksDir, `${uuid}.yml`)

      // Check if file already exists (highly unlikely but defensive)
      if (existsSync(filePath)) {
        throw new Error(`Webhook with UUID ${uuid} already exists`)
      }

      writeYamlFile(filePath, newWebhook)
      return newWebhook
    } catch (error) {
      throw new Error(`Failed to create webhook: ${error instanceof Error ? error.message : 'Unknown error'}`)
    }
  }

  /**
   * Retrieves a webhook by UUID
   */
  async get(uuid: string): Promise<Webhook | null> {
    try {
      const filePath = path.join(this.webhooksDir, `${uuid}.yml`)
      const webhook = readYamlFile<Webhook>(filePath)

      if (!webhook) {
        return null
      }

      // Ensure createdAt is a Date object
      if (webhook.createdAt && typeof webhook.createdAt === 'string') {
        webhook.createdAt = new Date(webhook.createdAt)
      }

      return webhook
    } catch (error) {
      console.error(`Failed to get webhook ${uuid}:`, error)
      return null
    }
  }

  /**
   * Updates an existing webhook
   */
  async update(uuid: string, updates: Partial<Webhook>): Promise<Webhook | null> {
    try {
      const existing = await this.get(uuid)
      if (!existing) {
        return null
      }

      // Prevent changing UUID and createdAt
      const { uuid: _, createdAt: __, ...allowedUpdates } = updates

      const updatedWebhook: Webhook = {
        ...existing,
        ...allowedUpdates,
      }

      const filePath = path.join(this.webhooksDir, `${uuid}.yml`)
      writeYamlFile(filePath, updatedWebhook)

      return updatedWebhook
    } catch (error) {
      console.error(`Failed to update webhook ${uuid}:`, error)
      return null
    }
  }

  /**
   * Deletes a webhook by UUID
   */
  async delete(uuid: string): Promise<boolean> {
    try {
      const filePath = path.join(this.webhooksDir, `${uuid}.yml`)

      if (!existsSync(filePath)) {
        return false
      }

      unlinkSync(filePath)
      return true
    } catch (error) {
      console.error(`Failed to delete webhook ${uuid}:`, error)
      return false
    }
  }

  /**
   * Lists all webhooks
   */
  async list(): Promise<Webhook[]> {
    try {
      if (!existsSync(this.webhooksDir)) {
        return []
      }

      const files = readdirSync(this.webhooksDir)
      const webhookFiles = files.filter((file) => file.endsWith('.yml'))

      const webhooks: Webhook[] = []

      for (const file of webhookFiles) {
        const uuid = file.replace('.yml', '')
        const webhook = await this.get(uuid)
        if (webhook) {
          webhooks.push(webhook)
        }
      }

      // Sort by creation date (newest first)
      return webhooks.sort((a, b) => b.createdAt.getTime() - a.createdAt.getTime())
    } catch (error) {
      console.error('Failed to list webhooks:', error)
      return []
    }
  }

  /**
   * Returns the webhooks directory path
   */
  getWebhooksDir(): string {
    return this.webhooksDir
  }
}
