import { configService, ConfigService } from './config.service'
import { SavedThreads, Thread } from '../model'

export class ThreadService {
  private threads: SavedThreads

  constructor(private configService: ConfigService) {
    configService.selectedProject$.subscribe(
      (selectedProject) => (this.threads = selectedProject?.config?.savedThreads)
    )
  }

  saveThread(threadId: string, name: string) {
    if (!this.threads) {
      this.threads = {}
    }
    this.threads[threadId] = { name }
    this.saveThreads()
  }

  listThreads(): Thread[] {
    if (!this.threads) {
      return []
    }
    return Object.keys(this.threads).map((threadId) => ({
      threadId,
      name: this.threads![threadId].name,
    }))
  }

  deleteThread(threadId: string | undefined) {
    if (!threadId) {
      return
    }
    if (this.threads) {
      delete this.threads[threadId]
      this.saveThreads()
    }
  }

  private saveThreads(): void {
    this.configService.saveProjectConfig({ savedThreads: this.threads })
  }
}

export const threadService = new ThreadService(configService)
