import {configService, ConfigService} from "./config.service"
import {Thread} from "../model/thread"

export class ThreadService {
  
  constructor(private service: ConfigService) {
  }
  
  saveThread(threadId: string, name: string) {
    const project = this.service.project
    if (!project) {
      throw new Error("No project selected")
    }
    if (!project.savedThreads) {
      project.savedThreads = {}
    }
    project.savedThreads[threadId] = {name}
    this.service.saveProjectConfig()
  }
  
  listThreads(): Thread[] {
    const project = this.service.project
    if (!project || !project.savedThreads) {
      return []
    }
    return Object.keys(project.savedThreads).map(threadId => ({
      threadId,
      name: project.savedThreads![threadId].name
    }))
  }
  
  deleteThread(threadId: string | undefined) {
    if (!threadId) {
      return
    }
    const project = this.service.project
    if (project?.savedThreads) {
      delete project.savedThreads[threadId]
      this.service.saveProjectConfig()
    }
  }
}

export const threadService = new ThreadService(configService)